package io.cdc.stream.apply;

import io.cdc.stream.event.OPERATION;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code yboutput} column envelope, where every column arrives as
 * {@code {value, set}}. Distinguishing "not part of this change" from "set to NULL" is
 * the whole point of the {@code set} flag.
 */
class RecordConverterTests {

	private final RecordConverter converter = new RecordConverter();

	@Test
	@DisplayName("a partial update carries only the columns whose set flag is true")
	void partialUpdateKeepsOnlyChangedColumns() {
		SourceRecord record = update(builder -> builder.put("id", wrapped(schema("id"), 7L, true))
			.put("quantity", wrapped(schema("quantity"), 3, true))
			.put("note", wrapped(schema("note"), "ignored", false)));

		ChangeRow row = converter.convert(record);

		assertThat(row).isNotNull();
		assertThat(row.op()).isEqualTo(OPERATION.u);
		assertThat(row.values()).containsOnlyKeys("id", "quantity");
		assertThat(row.values()).containsEntry("quantity", 3);
		assertThat(row.fullImage()).isFalse();
	}

	@Test
	@DisplayName("set=true with a null value is an explicit SET NULL, not an absent column")
	void explicitNullIsPreserved() {
		SourceRecord record = update(builder -> builder.put("id", wrapped(schema("id"), 7L, true))
			.put("quantity", wrapped(schema("quantity"), 3, true))
			.put("note", wrapped(schema("note"), null, true)));

		ChangeRow row = converter.convert(record);

		assertThat(row.values()).containsOnlyKeys("id", "quantity", "note");
		assertThat(row.values()).containsEntry("note", null);
		// Every column present, so the row can be upserted rather than updated in place.
		assertThat(row.fullImage()).isTrue();
	}

	@Test
	@DisplayName("the table is schema-qualified so same-named tables in other schemas cannot collide")
	void tableIsSchemaQualified() {
		ChangeRow row = converter.convert(update(builder -> builder.put("id", wrapped(schema("id"), 7L, true))));

		assertThat(row.table()).isEqualTo(new TableId("public", "orders"));
		assertThat(row.table().qualified()).isEqualTo("\"public\".\"orders\"");
	}

	@Test
	@DisplayName("the primary key is taken from the record key")
	void keyColumnsComeFromTheRecordKey() {
		ChangeRow row = converter.convert(update(builder -> builder.put("id", wrapped(schema("id"), 7L, true))));

		assertThat(row.schema().keyColumns()).containsExactly("id");
	}

	@Test
	@DisplayName("the replication origin is carried through so loops can be broken")
	void originIsExposed() {
		ChangeRow replayed = converter
			.convert(update(builder -> builder.put("id", wrapped(schema("id"), 7L, true)), "cdc_apply"));
		ChangeRow local = converter.convert(update(builder -> builder.put("id", wrapped(schema("id"), 7L, true))));

		assertThat(replayed.origin()).isEqualTo("cdc_apply");
		// An ordinary local write carries no origin, so it is never filtered. This null
		// vs
		// non-null distinction is the whole basis of bidirectional loop prevention: a
		// user
		// write is forwarded, a replication apply is not.
		assertThat(local.origin()).isNull();
	}

