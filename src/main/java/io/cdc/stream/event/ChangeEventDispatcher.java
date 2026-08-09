package io.cdc.stream.event;

import io.cdc.stream.apply.ChangeBuffer;
import io.cdc.stream.apply.ChangeEventApplier;
import io.cdc.stream.apply.ChangeRow;
import io.cdc.stream.apply.RecordConverter;
import io.cdc.stream.apply.UnrecoverableApplyException;
import io.cdc.stream.config.ConsumerConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Receives every change event and decides what constitutes one commit unit. Transport
 * agnostic: the embedded engine and the Kafka listener both hand it {@link PipelineEvent}s
 * and an {@link Acknowledger}, so the commit-unit rules live in exactly one place.
 *
 * <p>
 * There is no executor and no queue: the source already delivers changes in order, and
 * introducing concurrency between receiving and applying would discard that. Where a
 * transport does assign several consuming threads, each owns a disjoint set of partitions
 * and gets its own buffer, so a commit unit is never built by more than one thread.
 *
 * <p>
 * The commit unit depends on {@code consumer.transaction-scope}:
 * <ul>
 * <li><b>none</b> — rows flush once {@code batchSize} accumulates and again at the end of
 * every delivery batch. Order is preserved; a source transaction may span several sink
 * commits.
 * <li><b>global</b> — rows are held until the transaction's END marker and the whole
 * transaction commits at once, across every table it touched. Needs BEGIN/END markers in
 * the same ordered stream as the rows: true for the embedded engine, and true on Kafka only
 * when the transaction topic is funnelled into the same single partition as the data.
 * <li><b>table</b> — the commit unit is one transaction's changes to one table. Uses no
 * markers at all: each row carries its own {@code txId}, so a change of transaction or of
 * table is the boundary. This is what per-table topics can support, since nothing
 * co-orders their partitions.
 * </ul>
 * Snapshot records are exempt from the marker-based modes: the initial snapshot emits no
 * transaction metadata, so those fall back to size and time based flushing.
 */
@Component
public class ChangeEventDispatcher {

	private final static Logger log = LoggerFactory.getLogger(ChangeEventDispatcher.class);

	private static final String HEARTBEAT = "debezium-heartbeat";

	private final RecordConverter converter;

	private final ChangeEventApplier applier;

	private final ConsumerConfig config;

	/**
	 * One buffer per consuming thread. The commit unit is a sequence, and two threads
	 * sharing one buffer would splice their sequences together — {@code addRow} against
	 * another thread's {@code flush} loses rows outright. Each listener thread owns a
	 * disjoint set of partitions, so per-thread state is exactly the right granularity.
	 */
	private final ThreadLocal<ChangeBuffer> buffers = ThreadLocal.withInitial(ChangeBuffer::new);

	/** Set when the pipeline has stopped for a reason retrying cannot fix. */
	private volatile Throwable fatal;

	/** Rows discarded by the origin filter, for periodic logging. */
	private final AtomicLong filtered = new AtomicLong();

	/**
	 * Records that reached the converter and produced nothing. Counted and reported because
	 * the alternative is invisible: a batch every record of which is undecodable is
	 * acknowledged and forgotten, which from the outside is indistinguishable from
	 * consuming nothing at all.
	 */
	private final AtomicLong undecodable = new AtomicLong();

	private volatile boolean reportedUndecodable;

	/**
	 * Origins seen on the stream, logged once each. This is the only direct evidence that a
	 * writer's replication origin actually reaches the WAL and survives decoding — the
	 * write-side "Claimed replication origin" message proves only that the session took it.
	 */
	private final Set<String> observedOrigins = ConcurrentHashMap.newKeySet();

	public ChangeEventDispatcher(RecordConverter converter, ChangeEventApplier applier, ConsumerConfig config) {
		this.converter = converter;
		this.applier = applier;
		this.config = config;
	}

	/** The calling thread's commit unit under construction. */
	private ChangeBuffer buffer() {
		return buffers.get();
	}

	/**
	 * Matches an incoming origin against {@code consumer.ignore-origins} as a <em>prefix</em>,
	 * not an exact string.
	 *
	 * <p>
	 * A peer's origins are a family — {@code <prefix>_<pipeline>_1}, {@code _2}, and so on,
	 * one per apply lane — and how many lanes it runs is its business, not something the
	 * other side should have to track. Configuring the family once therefore covers all of
	 * them, and adding a lane over there does not silently start leaking changes back.
	 */
	private boolean isIgnoredOrigin(String origin) {
		for (String ignored : config.getIgnoreOrigins()) {
			if (origin.equals(ignored) || origin.startsWith(ignored + '_')) {
				return true;
			}
		}
		return false;
	}

