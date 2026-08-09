package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks that the sink can accept the rows, before any are written. Read-only: this issues
 * no DDL and never modifies the sink.
 *
 * <p>
 * This pipeline replicates <em>data</em>. The sink's shape is somebody else's
 * responsibility — a migration tool, or whoever owns the target — and deliberately so:
 * PostgreSQL and YugabyteDB logical decoding emit no DDL events, so the only way to infer a
 * schema change here is to diff the Connect schema against the sink, and that inference
 * cannot tell a rename from a drop plus an add, cannot backfill a new column, and would be
 * reshaping tables this pipeline does not own.
 *
 * <p>
 * What it will not do is start writing into a sink that cannot take the rows, because these
 * failures are unusually hard to read once they happen mid-batch:
 * <ul>
 * <li>a missing table is {@code 42P01},
 * <li>a missing {@code ON CONFLICT} target is {@code 42P10} — which PostgreSQL reports as a
 * syntax-class error even though the statement is perfectly well formed,
 * <li>a too-narrow column is a truncation or out-of-range error thousands of rows in.
 * </ul>
 * Spring folds the first two into {@code BadSqlGrammarException}, so the generated SQL alone
 * rarely identifies the cause. Failing here instead names the table, the columns, and what
 * to do about it.
 *
 * <p>
 * Runs once per table per distinct schema shape — the fingerprint is the cache key, so a
 * source {@code ALTER} re-triggers verification rather than coasting on a stale result.
 * Uses the metadata pool: these are {@code DatabaseMetaData} reads, which belong neither in
 * the row transaction nor on the single origin-tagged apply connection.
 */
@Component
public class SinkPrecondition {

	private final static Logger log = LoggerFactory.getLogger(SinkPrecondition.class);

	private final JdbcTemplate jdbcTemplate;

	/** Fingerprint of the last schema verified per table, to skip the common case. */
	private final Map<TableId, String> verified = new ConcurrentHashMap<>();

	/** The sink's real columns per table, so the applier can narrow rows onto them. */
	private final Map<TableId, Set<String>> knownColumns = new ConcurrentHashMap<>();

	private final ConsumerConfig config;

	public SinkPrecondition(@Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate, ConsumerConfig config) {
		this.jdbcTemplate = jdbcTemplate;
		this.config = config;
	}

	/**
	 * The sink's columns for a table, or null if it has not been verified yet — a truncate
	 * carries no schema and so never is.
	 */
	Set<String> columnsOf(TableId table) {
		return knownColumns.get(table);
	}

	/**
	 * Verifies the sink table against the incoming schema. Cheap after the first call for a
	 * given fingerprint.
	 * @throws UnrecoverableApplyException when the sink cannot accept these rows. Not
	 * retryable: no amount of waiting fixes a missing column.
	 */
	public void verify(TableSchema schema) {
		if (schema == null || schema.fingerprint().equals(verified.get(schema.table()))) {
			return;
		}
		Map<String, String> sink = sinkColumns(schema.table());
		knownColumns.put(schema.table(), Set.copyOf(sink.keySet()));
		if (sink.isEmpty()) {
			throw new UnrecoverableApplyException(String.format(
					"Sink table %s does not exist. This pipeline replicates data only and will not create it — "
							+ "create it in the sink (or run your migrations) and restart.",
					schema.table()));
		}
		verifyColumns(schema, sink);
		verifyConflictTarget(schema);
		verified.put(schema.table(), schema.fingerprint());
		log.info("Sink table {} accepts the change event schema: {} column(s), key {}", schema.table(),
				schema.columns().size(), schema.keyColumns());
	}

