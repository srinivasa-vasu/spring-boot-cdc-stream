package io.cdc.stream.apply;

import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;

/**
 * The shape of one source table as described by the change event itself. Derived per
 * distinct Connect schema and cached — a change in the derived fingerprint is exactly the
 * signal that source DDL happened, since PostgreSQL/YugabyteDB logical decoding emits no
 * DDL events of its own.
 */
public final class TableSchema {

	private final TableId table;

	private final Map<String, Column> columns;

	private final List<String> keyColumns;

	private final String fingerprint;

	TableSchema(TableId table, Map<String, Column> columns, List<String> keyColumns) {
		this.table = table;
		this.columns = columns;
		this.keyColumns = keyColumns;
		this.fingerprint = columns.values()
			.stream()
			.map(column -> column.name() + ':' + column.sqlType())
			.reduce((left, right) -> left + ',' + right)
			.orElse("");
	}

	public record Column(String name, Schema connectSchema, String sqlType, String placeholder) {
	}

	public TableId table() {
		return table;
	}

	public List<String> keyColumns() {
		return keyColumns;
	}

	public Map<String, Column> columns() {
		return columns;
	}

	public Column column(String name) {
		return columns.get(name);
	}

	public String fingerprint() {
		return fingerprint;
	}

	public boolean hasKey() {
		return !keyColumns.isEmpty();
	}

}