	public void handleBatch(List<PipelineEvent> events, Acknowledger ack) throws InterruptedException {
		try {
			for (PipelineEvent event : events) {
				accept(event, ack);
			}
			// In global mode an unterminated transaction stays buffered across delivery
			// batches: applying half of it is exactly what that mode exists to prevent.
			// Nothing was acknowledged, so it is simply re-delivered on restart.
			if (config.getTransactionScope() != ConsumerConfig.TransactionScope.global || !buffer().hasOpenTransaction()) {
				flush(ack);
			}
			ack.batchFinished();
		}
		catch (UnrecoverableApplyException e) {
			fatal = e;
			log.error("Change event apply failed unrecoverably; stopping the pipeline. "
					+ "{} row(s) in the current buffer were not committed and will be re-delivered.", buffer().rowCount(),
					e);
			throw e;
		}
		catch (RuntimeException e) {
			log.error("Change event batch failed after retries; stopping the pipeline", e);
			throw e;
		}
	}

	private void accept(PipelineEvent event, Acknowledger ack) throws InterruptedException {
		ConnectRecord<?> record = event.record();
		if (record == null || isHeartbeat(record)) {
			skip(event, ack);
			return;
		}
		if (converter.isTransactionMarker(record)) {
			marker(event, ack);
			return;
		}

		ChangeRow row = converter.convert(record);
		if (row == null) {
			reportUndecodable(record);
			skip(event, ack);
			return;
		}
		// Counted before filtering so it can be reconciled against the END marker's
		// event_count, which the source computes over all captured collections.
		buffer().observeRow();
		if (row.origin() != null && observedOrigins.add(row.origin())) {
			log.info("First change event carrying replication origin '{}' seen on {} — origin propagation from the "
					+ "writer through to this consumer is working", row.origin(), row.table());
		}
		if (row.origin() != null && (config.isIgnoreReplicatedChanges() || isIgnoredOrigin(row.origin()))) {
			// Our own write, streamed back to us. Discarding it is what breaks the loop.
			// Safe to do per row: the origin is recorded per transaction, so either every
			// row of a transaction is filtered or none is.
			long seen = filtered.incrementAndGet();
			if (seen % 10_000 == 1) {
				log.info("Discarding change from ignored origin '{}' ({} so far)", row.origin(), seen);
			}
			skip(event, ack);
			return;
		}
		// Checked before the row is added, because the boundary is in front of it.
		if (config.getTransactionScope() == ConsumerConfig.TransactionScope.table && buffer().crossesBoundary(row)) {
			flush(ack);
		}
		buffer().addEvent(event);
		buffer().addRow(row);
		if (shouldFlush(row)) {
			flush(ack);
		}
	}

	/**
	 * Names the first record that decodes to nothing, and keeps a count of the rest.
	 *
	 * <p>
	 * Almost always a topology problem rather than a data problem, and the two usual causes
	 * are worth naming outright: an {@code ExtractNewRecordState} SMT on the connector,
	 * which unwraps the envelope so there is no {@code op} or {@code source} left to read,
	 * and {@code schemas.enable=false} on the producer's JSON converter, which leaves no
	 * schema at all. Both discard every record silently otherwise.
	 */
	private void reportUndecodable(ConnectRecord<?> record) {
		long seen = undecodable.incrementAndGet();
		if (reportedUndecodable) {
			if (seen % 10_000 == 0) {
				log.warn("{} records so far carried no change event and were discarded", seen);
			}
			return;
		}
		reportedUndecodable = true;
		Object value = record.value();
		String shape = value == null ? "null value (tombstone?)"
				: value instanceof org.apache.kafka.connect.data.Struct struct
						? "fields " + struct.schema().fields().stream()
							.map(org.apache.kafka.connect.data.Field::name).toList()
						: value.getClass().getName();
		log.warn("Record on topic '{}' carries no change event and was discarded: {}. Every record of this shape will "
				+ "be dropped. Expected a Debezium envelope with 'op' and 'source' — check for an "
				+ "ExtractNewRecordState (unwrap) SMT on the connector, or schemas.enable=false on its converter.",
				record.topic(), shape);
	}

	/**
	 * Heartbeats are recognised by shape as well as by topic name. The topic check alone
	 * stops working the moment an SMT reroutes records — which is exactly what funnelling
	 * every table into one partition for {@code global} mode does.
	 */
	private boolean isHeartbeat(ConnectRecord<?> record) {
		return record.topic() != null && record.topic().contains(HEARTBEAT);
	}

