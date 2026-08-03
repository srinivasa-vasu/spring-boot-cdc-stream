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
	 * Full-image write: insert, falling back to an update of the non-key columns on
	 * conflict.
	 */
	public Statement upsert(TableSchema schema, Collection<String> columns) {
		return cache.computeIfAbsent(key("upsert", schema, columns), ignored -> {
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
		List<String> assignable = columns.stream().filter(column -> !schema.keyColumns().contains(column)).toList();
		if (assignable.isEmpty()) {
			return null;
		}
		return cache.computeIfAbsent(key("update", schema, columns), ignored -> {
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
			return new Statement(sql.toString(), binds);
		});
	}

	public Statement delete(TableSchema schema) {
		return cache.computeIfAbsent(key("delete", schema, schema.keyColumns()),
				ignored -> new Statement("DELETE FROM " + schema.table().qualified() + where(schema),
						new ArrayList<>(schema.keyColumns())));
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

	private static String key(String op, TableSchema schema, Collection<String> columns) {
		return op + '|' + schema.table() + '|' + schema.fingerprint() + '|' + String.join(",", columns);
	}

}
