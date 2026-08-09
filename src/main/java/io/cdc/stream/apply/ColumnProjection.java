package io.cdc.stream.apply;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Narrows a change event to the columns the sink actually has.
 *
 * <p>
 * A source column the sink lacks cannot be written — {@code SqlGenerator} builds statements
 * from the event's own column set, so leaving it in yields {@code 42703 column does not
 * exist} on the first batch. The fact is not whether to drop it but whether
 * dropping it loses anything, and that is a property of the <em>value</em>, not of the
 * schema: a column absent from the sink is harmless while its values are null and is data
 * loss the moment one is not.
 *
 * <p>
 * That distinction is why this is decided per row. {@code SinkPrecondition} runs once per
 * schema shape and cannot see values, so on its own it could only assume the worst and halt
 * the whole pipeline over a column that might never carry anything.
 *
 * <p>
 * Dropping a null is silent; dropping a non-null is fatal. Nothing is ever discarded
 * quietly, which is the same rule {@code RecordConverter} follows in preserving the
 * distinction between a column that was absent and one explicitly set to NULL.
 */
final class ColumnProjection {

	private ColumnProjection() {
	}

	/**
	 * @param sinkColumns the sink's real columns, or null when they are unknown — a
	 * truncate carries no schema, so there is nothing to project
	 * @return the row unchanged when every column exists in the sink, which is the common
	 * case and allocates nothing
	 * @throws UnrecoverableApplyException if a column the sink lacks carries a value, or if
	 * a key column is missing from the sink
	 */
	static ChangeRow project(ChangeRow row, Set<String> sinkColumns) {
		if (sinkColumns == null || row.schema() == null || sinkColumns.containsAll(row.values().keySet())) {
			return row;
		}

		Map<String, Object> kept = new LinkedHashMap<>(row.values().size());
		row.values().forEach((column, value) -> {
			if (sinkColumns.contains(column)) {
				kept.put(column, value);
				return;
			}
			if (row.schema().keyColumns().contains(column)) {
				throw new UnrecoverableApplyException(String.format(
						"Sink table %s has no column '%s', but it is part of the primary key — there is no way to "
								+ "target a row without it. This is a schema mismatch, not a missing column: migrate "
								+ "the sink.",
						row.table(), column));
			}
			if (value != null) {
				throw new UnrecoverableApplyException(String.format(
						"Sink table %s has no column '%s' and a %s event carries a non-null value for it (key %s). "
								+ "Dropping it would lose data. Add the column to the sink, or set "
								+ "consumer.unknown-columns=fail to stop before any such row arrives.",
						row.table(), column, row.op(), keyOf(row)));
			}
			// Null and not part of the key: nothing is lost by leaving it out.
		});
		return new ChangeRow(row.schema(), row.op(), kept, row.txId(), row.lsn(), row.origin(), row.fullImage());
	}

	private static Map<String, Object> keyOf(ChangeRow row) {
		Map<String, Object> key = new LinkedHashMap<>();
		row.schema().keyColumns().forEach(column -> key.put(column, row.values().get(column)));
		return key;
	}

}
