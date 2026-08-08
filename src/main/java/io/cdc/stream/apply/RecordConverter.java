package io.cdc.stream.apply;

import io.cdc.stream.event.OPERATION;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Turns a Debezium change event into a {@link ChangeRow}.
 *
 * <p>
 * Typed on {@link ConnectRecord}, not {@code SourceRecord}: only {@code value()},
 * {@code key()}, {@code keySchema()} and {@code topic()} are ever read, and all four are
 * declared there. That is what lets the same converter serve the embedded engine and a
 * {@code SinkRecord} deserialized from a Kafka topic.
 *
 * <p>
 * Handles the {@code yboutput} column envelope, where each column arrives as
 * {@code {value: <T>, set: <bool>}}. The {@code set} flag is the only way to distinguish
 * "this column was not part of the change" from "this column was set to NULL", so it is
 * preserved rather than discarded. Plain {@code pgoutput} records, which carry bare
 * values, are handled by the same code path.
 */
@Component
public class RecordConverter {

	private final static Logger log = LoggerFactory.getLogger(RecordConverter.class);

	private static final String OP = "op";

	private static final String BEFORE = "before";

	private static final String AFTER = "after";

	private static final String SOURCE = "source";

	/**
	 * Replication origin name, populated from the logical decoding ORIGIN message.
	 * Present on transactions that were replayed from another origin rather than written
	 * directly.
	 */
	private static final String ORIGIN = "origin";

	private static final String TRANSACTION = "transaction";

	public static final String BEGIN = "BEGIN";

	public static final String END = "END";

	private static final String VALUE = "value";

	private static final String SET = "set";

	private final Map<String, TableSchema> schemaCache = new ConcurrentHashMap<>();

	/**
	 * @return the decoded row, or {@code null} when the record carries no row to apply
	 * (heartbeat, transaction marker, logical decoding message, unsupported operation).
	 */
	public ChangeRow convert(ConnectRecord<?> record) {
		if (!(record.value() instanceof Struct envelope)) {
			return null;
		}
		if (envelope.schema().field(OP) == null || envelope.schema().field(SOURCE) == null) {
			return null;
		}
		OPERATION op = operation(envelope.getString(OP));
		if (op == null || op == OPERATION.m) {
			return null;
		}
		Struct source = envelope.getStruct(SOURCE);
		TableId table = new TableId(text(source, "schema"), text(source, "table"));
		if (table.table() == null) {
			log.warn("Change event without a table name on topic {}; skipping", record.topic());
			return null;
		}

		Struct image = op == OPERATION.d ? struct(envelope, BEFORE) : struct(envelope, AFTER);
		if (image == null && op != OPERATION.t) {
			log.warn("{} event for {} carried no {} image; check REPLICA IDENTITY", op, table,
					op == OPERATION.d ? BEFORE : AFTER);
			return null;
		}

		TableSchema schema = image == null ? null : tableSchema(table, image.schema(), record.keySchema());
		Map<String, Object> values = new LinkedHashMap<>();
		boolean fullImage = true;
		if (image != null) {
			fullImage = decode(image, schema, values);
			mergeKey(record, schema, values);
		}
		return new ChangeRow(schema, op, values, transactionId(envelope, source), number(source, "lsn"),
				text(source, ORIGIN), fullImage);
	}

	/** True when the record is a transaction BEGIN/END marker. */
	public boolean isTransactionMarker(ConnectRecord<?> record) {
		return record.value() instanceof Struct value && value.schema().field("status") != null
				&& value.schema().field(SOURCE) == null;
	}

	/** True for a transaction END marker, which closes a transaction boundary. */
	public boolean isTransactionEnd(ConnectRecord<?> record) {
		return isTransactionMarker(record) && END.equals(markerStatus(record));
	}

	/** {@code BEGIN} or {@code END}. Anything else is a marker shape we do not know. */
	public String markerStatus(ConnectRecord<?> record) {
		return ((Struct) record.value()).getString("status");
	}

	/**
	 * The raw marker id, which Debezium formats as {@code <txId>:<lsn>}. The LSN differs
	 * between the BEGIN and END of the same transaction — BEGIN carries the begin LSN,
	 * END the commit LSN — so two ids for one transaction are not equal and must not be
	 * compared directly.
	 */
	public String markerTransactionId(ConnectRecord<?> record) {
		return ((Struct) record.value()).getString("id");
	}

	/**
	 * The transaction id with the LSN suffix removed, which is stable across BEGIN and
	 * END.
	 */
	public String markerBaseTransactionId(ConnectRecord<?> record) {
		return baseTransactionId(markerTransactionId(record));
	}

