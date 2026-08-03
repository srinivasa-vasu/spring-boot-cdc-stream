package io.cdc.stream.apply;

/**
 * Schema-qualified table identity. Qualification matters: the previous handler registry
 * keyed on the bare table name, so {@code public.orders} and {@code audit.orders} would
 * have collided.
 */
public record TableId(String schema, String table) {

	public static String quote(String identifier) {
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

	public String qualified() {
		return schema == null || schema.isBlank() ? quote(table) : quote(schema) + '.' + quote(table);
	}

	@Override
	public String toString() {
		return schema == null || schema.isBlank() ? table : schema + '.' + table;
	}

}
