package io.cdc.stream.apply;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.TimeZone;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMapperTests {

	@Test
	@DisplayName("a microsecond timestamp is decoded at UTC, not in the JVM's default zone")
	void microTimestampDoesNotShiftWithTheJvmZone() {
		Schema schema = SchemaBuilder.int64().name("io.debezium.time.MicroTimestamp").build();
		TimeZone original = TimeZone.getDefault();
		try {
			// The old mapper built a java.sql.Timestamp from the Instant, which
			// re-interpreted it in the local zone and shifted every timestamp written to
			// a
			// `timestamp without time zone` column.
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
			Object shifted = TypeMapper.value(schema, 1_700_000_000_123_456L);
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			Object unshifted = TypeMapper.value(schema, 1_700_000_000_123_456L);

			assertThat(shifted).isEqualTo(unshifted).isEqualTo(LocalDateTime.parse("2023-11-14T22:13:20.123456"));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void microTimeBecomesLocalTime() {
		Schema schema = SchemaBuilder.int64().name("io.debezium.time.MicroTime").build();

		assertThat(TypeMapper.value(schema, 3_661_000_000L)).isEqualTo(LocalTime.parse("01:01:01"));
	}

	@Test
	void logicalTypesMapToSinkTypes() {
		assertThat(TypeMapper.sqlType(SchemaBuilder.int64().name("io.debezium.time.MicroTimestamp").build()))
			.isEqualTo("timestamp");
		assertThat(TypeMapper.sqlType(SchemaBuilder.string().name("io.debezium.time.ZonedTimestamp").build()))
			.isEqualTo("timestamptz");
		assertThat(TypeMapper.sqlType(SchemaBuilder.bytes().name("org.apache.kafka.connect.data.Decimal").build()))
			.isEqualTo("numeric");
		assertThat(TypeMapper.sqlType(SchemaBuilder.string().name("io.debezium.data.Uuid").build())).isEqualTo("uuid");
		assertThat(TypeMapper.sqlType(Schema.OPTIONAL_INT32_SCHEMA)).isEqualTo("integer");
		assertThat(TypeMapper.sqlType(SchemaBuilder.array(Schema.INT32_SCHEMA).build())).isEqualTo("integer[]");
	}

	@Test
	@DisplayName("only lossless widenings are allowed; everything else must halt the pipeline")
	void wideningMatrix() {
		assertThat(TypeMapper.canWiden("int4", "bigint")).isTrue();
		assertThat(TypeMapper.canWiden("int2", "integer")).isTrue();
		assertThat(TypeMapper.canWiden("bigint", "numeric")).isTrue();
		assertThat(TypeMapper.canWiden("varchar", "text")).isTrue();
		// Same type under a different alias is not a change at all.
		assertThat(TypeMapper.canWiden("int8", "bigint")).isTrue();

		assertThat(TypeMapper.canWiden("bigint", "integer")).isFalse();
		assertThat(TypeMapper.canWiden("numeric", "bigint")).isFalse();
		assertThat(TypeMapper.canWiden("text", "integer")).isFalse();
		assertThat(TypeMapper.canWiden("numeric", "bigint")).isFalse();
		assertThat(TypeMapper.canWiden("timestamp", "date")).isFalse();
	}

	@Test
	@DisplayName("driver type aliases are not mistaken for type changes")
	void driverAliasesAreNotTypeChanges() {
		// bigserial is bigint plus a sequence default, and it is what DatabaseMetaData
		// reports for an identity primary key -- not an unsafe narrowing.
		assertThat(TypeMapper.canWiden("bigserial", "bigint")).isTrue();
		assertThat(TypeMapper.canWiden("serial", "integer")).isTrue();
		assertThat(TypeMapper.canWiden("smallserial", "smallint")).isTrue();
		// The driver reports arrays with a leading underscore.
		assertThat(TypeMapper.canWiden("_int4", "integer[]")).isTrue();
		assertThat(TypeMapper.canWiden("_text", "text[]")).isTrue();
		assertThat(TypeMapper.canWiden("bpchar", "text")).isTrue();
		assertThat(TypeMapper.canWiden("timestamptz", "timestamp with time zone")).isTrue();

		// Still caught: a serial alias does not license an actual narrowing.
		assertThat(TypeMapper.canWiden("bigserial", "integer")).isFalse();
		assertThat(TypeMapper.canWiden("_int8", "integer[]")).isFalse();
	}

	@Test
	@DisplayName("a sink column that is already wider needs no DDL and must not halt")
	void widerSinkColumnIsAccepted() {
		// Observed in practice: subtotal arrives as a double while the sink column is
		// numeric. numeric stores it, so this is not a type change.
		assertThat(TypeMapper.accommodates("numeric", "double precision")).isTrue();
		assertThat(TypeMapper.accommodates("numeric(10,2)", "double precision")).isTrue();
		// bigserial primary key receiving an integer-typed source column.
		assertThat(TypeMapper.accommodates("bigserial", "integer")).isTrue();
		assertThat(TypeMapper.accommodates("bigint", "smallint")).isTrue();
		assertThat(TypeMapper.accommodates("text", "character varying")).isTrue();
		assertThat(TypeMapper.accommodates("timestamptz", "timestamp")).isTrue();
		assertThat(TypeMapper.accommodates("_int8", "bigint[]")).isTrue();

		// A narrower sink cannot store the incoming type; that still needs widening.
		assertThat(TypeMapper.accommodates("integer", "bigint")).isFalse();
		assertThat(TypeMapper.canWiden("integer", "bigint")).isTrue();
		// double precision deliberately refuses bigint: above 2^53 it would lose
		// precision.
		assertThat(TypeMapper.accommodates("double precision", "bigint")).isFalse();
		// Genuinely incompatible, so the pipeline should still halt.
		assertThat(TypeMapper.accommodates("integer", "text")).isFalse();
		assertThat(TypeMapper.canWiden("integer", "text")).isFalse();
	}

	@Test
	void castsAreAddedOnlyWhereNeeded() {
		assertThat(TypeMapper.placeholder("bigint")).isEqualTo("?");
		assertThat(TypeMapper.placeholder("text")).isEqualTo("?");
		assertThat(TypeMapper.placeholder("jsonb")).isEqualTo("?::jsonb");
		assertThat(TypeMapper.placeholder("uuid")).isEqualTo("?::uuid");
		assertThat(TypeMapper.placeholder("integer[]")).isEqualTo("?::integer[]");
	}

}
