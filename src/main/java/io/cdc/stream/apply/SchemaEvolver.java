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
 * Propagates source DDL to the sink.
 *
 * <p>
 * PostgreSQL and YugabyteDB logical decoding emit no DDL events — there is no DDL stream
 * to subscribe to, unlike MySQL or Oracle. What every change event does carry is its
 * Connect schema, and that schema lists all columns of the table regardless of which ones
 * the row actually changed. A change in the derived fingerprint is therefore a reliable
 * signal that the source table was altered, and diffing it against the sink's real
 * columns yields the DDL to apply.
 *
 * <p>
 * Only additive and provably lossless changes are automated. A narrowing or otherwise
 * incompatible type change halts the pipeline: there is no safe automatic answer, and
 * halting loudly beats truncating silently. Column drops are not propagated by default
 * because they cannot be undone.
 */
@Component
public class SchemaEvolver {

	private final static Logger log = LoggerFactory.getLogger(SchemaEvolver.class);

	private final JdbcTemplate jdbcTemplate;

	private final ConsumerConfig config;

	/** Fingerprint of the last schema reconciled per table, to skip the common case. */
	private final Map<TableId, String> reconciled = new ConcurrentHashMap<>();

	/**
	 * Uses the metadata pool, not the apply pool. DDL auto-commits so it can never be
	 * part of the row transaction, and metadata reads are chatty enough that queueing
	 * them behind the single tagged apply connection would stall applies for no benefit.
	 */
	public SchemaEvolver(@Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate, ConsumerConfig config) {
		this.jdbcTemplate = jdbcTemplate;
		this.config = config;
	}

	/**
	 * Reconciles the sink table with the incoming schema. Idempotent, and cheap after the
	 * first call for a given schema fingerprint.
	 *
	 * <p>
	 * DDL auto-commits in PostgreSQL/YugabyteDB, so this runs before — never inside — the
	 * row transaction. Every statement is written to be safely re-runnable so a retry
	 * after a partial failure converges.
	 */
	public void reconcile(TableSchema schema) {
		if (schema == null || schema.fingerprint().equals(reconciled.get(schema.table()))) {
			return;
		}
		if (config.getSchemaEvolution() == ConsumerConfig.SchemaEvolution.none) {
			reconciled.put(schema.table(), schema.fingerprint());
			return;
		}
		Map<String, String> sink = sinkColumns(schema.table());
		if (sink.isEmpty()) {
			createTable(schema);
		}
		else {
			alterTable(schema, sink);
			verifyConflictTarget(schema);
		}
		reconciled.put(schema.table(), schema.fingerprint());
	}

	/**
	 * Checks that the sink has a unique constraint on exactly the key columns the upsert
	 * names in {@code ON CONFLICT}. Without one PostgreSQL raises {@code 42P10}, which
	 * Spring surfaces as {@code BadSqlGrammarException} — the statement looks perfectly
	 * valid, so the real cause is easy to miss. Catching it here names the actual
	 * problem.
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
					+ "the statement is well formed. Add a matching constraint, or drop the table and let "
					+ "consumer.auto-create-tables recreate it.", schema.table(), wanted));
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

	private void createTable(TableSchema schema) {
		if (!config.isAutoCreateTables()) {
			throw new UnrecoverableApplyException("Sink table " + schema.table()
					+ " does not exist and consumer.auto-create-tables is disabled. Create it manually or enable the flag.");
		}
		if (!schema.hasKey()) {
			throw new UnrecoverableApplyException("Cannot create sink table " + schema.table()
					+ ": the change event carries no key, so no primary key can be derived. "
					+ "Add a primary key to the source table.");
		}
		List<String> definitions = new ArrayList<>();
		schema.keyFirst()
			.forEach((name, column) -> definitions.add(TableId.quote(name) + ' ' + column.sqlType()
					+ (schema.keyColumns().contains(name) ? " NOT NULL" : "")));
		definitions
			.add("PRIMARY KEY (" + String.join(", ", schema.keyColumns().stream().map(TableId::quote).toList()) + ')');

		String ddl = "CREATE TABLE IF NOT EXISTS " + schema.table().qualified() + " (" + String.join(", ", definitions)
				+ ')';
		log.info("Creating sink table {}: {}", schema.table(), ddl);
		jdbcTemplate.execute(ddl);
	}

	private void alterTable(TableSchema schema, Map<String, String> sink) {
		List<String> statements = new ArrayList<>();
		schema.columns().forEach((name, column) -> {
			String existing = sink.get(name);
			if (existing == null) {
				// New source column. Must be nullable: rows already in the sink have no
				// value for it and there is no backfill available from the stream.
				statements.add("ALTER TABLE " + schema.table().qualified() + " ADD COLUMN IF NOT EXISTS "
						+ TableId.quote(name) + ' ' + column.sqlType());
			}
			else if (TypeMapper.accommodates(existing, column.sqlType())) {
				// The sink column is already at least as wide as the incoming values.
				// Leave
				// the schema alone -- a deliberately wider sink type is not a type
				// change.
				log.debug("{}.{}: sink type '{}' already stores '{}'; no DDL needed", schema.table(), name, existing,
						column.sqlType());
			}
			else if (!TypeMapper.canWiden(existing, column.sqlType())) {
				throw new UnrecoverableApplyException(String.format(
						"Unsafe type change on %s.%s: sink is '%s', source now maps to '%s'. "
								+ "The sink type cannot store these values and widening it would not be lossless, "
								+ "so it is not applied automatically. Migrate the sink column manually and restart.",
						schema.table(), name, existing, column.sqlType()));
			}
			else if (!existing.equalsIgnoreCase(column.sqlType())) {
				statements.add("ALTER TABLE " + schema.table().qualified() + " ALTER COLUMN " + TableId.quote(name)
						+ " TYPE " + column.sqlType());
			}
		});

		sink.keySet().stream().filter(name -> !schema.columns().containsKey(name)).forEach(name -> {
			if (config.isAllowColumnDrop()) {
				statements
					.add("ALTER TABLE " + schema.table().qualified() + " DROP COLUMN IF EXISTS " + TableId.quote(name));
			}
			else {
				log.warn(
						"Column {}.{} is in the sink but no longer in the source. Leaving it in place and dropping "
								+ "its NOT NULL constraint; set consumer.allow-column-drop to propagate drops.",
						schema.table(), name);
				statements.add("ALTER TABLE " + schema.table().qualified() + " ALTER COLUMN " + TableId.quote(name)
						+ " DROP NOT NULL");
			}
		});

		if (statements.isEmpty()) {
			return;
		}
		log.info("Propagating {} schema change(s) to {}", statements.size(), schema.table());
		statements.forEach(ddl -> {
			log.info("  {}", ddl);
			jdbcTemplate.execute(ddl);
		});
	}

	/**
	 * Reads the sink's actual columns rather than trusting an in-memory cache, so a
	 * restart reconciles against reality.
	 */
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
		reconciled.remove(table);
	}

}
