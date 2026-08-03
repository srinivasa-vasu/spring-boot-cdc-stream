package io.cdc.stream.apply;

import io.debezium.engine.RecordChangeEvent;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Accumulates the rows of the current commit unit, along with every engine event that
 * unit covers.
 *
 * <p>
 * Holding the events matters as much as holding the rows: the events are what get handed
 * to {@code RecordCommitter.markProcessed}, and that must happen strictly <em>after</em>
 * the rows have committed. Marking an event as processed before its row is written is
 * what made the previous implementation at-most-once — Debezium would flush an offset
 * past a row that was still sitting in a queue.
 *
 * <p>
 * Confined to the single engine thread, so no synchronization.
 */
public final class ChangeBuffer {

	private final List<RecordChangeEvent<SourceRecord>> events = new ArrayList<>();

	private final List<ChangeRow> rows = new ArrayList<>();

	private boolean openTransaction;

	/** Id from the BEGIN marker of the transaction currently being buffered. */
	private String openTxId;

	/**
	 * Data events seen for the open transaction, counted before any filtering so it can
	 * be compared against the {@code event_count} the END marker declares.
	 */
	private int receivedForOpenTx;

	private int completedTransactions;

	private Long commitLsn;

	private long openedAt = System.currentTimeMillis();

	public void addEvent(RecordChangeEvent<SourceRecord> event) {
		if (events.isEmpty()) {
			openedAt = System.currentTimeMillis();
		}
		events.add(event);
	}

	public void addRow(ChangeRow row) {
		rows.add(row);
	}

	public void transactionBegan(String txId) {
		openTransaction = true;
		openTxId = txId;
		receivedForOpenTx = 0;
	}

	/**
	 * @param commitLsn the transaction's commit LSN, from the END marker's id. This — not
	 * the highest row LSN — is the transaction's actual commit position, and so the
	 * correct watermark.
	 */
	public void transactionEnded(Long commitLsn) {
		openTransaction = false;
		openTxId = null;
		receivedForOpenTx = 0;
		completedTransactions++;
		if (commitLsn != null && (this.commitLsn == null || commitLsn > this.commitLsn)) {
			this.commitLsn = commitLsn;
		}
	}

	/** Highest commit LSN among the transactions in this buffer, or null if unknown. */
	public Long commitLsn() {
		return commitLsn;
	}

	/** Counted for every data event, including ones later discarded by a filter. */
	public void observeRow() {
		receivedForOpenTx++;
	}

	public String openTxId() {
		return openTxId;
	}

	public int receivedForOpenTx() {
		return receivedForOpenTx;
	}

	/** True while a BEGIN has been seen without its matching END. */
	public boolean hasOpenTransaction() {
		return openTransaction;
	}

	public int completedTransactions() {
		return completedTransactions;
	}

	public List<RecordChangeEvent<SourceRecord>> events() {
		return events;
	}

	public List<ChangeRow> rows() {
		return rows;
	}

	public int rowCount() {
		return rows.size();
	}

	public boolean isEmpty() {
		return events.isEmpty();
	}

	public boolean olderThan(long millis) {
		return !events.isEmpty() && System.currentTimeMillis() - openedAt >= millis;
	}

	public void reset() {
		events.clear();
		rows.clear();
		completedTransactions = 0;
		commitLsn = null;
		openedAt = System.currentTimeMillis();
	}

	/**
	 * Discards buffered work without touching the open-transaction state, used on
	 * shutdown when an unterminated transaction is deliberately abandoned for
	 * re-delivery.
	 */
	public void discard() {
		reset();
		openTransaction = false;
		openTxId = null;
		receivedForOpenTx = 0;
	}

}