	/**
	 * The commit LSN, taken from the END marker's id. This is the correct watermark for a
	 * transaction — an individual row's {@code source.lsn} is its own position, not the
	 * transaction's commit point.
	 */
	public Long markerCommitLsn(ConnectRecord<?> record) {
		String id = markerTransactionId(record);
		int separator = id == null ? -1 : id.indexOf(':');
		if (separator < 0) {
			return null;
		}
		try {
			return Long.parseLong(id.substring(separator + 1).trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	static String baseTransactionId(String id) {
		if (id == null) {
			return null;
		}
		int separator = id.indexOf(':');
		return separator < 0 ? id : id.substring(0, separator);
	}

	/**
	 * Number of change events the source says this transaction contained, present on END.
	 * Comparing it against what actually arrived is the only way to notice that a
	 * transaction was delivered incomplete.
	 */
	public Long markerEventCount(ConnectRecord<?> record) {
		return number((Struct) record.value(), "event_count");
	}

	/**
	 * Decodes every present column into {@code values}.
	 * @return whether the image covers all known columns
	 */
	private boolean decode(Struct image, TableSchema schema, Map<String, Object> values) {
		int present = 0;
		for (Field field : image.schema().fields()) {
			TableSchema.Column column = schema.column(field.name());
			if (column == null) {
				continue;
			}
			Object raw = image.get(field);
			if (isEnvelope(field.schema())) {
				if (raw == null) {
					continue;
				}
				Struct wrapper = (Struct) raw;
				if (wrapper.schema().field(SET) != null && Boolean.FALSE.equals(wrapper.getBoolean(SET))) {
					continue;
				}
				values.put(field.name(), TypeMapper.value(column.connectSchema(), wrapper.get(VALUE)));
			}
			else {
				values.put(field.name(), TypeMapper.value(column.connectSchema(), raw));
			}
			present++;
		}
		return present == schema.columns().size();
	}

	/**
	 * A partial update image is guaranteed to carry the replica identity columns, but
	 * fall back to the record key if a connector build ever omits them — without key
	 * values there is nothing to target.
	 */
	private void mergeKey(ConnectRecord<?> record, TableSchema schema, Map<String, Object> values) {
		if (!(record.key() instanceof Struct key) || values.keySet().containsAll(schema.keyColumns())) {
			return;
		}
		for (Field field : key.schema().fields()) {
			TableSchema.Column column = schema.column(field.name());
			if (column == null || values.containsKey(field.name())) {
				continue;
			}
			Object raw = key.get(field);
			if (isEnvelope(field.schema()) && raw instanceof Struct wrapper) {
				raw = wrapper.get(VALUE);
			}
			values.put(field.name(), TypeMapper.value(column.connectSchema(), raw));
		}
	}

	private TableSchema tableSchema(TableId table, Schema imageSchema, Schema keySchema) {
		String cacheKey = table + "|" + describe(imageSchema);
		return schemaCache.computeIfAbsent(cacheKey, ignored -> build(table, imageSchema, keySchema));
	}

	private TableSchema build(TableId table, Schema imageSchema, Schema keySchema) {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		for (Field field : imageSchema.fields()) {
			Schema fieldSchema = unwrap(field.schema());
			String sqlType = TypeMapper.sqlType(fieldSchema);
			columns.put(field.name(),
					new TableSchema.Column(field.name(), fieldSchema, sqlType, TypeMapper.placeholder(sqlType)));
		}
		List<String> keyColumns = new ArrayList<>();
		if (keySchema != null) {
			keySchema.fields().stream().map(Field::name).filter(columns::containsKey).forEach(keyColumns::add);
		}
		if (keyColumns.isEmpty()) {
			log.warn("No primary key in the change event key for {}; rows cannot be upserted or deleted by key. "
					+ "Add a primary key to the source table or exclude it from table.include.list", table);
		}
		TableSchema schema = new TableSchema(table, columns, keyColumns);
		log.info("Resolved schema for {}: {} column(s), key {}", table, columns.size(), keyColumns);
		return schema;
	}

	/** Column envelope produced by the yboutput plugin: {@code {value, set}}. */
	private static boolean isEnvelope(Schema schema) {
		return schema.type() == Schema.Type.STRUCT && schema.field(VALUE) != null && schema.fields().size() <= 2
				&& (schema.fields().size() == 1 || schema.field(SET) != null);
	}

	private static Schema unwrap(Schema schema) {
		return isEnvelope(schema) ? schema.field(VALUE).schema() : schema;
	}

	private static String describe(Schema schema) {
		StringBuilder out = new StringBuilder();
		for (Field field : schema.fields()) {
			Schema fieldSchema = unwrap(field.schema());
			out.append(field.name()).append(':').append(fieldSchema.type());
			if (fieldSchema.name() != null) {
				out.append('/').append(fieldSchema.name());
			}
			out.append(';');
		}
		return out.toString();
	}

	/** Normalised to the base id so it compares equal to the BEGIN and END markers. */
	private static String transactionId(Struct envelope, Struct source) {
		if (envelope.schema().field(TRANSACTION) != null) {
			Struct transaction = envelope.getStruct(TRANSACTION);
			if (transaction != null) {
				return baseTransactionId(transaction.getString("id"));
			}
		}
		Long txId = number(source, "txId");
		return txId == null ? null : String.valueOf(txId);
	}

	private static Struct struct(Struct envelope, String field) {
		return envelope.schema().field(field) == null ? null : envelope.getStruct(field);
	}

	private static String text(Struct struct, String field) {
		return struct.schema().field(field) == null ? null : struct.getString(field);
	}

	private static Long number(Struct struct, String field) {
		if (struct.schema().field(field) == null) {
			return null;
		}
		Object value = struct.get(field);
		return value instanceof Number n ? n.longValue() : null;
	}

	private static OPERATION operation(String op) {
		if (op == null) {
			return null;
		}
		try {
			return OPERATION.valueOf(op);
		}
		catch (IllegalArgumentException e) {
			log.warn("Unsupported operation '{}'; skipping event", op);
			return null;
		}
	}

}