	/**
	 * Every column the change events carry must exist in the sink and be able to store the
	 * incoming values.
	 *
	 * <p>
	 * A sink column that is deliberately <em>wider</em> than the source is not a problem and
	 * is not reported — {@link TypeMapper#accommodates} is the question being asked, not
	 * whether the two types are equal.
	 */
	private void verifyColumns(TableSchema schema, Map<String, String> sink) {
		List<String> missing = new ArrayList<>();
		List<String> incompatible = new ArrayList<>();
		schema.columns().forEach((name, column) -> {
			String existing = sink.get(name);
			if (existing == null) {
				missing.add(name);
				return;
			}
			if (TypeMapper.accommodates(existing, column.sqlType()) || existing.equalsIgnoreCase(column.sqlType())) {
				return;
			}
			// Being widenable is not a pass: this pipeline never widens. But it is worth
			// saying so, because it turns the error into the exact migration to run.
			String remedy = TypeMapper.canWiden(existing, column.sqlType())
					? String.format("a lossless widening exists: ALTER TABLE %s ALTER COLUMN %s TYPE %s",
							schema.table().qualified(), TableId.quote(name), column.sqlType())
					: "no lossless widening exists, so the column needs a deliberate migration";
			incompatible
				.add(String.format("%s (sink '%s', source maps to '%s' — %s)", name, existing, column.sqlType(), remedy));
		});
		if (!missing.isEmpty()) {
			if (config.getUnknownColumns() == ConsumerConfig.UnknownColumns.fail) {
				throw new UnrecoverableApplyException(String.format(
						"Sink table %s is missing column(s) %s that the change events carry, and "
								+ "consumer.unknown-columns is 'fail'. Migrate the sink and restart, or use "
								+ "'skipIfNull' to keep applying while their values are null.",
						schema.table(), missing));
			}
			// Deliberately not fatal: whether this loses anything depends on the values,
			// which only ColumnProjection can see, one row at a time.
			log.warn("Sink table {} is missing column(s) {} that the change events carry. They will be left out of "
					+ "writes while their values are null, and the first non-null value will stop the pipeline. "
					+ "Migrate the sink to clear this.", schema.table(), missing);
		}
		if (!incompatible.isEmpty()) {
			throw new UnrecoverableApplyException(String.format(
					"Sink table %s cannot store the incoming values for %s. Changing a column type is a schema change, "
							+ "which this pipeline does not make — migrate the sink and restart.",
					schema.table(), incompatible));
		}
	}

	/**
	 * Checks that the sink has a unique constraint on exactly the key columns the upsert
	 * names in {@code ON CONFLICT}. Without one PostgreSQL raises {@code 42P10}, which
	 * Spring surfaces as {@code BadSqlGrammarException} — the statement looks perfectly
	 * valid, so the real cause is easy to miss. Catching it here names the actual problem.
	 */
	private void verifyConflictTarget(TableSchema schema) {
		if (!schema.hasKey()) {
			return;
		}
		Set<String> wanted = new LinkedHashSet<>(schema.keyColumns());
		if (uniqueConstraints(schema.table()).stream().anyMatch(wanted::equals)) {
			return;
		}
		throw new UnrecoverableApplyException(String
			.format("Sink table %s has no primary key or unique constraint on %s, which the upsert needs for its "
					+ "ON CONFLICT target. PostgreSQL reports this as a syntax-class error (42P10) even though "
					+ "the statement is well formed. Add a matching constraint to the sink and restart.",
					schema.table(), wanted));
	}

	/** Primary key plus every unique index, each as its ordered column set. */
	private List<Set<String>> uniqueConstraints(TableId table) {
		return jdbcTemplate.execute((ConnectionCallback<List<Set<String>>>) connection -> {
			DatabaseMetaData metaData = connection.getMetaData();
			List<Set<String>> constraints = new ArrayList<>();
			Set<String> primaryKey = new LinkedHashSet<>();
			try (ResultSet rs = metaData.getPrimaryKeys(null, table.schema(), table.table())) {
				while (rs.next()) {
					primaryKey.add(rs.getString("COLUMN_NAME"));
				}
			}
			if (!primaryKey.isEmpty()) {
				constraints.add(primaryKey);
			}
			Map<String, Set<String>> indexes = new LinkedHashMap<>();
			try (ResultSet rs = metaData.getIndexInfo(null, table.schema(), table.table(), true, false)) {
				while (rs.next()) {
					String column = rs.getString("COLUMN_NAME");
					if (column != null) {
						indexes.computeIfAbsent(rs.getString("INDEX_NAME"), ignored -> new LinkedHashSet<>())
							.add(column);
					}
				}
			}
			constraints.addAll(indexes.values());
			return constraints;
		});
	}

	/** Reads the sink's actual columns, so a restart verifies against reality. */
	private Map<String, String> sinkColumns(TableId table) {
		return jdbcTemplate.execute((ConnectionCallback<Map<String, String>>) connection -> {
			Map<String, String> columns = new LinkedHashMap<>();
			DatabaseMetaData metaData = connection.getMetaData();
			try (ResultSet rs = metaData.getColumns(null, table.schema(), table.table(), null)) {
				while (rs.next()) {
					columns.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
				}
			}
			return columns;
		});
	}

	/** Forget cached state so the next event re-reads the sink. Used after a failure. */
	void invalidate(TableId table) {
		verified.remove(table);
		knownColumns.remove(table);
	}

}
