package io.cdc.stream.apply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Builds DML from the column set actually present on a row, and caches it. The generated
 * statement is the reason DDL evolution is possible at all: a new source column simply
 * produces a new cache entry rather than requiring a code change.
 */
@Component
public class SqlGenerator {

	private final Map<String, Statement> cache = new ConcurrentHashMap<>();

	/**
	 * @param bindColumns column names in parameter order
	 */
	public record Statement(String sql, List<String> bindColumns) {
	}

	/**
	 * Last-writer-wins guard: apply only when the sink row is older than the incoming
	 * change, compared on a column both already carry.
	 *
	 * @param column the sink's version column — see {@code SinkPrecondition.conflictColumnOf}
	 * @param strict how an equal version is settled, which is the whole of the tiebreak.
	 * {@code true} compares {@code <} and rejects a tie; {@code false} compares {@code <=}
	 * and accepts it. Both deployments in a pair derive this from the same rule and reach
	 * opposite answers, so exactly one accepts a given tie and the two converge — see
	 * {@code ConsumerConfig.incomingWinsTies}. Leaving ties to one setting on both sides
	 * diverges either way round: reject-both keeps two different values, accept-both swaps
	 * them.
	 */
	public record Guard(String column, boolean strict) {

		String comparison() {
			return strict ? " < " : " <= ";
		}

	}

	/**
	 * Full-image write: insert, falling back to an update of the non-key columns on
	 * conflict.
	 */
	public Statement upsert(TableSchema schema, Collection<String> columns) {
		return upsert(schema, columns, null);
	}

	public Statement upsert(TableSchema schema, Collection<String> columns, Guard guard) {
		Guard effective = usable(guard, columns);
		return cache.computeIfAbsent(key("upsert", schema, columns, effective), ignored -> {
			List<String> insertColumns = new ArrayList<>(columns);
			StringBuilder sql = new StringBuilder("INSERT INTO ").append(schema.table().qualified()).append(" (");
			sql.append(joinQuoted(insertColumns)).append(") VALUES (");
			for (int i = 0; i < insertColumns.size(); i++) {
				sql.append(i == 0 ? "" : ", ").append(schema.column(insertColumns.get(i)).placeholder());
			}
			sql.append(") ON CONFLICT (").append(joinQuoted(schema.keyColumns())).append(") ");

			List<String> updatable = insertColumns.stream()
				.filter(column -> !schema.keyColumns().contains(column))
				.toList();
			if (updatable.isEmpty()) {
				sql.append("DO NOTHING");
			}
			else {
				sql.append("DO UPDATE SET ");
				for (int i = 0; i < updatable.size(); i++) {
					String column = TableId.quote(updatable.get(i));
					sql.append(i == 0 ? "" : ", ").append(column).append(" = EXCLUDED.").append(column);
				}
				if (effective != null) {
					// The target row is addressed by the table's own name, which PostgreSQL
					// makes available as the implicit alias in ON CONFLICT. No extra bind:
					// the incoming value is already among the inserted columns.
					String column = TableId.quote(effective.column());
					sql.append(" WHERE ")
						.append(TableId.quote(schema.table().table()))
						.append('.')
						.append(column)
						.append(effective.comparison())
						.append("EXCLUDED.")
						.append(column);

					// Nothing reads these rows. RETURNING is here because it makes the
					// statement ineligible for reWriteBatchedInserts, and a batch that is not
					// rewritten reports real per-row affected counts instead of
					// SUCCESS_NO_INFO. Those counts are the only trustworthy signal of which
					// changes the guard refused: once the batch has run, an applied row and a
					// row that lost a tie both leave the sink holding the incoming version,
					// so no later query can tell them apart. Guarded writes would otherwise
					// have to run one statement at a time to stay honest.
					//
					// Safe to batch a statement that returns rows: the driver discards result
					// rows in executeBatch unless generated keys were asked for. It is NOT
					// safe under executeUpdate, which rejects them outright — see
					// ChangeEventApplier.execute.
					sql.append(" RETURNING ").append(joinQuoted(schema.keyColumns()));
				}
			}
			return new Statement(sql.toString(), insertColumns);
		});
	}

