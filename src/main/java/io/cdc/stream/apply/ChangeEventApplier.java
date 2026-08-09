package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.retry.RetryTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * The one place rows are written to the sink. Handles DML and DDL for every table, in
 * source order.
 *
 * <p>
 * Two properties this deliberately preserves:
 * <ul>
 * <li><b>Order.</b> The replication slot delivers changes in commit order and the engine
 * calls us on a single thread, so the ordering guarantee already exists by the time a
 * record arrives. Rows are only ever coalesced into a batch when they are
 * <em>adjacent</em> and share a statement, so batching never reorders them. (The previous
 * implementation sorted rows into separate insert and delete lists across four per-table
 * queues, each drained by its own thread.)
 * <li><b>Atomicity.</b> Everything handed to {@link #apply} commits as one transaction,
 * together with the LSN watermark, so a reader of the sink never observes a partially
 * applied source transaction and a replay after a crash is idempotent.
 * </ul>
 */
@Component
public class ChangeEventApplier {

	private final static Logger log = LoggerFactory.getLogger(ChangeEventApplier.class);

	private final ApplyLanes lanes;

	private final RetryTemplate retryTemplate;

	private final SqlGenerator sqlGenerator;

	private final SinkPrecondition sinkPrecondition;

	private final ApplyStateStore stateStore;

	private final KafkaOffsetStore offsetStore;

	private final ConsumerConfig config;

	public ChangeEventApplier(ApplyLanes lanes, RetryTemplate retryTemplate, SqlGenerator sqlGenerator,
			SinkPrecondition sinkPrecondition, ApplyStateStore stateStore, KafkaOffsetStore offsetStore,
			ConsumerConfig config) {
		this.lanes = lanes;
		this.retryTemplate = retryTemplate;
		this.sqlGenerator = sqlGenerator;
		this.sinkPrecondition = sinkPrecondition;
		this.stateStore = stateStore;
		this.offsetStore = offsetStore;
		this.config = config;
	}

	/**
	 * Applies the given rows as a single sink transaction.
	 *
	 * <p>
	 * The retry wraps the whole unit, never an individual statement: a serialization
	 * failure (40001) dooms the entire transaction, so retrying one statement inside it
	 * would only re-fail. Each attempt therefore begins a fresh transaction, and every
	 * statement is idempotent so a retry after a partial failure converges.
	 * @param commitLsn commit LSN of the last transaction in this unit, taken from its
	 * END marker. Falls back to the highest row LSN when transaction metadata is
	 * unavailable, as during the initial snapshot.
	 * @param positions Kafka coordinates covered by this unit, written inside the same
	 * transaction so the sink — not Kafka's committed offset — is the authority on progress.
	 * @return number of rows written
	 */
	@SneakyThrows
	public int apply(List<ChangeRow> rows, Long commitLsn, List<PartitionPosition> positions) {
		long watermark = commitLsn != null ? commitLsn : highestRowLsn(rows);
		if (watermark > 0) {
			stateStore.observeCommitLsn(watermark);
		}
		List<ChangeRow> pending = fresh(rows);
		if (pending.isEmpty()) {
			return 0;
		}
		return retryTemplate.execute(() -> {
			try {
				return attempt(pending, watermark, positions);
			}
			catch (RuntimeException e) {
				// Force a re-read of the sink's real columns on the next attempt.
				pending.forEach(row -> sinkPrecondition.invalidate(row.table()));
				throw e;
			}
		});
	}

	private int attempt(List<ChangeRow> rows, long watermark, List<PartitionPosition> positions) {
		// Read-only, and outside the row transaction: a sink that cannot accept these rows
		// should fail before any of them is written.
		distinctSchemas(rows).values().forEach(sinkPrecondition::verify);

		// After verification, because it needs the sink's real columns. A source column the
		// sink lacks is dropped when its value is null and fatal when it is not — the
		// schema-level check cannot make that call, since it never sees a value.
		List<ChangeRow> pending = project(rows);

		// This thread's lane, and therefore this thread's connection. Everything below
		// must go through it or it lands outside the transaction.
		ApplyLane lane = lanes.current();
		String lastTxId = pending.getLast().txId();
		lane.transactionTemplate().executeWithoutResult(status -> {
			applyRuns(lane, pending);
			// The LSN watermark is a single row per pipeline, so with parallel lanes every
			// lane's transaction would contend on it and serialise them all — and a global
			// commit position means little once lanes advance independently. The Kafka
			// offsets below are already per topic-partition, so they carry restart state
			// without contention.
			if (watermark > 0 && lanes.isSingleLane()) {
				stateStore.record(lane.jdbcTemplate(), lastTxId, watermark);
			}
			// Same transaction as the rows: either both are durable or neither is, which
			// is what makes a replay after a crash recognisable rather than guesswork.
			offsetStore.record(lane.jdbcTemplate(), positions);
		});
		if (lanes.isSingleLane()) {
			stateStore.committed(watermark);
		}
		return pending.size();
	}

	/**
	 * Narrows every row onto the columns the sink actually has. Returns the same list when
	 * nothing needs narrowing, which is the common case.
	 */
	private List<ChangeRow> project(List<ChangeRow> rows) {
		List<ChangeRow> projected = null;
		for (int i = 0; i < rows.size(); i++) {
			ChangeRow row = rows.get(i);
			ChangeRow narrowed = ColumnProjection.project(row, sinkPrecondition.columnsOf(row.table()));
			if (narrowed != row && projected == null) {
				projected = new ArrayList<>(rows.subList(0, i));
			}
			if (projected != null) {
				projected.add(narrowed);
			}
		}
		return projected == null ? rows : projected;
	}

	private long highestRowLsn(List<ChangeRow> rows) {
		return rows.stream()
				.map(ChangeRow::lsn)
				.filter(java.util.Objects::nonNull)
				.mapToLong(Long::longValue)
				.max()
				.orElse(0L);
	}

	/** Drops rows an earlier run already committed, using the watermark in the sink. */
	private List<ChangeRow> fresh(List<ChangeRow> rows) {
		List<ChangeRow> pending = new ArrayList<>(rows.size());
		int skipped = 0;
		for (ChangeRow row : rows) {
			if (stateStore.alreadyApplied(row.lsn())) {
				skipped++;
			}
			else {
				pending.add(row);
			}
		}
		if (skipped > 0) {
			log.info("Skipped {} already-applied row(s) at or below LSN {} (re-delivery after restart)", skipped,
					stateStore.lastLsn());
		}
		return pending;
	}

	private Map<TableId, TableSchema> distinctSchemas(List<ChangeRow> rows) {
		Map<TableId, TableSchema> schemas = new LinkedHashMap<>();
		rows.forEach(row -> {
			if (row.schema() != null) {
				schemas.put(row.table(), row.schema());
			}
		});
		return schemas;
	}

	/**
	 * Walks the rows in order, coalescing each maximal run of adjacent rows that share a
	 * statement into one JDBC batch.
	 *
	 * <p>
	 * A run is also cut when a key repeats. Two changes to the same key must not land in
	 * one batch, because the driver may rewrite a batch of inserts into a single
	 * multi-row {@code INSERT}, and one {@code ON CONFLICT DO UPDATE} command cannot
	 * touch the same row twice — PostgreSQL raises {@code 21000} rather than applying
	 * them in order. Cutting the run keeps both changes, in order, as separate commands.
	 */
	private void applyRuns(ApplyLane lane, List<ChangeRow> rows) {
		int index = 0;
		while (index < rows.size()) {
			ChangeRow head = rows.get(index);
			if (head.isTruncate()) {
				log.info("Applying TRUNCATE to {}", head.table());
				lane.jdbcTemplate().execute(sqlGenerator.truncate(head.table()));
				index++;
				continue;
			}
			SqlGenerator.Statement statement = statementFor(head);
			if (statement == null) {
				index++;
				continue;
			}
			List<ChangeRow> run = new ArrayList<>();
			run.add(head);
			Set<List<Object>> keysInRun = new HashSet<>();
			keysInRun.add(keyTuple(head));
			int next = index + 1;
			while (next < rows.size() && run.size() < config.getBatchSize()) {
				ChangeRow candidate = rows.get(next);
				if (candidate.isTruncate()) {
					break;
				}
				SqlGenerator.Statement candidateStatement = statementFor(candidate);
				if (candidateStatement == null || !candidateStatement.sql().equals(statement.sql())) {
					break;
				}
				if (!keysInRun.add(keyTuple(candidate))) {
					// Same key twice: end the batch here so each change is its own command.
					break;
				}
				run.add(candidate);
				next++;
			}
			execute(lane, statement, run);
			index = next;
		}
	}

	private List<Object> keyTuple(ChangeRow row) {
		List<Object> key = new ArrayList<>(row.schema().keyColumns().size());
		row.schema().keyColumns().forEach(column -> key.add(row.values().get(column)));
		return key;
	}

	private SqlGenerator.Statement statementFor(ChangeRow row) {
		TableSchema schema = row.schema();
		if (schema == null) {
			return null;
		}
		if (!schema.hasKey()) {
			throw new UnrecoverableApplyException("Cannot apply " + row.op() + " to " + row.table()
					+ ": the change event carries no key. Add a primary key to the source table, "
					+ "or stop capturing it in the Connect connector.");
		}
		return switch (row.op()) {
			case c, r -> sqlGenerator.upsert(schema, row.values().keySet());
			case u -> row.fullImage() ? sqlGenerator.upsert(schema, row.values().keySet())
					: sqlGenerator.update(schema, requireKey(row));
			case d -> {
				requireKey(row);
				yield sqlGenerator.delete(schema);
			}
			default -> null;
		};
	}

	/**
	 * A partial update or a delete is targeted by key, so a missing key value would hit
	 * the wrong row — or every row. Fail loudly instead.
	 */
	private java.util.Set<String> requireKey(ChangeRow row) {
		if (!row.values().keySet().containsAll(row.schema().keyColumns())) {
			throw new UnrecoverableApplyException(String.format(
					"%s event for %s is missing key column(s) %s. Set REPLICA IDENTITY FULL (or DEFAULT) "
							+ "on the source table; note that in YugabyteDB the replica identity is fixed when the "
							+ "replication slot is created, so an existing slot may need to be recreated.",
					row.op(), row.table(), row.schema().keyColumns()));
		}
		return row.values().keySet();
	}

	private void execute(ApplyLane lane, SqlGenerator.Statement statement, List<ChangeRow> run) {
		try {
			if (run.size() == 1) {
				ChangeRow row = run.getFirst();
				int affected = lane.jdbcTemplate().update(statement.sql(), ps -> bind(ps, statement, row));
				if (affected == 0 && row.op() == io.cdc.stream.event.OPERATION.u) {
					log.warn("Update for {} key {} matched no sink row; the sink may be missing this row", row.table(),
							keyOf(row));
				}
				return;
			}
			lane.jdbcTemplate().batchUpdate(statement.sql(), new BatchPreparedStatementSetter() {
				@Override
				public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
					bind(ps, statement, run.get(i));
				}

				@Override
				public int getBatchSize() {
					return run.size();
				}
			});
		}
		catch (RuntimeException e) {
			log.error("Failed applying {} {} row(s) to {} (keys: {}) with: {}", run.size(), run.getFirst().op(),
					run.getFirst().table(), run.stream().map(this::keyOf).limit(20).toList(), statement.sql());
			throw e;
		}
	}

	private void bind(PreparedStatement ps, SqlGenerator.Statement statement, ChangeRow row) throws SQLException {
		List<String> columns = statement.bindColumns();
		for (int i = 0; i < columns.size(); i++) {
			ps.setObject(i + 1, row.values().get(columns.get(i)));
		}
	}

	private Map<String, Object> keyOf(ChangeRow row) {
		Map<String, Object> key = new LinkedHashMap<>();
		row.schema().keyColumns().forEach(column -> key.put(column, row.values().get(column)));
		return key;
	}

}