	@Test
	@DisplayName("transaction markers are recognised and carry no row")
	void transactionMarkersAreNotRows() {
		Schema markerSchema = SchemaBuilder.struct()
			.field("status", Schema.STRING_SCHEMA)
			.field("id", Schema.STRING_SCHEMA)
			.field("event_count", Schema.OPTIONAL_INT64_SCHEMA)
			.build();
		Struct end = new Struct(markerSchema).put("status", "END").put("id", "1234:5678").put("event_count", 3L);
		SourceRecord record = new SourceRecord(null, null, "ybdb.transaction", null, null, markerSchema, end);

		assertThat(converter.isTransactionMarker(record)).isTrue();
		assertThat(converter.isTransactionEnd(record)).isTrue();
		assertThat(converter.markerStatus(record)).isEqualTo("END");
		assertThat(converter.markerTransactionId(record)).isEqualTo("1234:5678");
		// Declared count is what makes an incomplete transaction detectable.
		assertThat(converter.markerEventCount(record)).isEqualTo(3L);
		assertThat(converter.convert(record)).isNull();

		Struct begin = new Struct(markerSchema).put("status", "BEGIN").put("id", "1234:5678");
		SourceRecord beginRecord = new SourceRecord(null, null, "ybdb.transaction", null, null, markerSchema, begin);

		assertThat(converter.isTransactionMarker(beginRecord)).isTrue();
		assertThat(converter.isTransactionEnd(beginRecord)).isFalse();
		assertThat(converter.markerStatus(beginRecord)).isEqualTo("BEGIN");
		assertThat(converter.markerEventCount(beginRecord)).isNull();
	}

	// --- fixtures -----------------------------------------------------------

	private static final Schema ID = Schema.OPTIONAL_INT64_SCHEMA;

	private static final Schema QUANTITY = Schema.OPTIONAL_INT32_SCHEMA;

	private static final Schema NOTE = Schema.OPTIONAL_STRING_SCHEMA;

	private static Schema schema(String column) {
		return switch (column) {
			case "id" -> ID;
			case "quantity" -> QUANTITY;
			default -> NOTE;
		};
	}

	/** {@code {value, set}} envelope as emitted by the yboutput plugin. */
	private static Schema envelope(Schema inner) {
		return SchemaBuilder.struct().field("value", inner).field("set", Schema.BOOLEAN_SCHEMA).optional().build();
	}

	private static Struct wrapped(Schema inner, Object value, boolean set) {
		return new Struct(envelope(inner)).put("value", value).put("set", set);
	}

	private static final Schema AFTER = SchemaBuilder.struct()
		.name("ybdb.public.orders.Value")
		.field("id", envelope(ID))
		.field("quantity", envelope(QUANTITY))
		.field("note", envelope(NOTE))
		.optional()
		.build();

	private static final Schema SOURCE = SchemaBuilder.struct()
		.field("schema", Schema.OPTIONAL_STRING_SCHEMA)
		.field("table", Schema.OPTIONAL_STRING_SCHEMA)
		.field("txId", Schema.OPTIONAL_INT64_SCHEMA)
		.field("lsn", Schema.OPTIONAL_INT64_SCHEMA)
		// Matches PostgresSourceInfoStructMaker: optional string / optional int64.
		.field("origin", Schema.OPTIONAL_STRING_SCHEMA)
		.field("origin_lsn", Schema.OPTIONAL_INT64_SCHEMA)
		.build();

	private static final Schema ENVELOPE = SchemaBuilder.struct()
		.name("ybdb.public.orders.Envelope")
		.field("op", Schema.STRING_SCHEMA)
		.field("before", AFTER)
		.field("after", AFTER)
		.field("source", SOURCE)
		.build();

	private static final Schema KEY = SchemaBuilder.struct().field("id", envelope(ID)).build();

	private interface AfterBuilder {

		void accept(Struct after);

	}

	private static SourceRecord update(AfterBuilder customiser) {
		return update(customiser, null);
	}

	private static SourceRecord update(AfterBuilder customiser, String origin) {
		Struct after = new Struct(AFTER);
		customiser.accept(after);
		Struct source = new Struct(SOURCE).put("schema", "public")
			.put("table", "orders")
			.put("txId", 1234L)
			.put("lsn", 5678L)
			.put("origin", origin);
		Struct value = new Struct(ENVELOPE).put("op", "u").put("after", after).put("source", source);
		Struct key = new Struct(KEY).put("id", wrapped(ID, 7L, true));
		return new SourceRecord(null, null, "ybdb.public.orders", KEY, key, ENVELOPE, value);
	}

}