	/**
	 * Partial-image write. Used when {@code REPLICA IDENTITY CHANGE} means the event only
	 * carries the columns that actually changed — an insert would fail on the columns it
	 * cannot supply, so the existing row is updated in place instead.
	 * @return {@code null} when the event changes nothing outside the key
	 */
	public Statement update(TableSchema schema, Collection<String> columns) {
		return update(schema, columns, null);
	}

	public Statement update(TableSchema schema, Collection<String> columns, Guard guard) {
		List<String> assignable = columns.stream().filter(column -> !schema.keyColumns().contains(column)).toList();
		if (assignable.isEmpty()) {
			return null;
		}
		Guard effective = usable(guard, columns);
		return cache.computeIfAbsent(key("update", schema, columns, effective), ignored -> {
			StringBuilder sql = new StringBuilder("UPDATE ").append(schema.table().qualified()).append(" SET ");
			for (int i = 0; i < assignable.size(); i++) {
				String column = assignable.get(i);
				sql.append(i == 0 ? "" : ", ")
					.append(TableId.quote(column))
					.append(" = ")
					.append(schema.column(column).placeholder());
			}
			sql.append(where(schema));
			List<String> binds = new ArrayList<>(assignable);
			binds.addAll(schema.keyColumns());
			if (effective != null) {
				sql.append(" AND ")
					.append(TableId.quote(effective.column()))
					.append(effective.comparison())
					.append(schema.column(effective.column()).placeholder());
				binds.add(effective.column());
			}
			return new Statement(sql.toString(), binds);
		});
	}

	public Statement delete(TableSchema schema) {
		return delete(schema, null, null);
	}

	/**
	 * @param available the columns the before image actually carries, since the guard value
	 * for a delete comes from there
	 */
	public Statement delete(TableSchema schema, Collection<String> available, Guard guard) {
		Guard effective = available == null ? null : usable(guard, available);
		return cache.computeIfAbsent(key("delete", schema, schema.keyColumns(), effective), ignored -> {
			StringBuilder sql = new StringBuilder("DELETE FROM ").append(schema.table().qualified()).append(where(schema));
			List<String> binds = new ArrayList<>(schema.keyColumns());
			if (effective != null) {
				// Never strict: delete when the sink is no newer than the version the
				// source saw when it deleted. A sink row that has moved on since then holds
				// changes the deleter did not know about, so the delete loses.
				sql.append(" AND ")
					.append(TableId.quote(effective.column()))
					.append(" <= ")
					.append(schema.column(effective.column()).placeholder());
				binds.add(effective.column());
			}
			return new Statement(sql.toString(), binds);
		});
	}

	/**
	 * Drops the guard when the event does not carry the version column — a partial image
	 * under {@code REPLICA IDENTITY CHANGE} may not include it, and there is then nothing to
	 * compare. The caller is told, so the fallback is visible rather than silent.
	 */
	private static Guard usable(Guard guard, Collection<String> columns) {
		return guard != null && columns.contains(guard.column()) ? guard : null;
	}

	public String truncate(TableId table) {
		return "TRUNCATE TABLE " + table.qualified();
	}

	private String where(TableSchema schema) {
		StringBuilder sql = new StringBuilder(" WHERE ");
		List<String> keys = schema.keyColumns();
		for (int i = 0; i < keys.size(); i++) {
			sql.append(i == 0 ? "" : " AND ")
				.append(TableId.quote(keys.get(i)))
				.append(" = ")
				.append(schema.column(keys.get(i)).placeholder());
		}
		return sql.toString();
	}

	private static String joinQuoted(Collection<String> columns) {
		return String.join(", ", columns.stream().map(TableId::quote).toList());
	}

	private static String key(String op, TableSchema schema, Collection<String> columns, Guard guard) {
		return op + '|' + schema.table() + '|' + schema.fingerprint() + '|' + String.join(",", columns) + '|'
				+ (guard == null ? "" : guard.column() + (guard.strict() ? "<" : "<="));
	}

}
