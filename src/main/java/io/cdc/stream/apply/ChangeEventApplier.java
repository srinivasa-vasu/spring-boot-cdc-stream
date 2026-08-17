package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

	private final ConflictLog conflictLog;

	private final ApplyStateStore stateStore;

	private final KafkaOffsetStore offsetStore;

	private final ConsumerConfig config;

	public ChangeEventApplier(ApplyLanes lanes, RetryTemplate retryTemplate, SqlGenerator sqlGenerator,
			SinkPrecondition sinkPrecondition, ConflictLog conflictLog, ApplyStateStore stateStore,
			KafkaOffsetStore offsetStore,
			ConsumerConfig config) {
		this.lanes = lanes;
		this.retryTemplate = retryTemplate;
		this.sqlGenerator = sqlGenerator;
		this.sinkPrecondition = sinkPrecondition;
		this.conflictLog = conflictLog;
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

	/**
	 * This table's last-writer-wins guard, or null when it has none.
	 *
	 * <p>
	 * The strictness carries the tiebreak: {@code <} rejects an equal version, {@code <=}
	 * accepts it. With {@code conflict-tiebreak=nodeId} the two deployments derive opposite
	 * answers from the same rule, so exactly one of them accepts a tie and both converge.
	 */
	private SqlGenerator.Guard guardFor(ChangeRow row) {
		String column = sinkPrecondition.conflictColumnOf(row.table());
		return column == null ? null : new SqlGenerator.Guard(column, !config.incomingWinsTies());
	}

	private SqlGenerator.Statement statementFor(ChangeRow row) {
		return statementFor(row, guardFor(row));
	}

	private SqlGenerator.Statement statementFor(ChangeRow row, SqlGenerator.Guard guard) {
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
			case c, r -> sqlGenerator.upsert(schema, row.values().keySet(), guard);
			case u -> row.fullImage() ? sqlGenerator.upsert(schema, row.values().keySet(), guard)
					: sqlGenerator.update(schema, requireKey(row), guard);
			case d -> {
				requireKey(row);
				yield sqlGenerator.delete(schema, row.values().keySet(), guard);
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
				if (affected == 0) {
					classify(lane, row);
				}
				return;
			}
			int[] counts = lane.jdbcTemplate().batchUpdate(statement.sql(), new BatchPreparedStatementSetter() {
				@Override
				public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
					bind(ps, statement, run.get(i));
				}

				@Override
				public int getBatchSize() {
					return run.size();
				}
			});
			if (!allApplied(counts, run.size())) {
				findLosers(lane, run);
			}
		}
		catch (RuntimeException e) {
			log.error("Failed applying {} {} row(s) to {} (keys: {}) with: {}", run.size(), run.getFirst().op(),
					run.getFirst().table(), run.stream().map(this::keyOf).limit(20).toList(), statement.sql());
			throw e;
		}
	}

	/**
	 * Whether the batch accounted for every row.
	 *
	 * <p>
	 * Returns false when any count is {@code SUCCESS_NO_INFO} as well as when the total
	 * falls short. With {@code reWriteBatchedInserts} the driver collapses a batch into one
	 * multi-row statement and reports no per-row counts at all, so "cannot tell" has to be
	 * treated the same as "something was rejected" — the alternative is missing losers
	 * silently, which is the whole failure this exists to prevent.
	 */
	private static boolean allApplied(int[] counts, int expected) {
		int accounted = 0;
		for (int count : counts) {
			if (count < 0) {
				return false;
			}
			accounted += count;
		}
		return accounted >= expected;
	}

	/**
	 * Second phase: work out which rows of the batch applied to nothing.
	 *
	 * <p>
	 * Deliberately a <em>read-only</em> probe rather than re-running the statement. Replaying
	 * with a relaxed comparison would tell an applied row from a rejected one, but it would
	 * also re-apply any row that lost a tie — silently undoing the decision the tiebreak had
	 * just made. A probe cannot change the outcome it is measuring.
	 *
	 * <p>
	 * Only reached when the batch could not account for every row, so the common case pays
	 * nothing.
	 */
	private void findLosers(ApplyLane lane, List<ChangeRow> run) {
		for (ChangeRow row : run) {
			if (row.schema() != null && row.schema().hasKey() && !wasApplied(lane, row)) {
				classify(lane, row);
			}
		}
	}

	/**
	 * Asks the sink whether the guard would accept this change, in one statement.
	 *
	 * <p>
	 * Evaluated in SQL with the same comparison the apply used, so it needs no type handling
	 * of its own — a timestamp read back through JDBC and one converted from the change event
	 * are not the same Java type, and comparing them here would be a quiet source of wrong
	 * answers.
	 * @return false when the row is absent or the guard would refuse, which are the two ways
	 * a change applies to nothing
	 */
	private boolean wasApplied(ApplyLane lane, ChangeRow row) {
		SqlGenerator.Guard guard = guardFor(row);
		List<String> keys = row.schema().keyColumns();
		StringBuilder sql = new StringBuilder("SELECT ");
		List<Object> args = new ArrayList<>();
		if (guard == null || !row.values().containsKey(guard.column())) {
			sql.append("true");
		}
		else {
			sql.append(TableId.quote(guard.column())).append(guard.comparison()).append('?');
			args.add(row.values().get(guard.column()));
		}
		sql.append(" FROM ").append(row.table().qualified()).append(" WHERE ");
		for (int i = 0; i < keys.size(); i++) {
			sql.append(i == 0 ? "" : " AND ").append(TableId.quote(keys.get(i))).append(" = ?");
			args.add(row.values().get(keys.get(i)));
		}
		Boolean accepted = lane.jdbcTemplate().query(sql.toString(),
				rs -> rs.next() ? rs.getBoolean(1) : null, args.toArray());
		// Absent row: a delete succeeded, or an update had nothing to act on. Either way it
		// did not apply, and classify() decides which of those it was.
		return Boolean.TRUE.equals(accepted);
	}

	/**
	 * Decides why a change applied to nothing, and records it.
	 *
	 * <p>
	 * An upsert cannot miss for want of a row — it would have inserted one — so zero rows
	 * means the guard refused it. A partial update or a delete genuinely might have no row
	 * to act on, which is a different fact and worth distinguishing, so those probe.
	 * A delete whose row is already gone is not a conflict at all: it simply succeeded, or
	 * something else removed the row first.
	 */
	private void classify(ApplyLane lane, ChangeRow row) {
		SqlGenerator.Guard guard = guardFor(row);
		boolean upsert = row.op() != io.cdc.stream.event.OPERATION.d
				&& (row.fullImage() || row.op() != io.cdc.stream.event.OPERATION.u);
		if (upsert) {
			if (guard != null) {
				conflictLog.record(lane.jdbcTemplate(), row, ConflictLog.Reason.stale, row.values().get(guard.column()));
			}
			return;
		}
		boolean present = present(lane, row);
		if (row.isDelete()) {
			if (present) {
				conflictLog.record(lane.jdbcTemplate(), row, ConflictLog.Reason.stale,
						guard == null ? null : row.values().get(guard.column()));
			}
			return;
		}
		conflictLog.record(lane.jdbcTemplate(), row, present ? ConflictLog.Reason.stale : ConflictLog.Reason.row_missing,
				guard == null ? null : row.values().get(guard.column()));
	}

	/** One narrow existence probe, only on the path where a change already applied to nothing. */
	private boolean present(ApplyLane lane, ChangeRow row) {
		List<String> keys = row.schema().keyColumns();
		StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(row.table().qualified()).append(" WHERE ");
		for (int i = 0; i < keys.size(); i++) {
			sql.append(i == 0 ? "" : " AND ").append(TableId.quote(keys.get(i))).append(" = ?");
		}
		Object[] args = keys.stream().map(column -> row.values().get(column)).toArray();
		return Boolean.TRUE.equals(lane.jdbcTemplate().query(sql.toString(), ResultSet::next, args));
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
