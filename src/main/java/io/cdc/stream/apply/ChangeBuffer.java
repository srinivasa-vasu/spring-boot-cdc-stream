package io.cdc.stream.apply;

import io.cdc.stream.event.PipelineEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the rows of the current commit unit, along with every event that unit covers.
 *
 * <p>
 * Holding the events matters as much as holding the rows: they are what gets acknowledged
 * back to the transport, and that must happen strictly <em>after</em> the rows have
 * committed. Acknowledging an event before its row is written is what made the previous
 * implementation at-most-once — Debezium would flush an offset past a row still sitting in
 * a queue, and a Kafka commit would do exactly the same thing.
 *
 * <p>
 * Confined to a single consuming thread, so no synchronization.
 */
public final class ChangeBuffer {

	private final List<PipelineEvent> events = new ArrayList<>();

	private final List<ChangeRow> rows = new ArrayList<>();

	/**
	 * Highest offset seen per topic-partition in this unit. Written into the sink
	 * transaction so a restart can seek precisely, rather than trusting a Kafka commit that
	 * may lag or race a rebalance.
	 */
	private final Map<String, PartitionPosition> positions = new LinkedHashMap<>();

	private boolean openTransaction;

	/** Id from the BEGIN marker of the transaction currently being buffered. */
	private String openTxId;

	/**
	 * Data events seen for the open transaction, counted before any filtering so it can be
	 * compared against the {@code event_count} the END marker declares.
	 */
	private int receivedForOpenTx;

	private int completedTransactions;

	private Long commitLsn;

	/**
	 * Transaction and table of the last row added. Without BEGIN/END markers in the same
	 * ordered stream — which is the situation on per-table topics — a change in either is
	 * the only commit boundary available.
	 */
	private String lastRowTxId;

	private TableId lastRowTable;

	private long openedAt = System.currentTimeMillis();

	public void addEvent(PipelineEvent event) {
		if (events.isEmpty()) {
			openedAt = System.currentTimeMillis();
		}
		events.add(event);
		if (event.position() != null) {
			PartitionPosition position = event.position();
			String key = position.topic() + '-' + position.partition();
			PartitionPosition seen = positions.get(key);
			if (seen == null || position.offset() > seen.offset()) {
				positions.put(key, position);
			}
		}
	}

	public void addRow(ChangeRow row) {
		rows.add(row);
		lastRowTxId = row.txId();
		lastRowTable = row.table();
	}

	/**
	 * Whether adding this row would put two different source transactions, or two different
	 * tables, into one commit unit. Used only where transaction markers are unavailable.
	 */
	public boolean crossesBoundary(ChangeRow row) {
		if (rows.isEmpty()) {
			return false;
		}
		if (lastRowTable != null && !lastRowTable.equals(row.table())) {
			return true;
		}
		// A null id on either side means the source sent no transaction metadata (the
		// snapshot does this). Treat that as "no boundary information" rather than as a
		// boundary, or every snapshot row would commit on its own.
		return lastRowTxId != null && row.txId() != null && !lastRowTxId.equals(row.txId());
	}

	public void transactionBegan(String txId) {
		openTransaction = true;
		openTxId = txId;
		receivedForOpenTx = 0;
	}

	/**
	 * @param commitLsn the transaction's commit LSN, from the END marker's id. This — not
	 * the highest row LSN — is the transaction's actual commit position, and so the correct
	 * watermark.
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

	/** Highest offset per topic-partition in this unit; empty for the embedded engine. */
	public List<PartitionPosition> positions() {
		return List.copyOf(positions.values());
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

	public List<PipelineEvent> events() {
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
		positions.clear();
		completedTransactions = 0;
		commitLsn = null;
		lastRowTxId = null;
		lastRowTable = null;
		openedAt = System.currentTimeMillis();
	}

	/**
	 * Discards buffered work without touching the open-transaction state, used on shutdown
	 * or partition revocation when an unterminated transaction is deliberately abandoned
	 * for re-delivery.
	 */
	public void discard() {
		reset();
		openTransaction = false;
		openTxId = null;
		receivedForOpenTx = 0;
	}

}
