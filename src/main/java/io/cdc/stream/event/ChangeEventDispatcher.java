package io.cdc.stream.event;

import io.cdc.stream.apply.ChangeBuffer;
import io.cdc.stream.apply.ChangeEventApplier;
import io.cdc.stream.apply.ChangeRow;
import io.cdc.stream.apply.RecordConverter;
import io.cdc.stream.apply.UnrecoverableApplyException;
import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.ProducerConfig;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Receives every change event from the embedded engine and decides what constitutes one
 * commit unit. Runs entirely on the engine's own thread — there is no executor and no
 * queue, because the replication slot already delivers changes in commit order and any
 * concurrency here would discard that.
 *
 * <p>
 * The commit unit depends on {@code consumer.enable-transaction-boundary}:
 * <ul>
 * <li><b>off</b> — rows are flushed once {@code batchSize} accumulates, and again at the
 * end of every engine batch. Order is preserved; a source transaction may span several
 * sink commits.
 * <li><b>on</b> — rows are held until the transaction's END marker and the whole
 * transaction commits at once, so the sink never exposes a partial source transaction.
 * Snapshot records are exempt: the initial snapshot emits no transaction markers, so
 * those fall back to size-based flushing.
 * </ul>
 */
@Component
public class ChangeEventDispatcher {

	private final static Logger log = LoggerFactory.getLogger(ChangeEventDispatcher.class);

	private static final String HEARTBEAT = "debezium-heartbeat";

	private final RecordConverter converter;

	private final ChangeEventApplier applier;

	private final ConsumerConfig config;

	private final ProducerConfig producerConfig;

	private final ChangeBuffer buffer = new ChangeBuffer();

	/** Set when the pipeline has stopped for a reason retrying cannot fix. */
	private volatile Throwable fatal;

	/** Rows discarded by the origin filter, for periodic logging. */
	private long filtered;

	/**
	 * Origins seen on the stream, logged once each. This is the only direct evidence that
	 * a writer's replication origin actually reaches the WAL and survives decoding — the
	 * write-side "Claimed replication origin" message proves only that the session took
	 * it.
	 */
	private final Set<String> observedOrigins = new HashSet<>();

	public ChangeEventDispatcher(RecordConverter converter, ChangeEventApplier applier, ConsumerConfig config,
			ProducerConfig producerConfig) {
		this.converter = converter;
		this.applier = applier;
		this.config = config;
		this.producerConfig = producerConfig;
	}

	@PostConstruct
	void validate() {
		if (config.isEnableTransactionBoundary() && !producerConfig.isProvideTransactionMetadata()) {
			throw new IllegalStateException(
					"consumer.enable-transaction-boundary requires producer.provide-transaction-metadata=true. "
							+ "Without the BEGIN/END markers there is no reliable way to know where a source "
							+ "transaction ends, since a transaction can span several engine batches.");
		}
		log.info("Apply mode: {} (transactions per commit: {}, batch size: {}, schema evolution: {})",
				config.isEnableTransactionBoundary() ? "atomic per source transaction" : "ordered batches",
				config.getTransactionsPerCommit(), config.getBatchSize(), config.getSchemaEvolution());
		if (config.isIgnoreReplicatedChanges()) {
			log.info("Loop prevention: discarding every change that carries a replication origin");
		}
		else if (!config.getIgnoreOrigins().isEmpty()) {
			log.info("Loop prevention: discarding changes from replication origin(s) {}", config.getIgnoreOrigins());
		}
		else if (config.getApplyOriginName() != null && !config.getApplyOriginName().isBlank()) {
			log.warn("Writes are tagged with origin '{}' but no origin filter is configured. In a bidirectional "
					+ "topology set consumer.ignore-replicated-changes=true, or this pipeline will replay its "
					+ "peer's applies back to it.", config.getApplyOriginName());
		}
	}

	public void handleBatch(List<RecordChangeEvent<SourceRecord>> events,
			DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
		try {
			for (RecordChangeEvent<SourceRecord> event : events) {
				accept(event, committer);
			}
			// In atomic mode an unterminated transaction stays buffered across engine
			// batches: applying half of it is exactly what this mode exists to prevent.
			// Its offsets were never marked, so it is simply re-delivered on restart.
			if (!config.isEnableTransactionBoundary() || !buffer.hasOpenTransaction()) {
				flush(committer);
			}
			committer.markBatchFinished();
		}
		catch (UnrecoverableApplyException e) {
			fatal = e;
			log.error(
					"Change event apply failed unrecoverably; stopping the pipeline. "
							+ "{} row(s) in the current buffer were not committed and will be re-delivered.",
					buffer.rowCount(), e);
			throw e;
		}
		catch (RuntimeException e) {
			log.error("Change event batch failed after retries; stopping the pipeline", e);
			throw e;
		}
	}

	private void accept(RecordChangeEvent<SourceRecord> event,
			DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
		SourceRecord record = event.record();
		if (record == null || record.topic() == null || record.topic().contains(HEARTBEAT)) {
			skip(event, committer);
			return;
		}
		if (converter.isTransactionMarker(record)) {
			marker(event, committer);
			return;
		}

		ChangeRow row = converter.convert(record);
		if (row == null) {
			skip(event, committer);
			return;
		}
		// Counted before filtering so it can be reconciled against the END marker's
		// event_count, which the source computes over all captured collections.
		buffer.observeRow();
		if (row.origin() != null && observedOrigins.add(row.origin())) {
			log.info("First change event carrying replication origin '{}' seen on {} — origin propagation from the "
					+ "writer through the WAL to this consumer is working", row.origin(), row.table());
		}
		if (row.origin() != null
				&& (config.isIgnoreReplicatedChanges() || config.getIgnoreOrigins().contains(row.origin()))) {
			// Our own write, streamed back to us. Discarding it is what breaks the loop.
			// Safe to do per row: the origin is recorded per transaction, so either every
			// row of a transaction is filtered or none is.
			if (filtered++ % 10_000 == 0) {
				log.info("Discarding change from ignored origin '{}' ({} so far)", row.origin(), filtered);
			}
			skip(event, committer);
			return;
		}
		buffer.addEvent(event);
		buffer.addRow(row);
		if (shouldFlush(row)) {
			flush(committer);
		}
	}

