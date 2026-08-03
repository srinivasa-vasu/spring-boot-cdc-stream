package io.cdc.stream.apply;

import io.cdc.stream.event.OPERATION;
import java.util.Map;

/**
 * One row change, already decoded into column values.
 *
 * @param values only the columns actually carried by this event. With
 * {@code REPLICA IDENTITY CHANGE} an update carries just the changed columns, so
 * {@code values} is authoritative about what to write — a column absent here was
 * <em>not</em> modified, whereas a column present with a {@code null} value was
 * explicitly set to NULL. Conflating those two is what the previous
 * {@code COALESCE(EXCLUDED.col, target.col)} upsert did, which made an
 * {@code UPDATE ... SET col = NULL} impossible to replicate.
 * @param origin the replication origin name from {@code source.origin}, set when the
 * source transaction was itself replayed from somewhere else rather than written
 * directly. Null for an ordinary local write. Used to break replication loops — see
 * {@code consumer.ignore-origins}. The origin is a property of the transaction, not the
 * row, so filtering on it can never split a transaction.
 * @param fullImage true when every known column is present, so the row can be upserted
 * rather than updated in place.
 */
public record ChangeRow(TableSchema schema, OPERATION op, Map<String, Object> values, String txId, Long lsn,
		String origin, boolean fullImage) {

	public TableId table() {
		return schema.table();
	}

	public boolean isDelete() {
		return op == OPERATION.d;
	}

	public boolean isTruncate() {
		return op == OPERATION.t;
	}

}