	/**
	 * Handles a BEGIN/END marker. These define the commit unit in global mode, so the
	 * transaction id and declared event count are checked rather than assumed — a marker
	 * that goes missing or arrives out of order would otherwise silently redraw a commit
	 * boundary.
	 */
	private void marker(PipelineEvent event, Acknowledger ack) throws InterruptedException {
		ConnectRecord<?> record = event.record();
		String status = converter.markerStatus(record);
		// The raw id embeds the LSN, which differs between BEGIN and END for the same
		// transaction, so only the base id is comparable.
		String txId = converter.markerBaseTransactionId(record);

		if (RecordConverter.BEGIN.equals(status)) {
			buffer().addEvent(event);
			if (buffer().hasOpenTransaction()) {
				// Merging two transactions into one commit is still atomic — no partial
				// transaction is ever exposed — but it means a marker was lost.
				log.warn("BEGIN for transaction {} while {} is still open; its END marker was not delivered. "
						+ "Both will commit together.", txId, buffer().openTxId());
			}
			buffer().transactionBegan(txId);
			return;
		}
		if (RecordConverter.END.equals(status)) {
			buffer().addEvent(event);
			verifyBoundary(txId, converter.markerEventCount(record));
			buffer().transactionEnded(converter.markerCommitLsn(record));
			if (config.getTransactionScope() == ConsumerConfig.TransactionScope.global
					&& buffer().completedTransactions() >= config.getTransactionsPerCommit()) {
				flush(ack);
			}
			return;
		}
		log.debug("Ignoring transaction marker with unrecognised status '{}' for {}", status, txId);
		skip(event, ack);
	}

	/**
	 * Cross-checks the END marker against what was actually buffered. Both mismatches are
	 * reported rather than fatal: the commit is still atomic, but the boundary is no longer
	 * provably the source's, and that is worth seeing in the log.
	 */
	private void verifyBoundary(String txId, Long declaredEventCount) {
		String open = buffer().openTxId();
		if (open != null && txId != null && !txId.equals(open)) {
			log.warn("END marker is for transaction {} but the open transaction is {}; "
					+ "transaction markers are interleaved or one was dropped", txId, open);
		}
		if (declaredEventCount != null && declaredEventCount != buffer().receivedForOpenTx()) {
			log.warn("Transaction {} declared {} change event(s) but {} arrived; it is being committed incomplete",
					txId, declaredEventCount, buffer().receivedForOpenTx());
		}
	}

	/**
	 * An event carrying nothing to apply can be acknowledged right away, but only while
	 * nothing is pending. Acknowledging it while rows sit in the buffer would advance the
	 * offset past those unwritten rows.
	 */
	private void skip(PipelineEvent event, Acknowledger ack) throws InterruptedException {
		if (buffer().isEmpty()) {
			ack.processed(event);
		}
		else {
			buffer().addEvent(event);
		}
	}

	private boolean shouldFlush(ChangeRow row) {
		ConsumerConfig.TransactionScope scope = config.getTransactionScope();
		if (scope == ConsumerConfig.TransactionScope.none) {
			return buffer().rowCount() >= config.getBatchSize();
		}
		if (scope == ConsumerConfig.TransactionScope.table) {
			// The boundary itself is handled before the row is added; this is only the
			// safety valve for a single transaction larger than one batch.
			return buffer().rowCount() >= config.getBatchSize() || buffer().olderThan(config.getFlushIntervalMs());
		}
		if (row.txId() == null) {
			// Snapshot, or a source without transaction metadata: no END marker is coming,
			// so fall back to size and time based flushing.
			return buffer().rowCount() >= config.getBatchSize() || buffer().olderThan(config.getFlushIntervalMs());
		}
		if (buffer().rowCount() >= config.getMaxBufferedRows()) {
			log.warn("Source transaction {} exceeds consumer.max-buffered-rows ({}); flushing it in pieces. "
					+ "Atomicity is not preserved for this transaction.", row.txId(), config.getMaxBufferedRows());
			return true;
		}
		return false;
	}

	private void flush(Acknowledger ack) throws InterruptedException {
		if (buffer().isEmpty()) {
			return;
		}
		if (buffer().rowCount() > 0) {
			int applied = applier.apply(buffer().rows(), buffer().commitLsn(), buffer().positions());
			log.debug("Committed {} row(s) across {} transaction(s) up to commit LSN {}", applied,
					buffer().completedTransactions(), buffer().commitLsn());
		}
		// Only now may offsets advance.
		for (PipelineEvent event : buffer().events()) {
			ack.processed(event);
		}
		buffer().reset();
	}

	public Throwable fatal() {
		return fatal;
	}

	/**
	 * Called when no more events can arrive for the current assignment — shutdown, or
	 * partition revocation during a rebalance. Anything still buffered belongs to an
	 * uncommitted unit that was never acknowledged, so it is discarded rather than
	 * half-applied; the source re-delivers it.
	 */
	public void discardUncommitted() {
		if (buffer().rowCount() > 0) {
			log.info("Discarding {} uncommitted row(s) of transaction {}; they will be re-delivered",
					buffer().rowCount(), buffer().openTxId());
		}
		buffer().discard();
	}

}