	/**
	 * Handles a BEGIN/END marker. These define the commit unit in atomic mode, so the
	 * transaction id and declared event count are checked rather than assumed — a marker
	 * that goes missing or arrives out of order would otherwise silently redraw a commit
	 * boundary.
	 */
	private void marker(RecordChangeEvent<SourceRecord> event,
			DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
		SourceRecord record = event.record();
		String status = converter.markerStatus(record);
		// The raw id embeds the LSN, which differs between BEGIN and END for the same
		// transaction, so only the base id is comparable.
		String txId = converter.markerBaseTransactionId(record);

		if (RecordConverter.BEGIN.equals(status)) {
			buffer.addEvent(event);
			if (buffer.hasOpenTransaction()) {
				// Merging two transactions into one commit is still atomic — no partial
				// transaction is ever exposed — but it means a marker was lost.
				log.warn("BEGIN for transaction {} while {} is still open; its END marker was not delivered. "
						+ "Both will commit together.", txId, buffer.openTxId());
			}
			buffer.transactionBegan(txId);
			return;
		}
		if (RecordConverter.END.equals(status)) {
			buffer.addEvent(event);
			verifyBoundary(txId, converter.markerEventCount(record));
			buffer.transactionEnded(converter.markerCommitLsn(record));
			if (config.isEnableTransactionBoundary()
					&& buffer.completedTransactions() >= config.getTransactionsPerCommit()) {
				flush(committer);
			}
			return;
		}
		log.debug("Ignoring transaction marker with unrecognised status '{}' for {}", status, txId);
		skip(event, committer);
	}

	/**
	 * Cross-checks the END marker against what was actually buffered. Both mismatches are
	 * reported rather than fatal: the commit is still atomic, but the boundary is no
	 * longer provably the source's, and that is worth seeing in the log.
	 */
	private void verifyBoundary(String txId, Long declaredEventCount) {
		String open = buffer.openTxId();
		if (open != null && txId != null && !txId.equals(open)) {
			log.warn("END marker is for transaction {} but the open transaction is {}; "
					+ "transaction markers are interleaved or one was dropped", txId, open);
		}
		if (declaredEventCount != null && declaredEventCount != buffer.receivedForOpenTx()) {
			log.warn("Transaction {} declared {} change event(s) but {} arrived; " + "it is being committed incomplete",
					txId, declaredEventCount, buffer.receivedForOpenTx());
		}
	}

	/**
	 * An event carrying nothing to apply can have its offset advanced right away, but
	 * only while nothing is pending. Marking it while rows sit in the buffer would push
	 * the offset past those unwritten rows.
	 */
	private void skip(RecordChangeEvent<SourceRecord> event,
			DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
		if (buffer.isEmpty()) {
			committer.markProcessed(event);
		}
		else {
			buffer.addEvent(event);
		}
	}

	private boolean shouldFlush(ChangeRow row) {
		if (!config.isEnableTransactionBoundary()) {
			return buffer.rowCount() >= config.getBatchSize();
		}
		if (row.txId() == null) {
			// Snapshot or a connector build without transaction metadata: no END marker
			// is
			// coming, so fall back to size and time based flushing.
			return buffer.rowCount() >= config.getBatchSize() || buffer.olderThan(config.getFlushIntervalMs());
		}
		if (buffer.rowCount() >= config.getMaxBufferedRows()) {
			log.warn(
					"Source transaction {} exceeds consumer.max-buffered-rows ({}); flushing it in pieces. "
							+ "Atomicity is not preserved for this transaction.",
					row.txId(), config.getMaxBufferedRows());
			return true;
		}
		return false;
	}

	private void flush(DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
			throws InterruptedException {
		if (buffer.isEmpty()) {
			return;
		}
		if (buffer.rowCount() > 0) {
			int applied = applier.apply(buffer.rows(), buffer.commitLsn());
			log.debug("Committed {} row(s) across {} transaction(s) up to commit LSN {}", applied,
					buffer.completedTransactions(), buffer.commitLsn());
		}
		// Only now may offsets advance.
		for (RecordChangeEvent<SourceRecord> event : buffer.events()) {
			committer.markProcessed(event);
		}
		buffer.reset();
	}

	public Throwable fatal() {
		return fatal;
	}

	/**
	 * The engine is closed before this bean is destroyed, so nothing new can arrive.
	 * Anything still buffered belongs to an uncommitted transaction whose offsets were
	 * never marked, so it is discarded rather than half-applied — the source will
	 * re-deliver it.
	 */
	@PreDestroy
	void discardUncommitted() {
		if (buffer.rowCount() > 0) {
			log.info("Discarding {} uncommitted row(s) of transaction {} on shutdown; "
					+ "they will be re-delivered on restart", buffer.rowCount(), buffer.openTxId());
		}
		buffer.discard();
	}

}
