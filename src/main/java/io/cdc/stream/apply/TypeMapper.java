package io.cdc.stream.apply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Kafka Connect schemas and values onto YugabyteDB/PostgreSQL types.
 *
 * <p>
 * Temporal handling is worth calling out: Debezium encodes {@code timestamp without time
 * zone} as an epoch value that is already UTC-naive, so it is converted to
 * {@link LocalDateTime} at UTC rather than through {@link java.sql.Timestamp#from}. The
 * latter re-interprets the instant in the JVM's default zone and silently shifts every
 * timestamp by the local offset.
 */
final class TypeMapper {

	private final static Logger log = LoggerFactory.getLogger(TypeMapper.class);

	private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

	private static final String DECIMAL = "org.apache.kafka.connect.data.Decimal";

	private static final Set<String> JSON_TYPES = Set.of("jsonb", "json");

	private TypeMapper() {
	}

	/** Column type to use when creating or altering the sink table. */
	static String sqlType(Schema schema) {
		String name = schema.name();
		if (name != null) {
			String logical = byLogicalName(name, schema);
			if (logical != null) {
				return logical;
			}
		}
		return switch (schema.type()) {
			case INT8, INT16 -> "smallint";
			case INT32 -> "integer";
			case INT64 -> "bigint";
			case FLOAT32 -> "real";
			case FLOAT64 -> "double precision";
			case BOOLEAN -> "boolean";
			case STRING -> "text";
			case BYTES -> "bytea";
			case ARRAY -> sqlType(schema.valueSchema()) + "[]";
			case STRUCT, MAP -> "jsonb";
		};
	}

	private static String byLogicalName(String name, Schema schema) {
		return switch (name) {
			case DECIMAL -> "numeric";
			case "org.apache.kafka.connect.data.Date", "io.debezium.time.Date" -> "date";
			case "org.apache.kafka.connect.data.Time", "io.debezium.time.Time", "io.debezium.time.MicroTime",
					"io.debezium.time.NanoTime" ->
				"time";
			case "org.apache.kafka.connect.data.Timestamp", "io.debezium.time.Timestamp",
					"io.debezium.time.MicroTimestamp", "io.debezium.time.NanoTimestamp" ->
				"timestamp";
			case "io.debezium.time.ZonedTimestamp" -> "timestamptz";
			case "io.debezium.time.ZonedTime" -> "timetz";
			case "io.debezium.time.Interval" -> "interval";
			case "io.debezium.time.MicroDuration" -> "numeric";
			case "io.debezium.data.Json" -> "jsonb";
			case "io.debezium.data.Xml" -> "xml";
			case "io.debezium.data.Uuid" -> "uuid";
			case "io.debezium.data.Ltree" -> "ltree";
			default -> {
				if (name.startsWith("io.debezium.data.geometry.")) {
					yield "jsonb";
				}
				yield null;
			}
		};
	}

	/**
	 * Bind placeholder for the column. Types that PostgreSQL will not implicitly coerce
	 * from {@code text} get an explicit cast rather than a driver-specific object
	 * wrapper.
	 */
	static String placeholder(String sqlType) {
		String base = sqlType.toLowerCase();
		if (JSON_TYPES.contains(base) || base.equals("uuid") || base.equals("xml") || base.equals("interval")
				|| base.equals("ltree") || base.endsWith("[]")) {
			return "?::" + sqlType;
		}
		return "?";
	}

	/** Converts a Connect value into something the JDBC driver accepts. */
	static Object value(Schema schema, Object raw) {
		if (raw == null) {
			return null;
		}
		String name = schema.name();
		if (name != null) {
			switch (name) {
				case "io.debezium.time.Date":
					return LocalDate.ofEpochDay(((Number) raw).longValue());
				case "io.debezium.time.Timestamp":
					return utc(Instant.ofEpochMilli(((Number) raw).longValue()));
				case "io.debezium.time.MicroTimestamp":
					return utc(micros(((Number) raw).longValue()));
				case "io.debezium.time.NanoTimestamp":
					return utc(nanos(((Number) raw).longValue()));
				case "io.debezium.time.ZonedTimestamp":
					return OffsetDateTime.parse((String) raw);
				case "io.debezium.time.Time":
					return LocalTime.ofNanoOfDay(((Number) raw).longValue() * 1_000_000L);
				case "io.debezium.time.MicroTime":
					return LocalTime.ofNanoOfDay(((Number) raw).longValue() * 1_000L);
				case "io.debezium.time.NanoTime":
					return LocalTime.ofNanoOfDay(((Number) raw).longValue());
				case "io.debezium.data.Json", "io.debezium.data.Xml", "io.debezium.data.Uuid",
						"io.debezium.time.Interval", "io.debezium.data.Ltree":
					return raw.toString();
				default:
					break;
			}
			if (name.startsWith("io.debezium.data.geometry.") && raw instanceof Struct struct) {
				return json(struct);
			}
		}
		return byType(schema, raw);
	}

	private static Object byType(Schema schema, Object raw) {
		return switch (schema.type()) {
			case BYTES -> raw instanceof ByteBuffer buffer ? bytes(buffer) : raw;
			case ARRAY -> arrayLiteral(schema.valueSchema(), (List<?>) raw);
			case STRUCT -> json((Struct) raw);
			case MAP -> jsonString(raw);
			default -> raw;
		};
	}

	private static Object bytes(ByteBuffer buffer) {
		byte[] out = new byte[buffer.remaining()];
		buffer.duplicate().get(out);
		return out;
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant micros(long micros) {
		return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
	}

	private static Instant nanos(long nanos) {
		return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
	}

	/**
	 * Arrays are rendered as a PostgreSQL array literal and cast, so no live
	 * {@link java.sql.Connection} is needed to build a {@link java.sql.Array}.
	 */
	private static String arrayLiteral(Schema elementSchema, List<?> elements) {
		List<String> parts = new ArrayList<>(elements.size());
		for (Object element : elements) {
			if (element == null) {
				parts.add("NULL");
				continue;
			}
			Object converted = value(elementSchema, element);
			parts.add('"' + String.valueOf(converted).replace("\\", "\\\\").replace("\"", "\\\"") + '"');
		}
		return '{' + String.join(",", parts) + '}';
	}

	private static String json(Struct struct) {
		return jsonString(toMap(struct));
	}

	private static String jsonString(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (Exception e) {
			log.warn("Falling back to toString() for value of type {}", value.getClass().getName(), e);
			return String.valueOf(value);
		}
	}

	private static Map<String, Object> toMap(Struct struct) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Field field : struct.schema().fields()) {
			Object raw = struct.get(field);
			out.put(field.name(), raw instanceof Struct nested ? toMap(nested) : value(field.schema(), raw));
		}
		return out;
	}

	/**
	 * Whether the sink's existing type can already store values of the incoming type, so
	 * no DDL is needed at all.
	 *
	 * <p>
	 * Asked before {@link #canWiden}, because the two questions are different and only
	 * this one respects a sink that is deliberately wider than the source. A
	 * {@code numeric} column receiving {@code double precision} values, or a
	 * {@code bigint} key receiving {@code integer}, needs nothing done — treating those
	 * as unsafe type changes would halt the pipeline over a schema that is already
	 * correct.
	 */
	static boolean accommodates(String sinkType, String incomingType) {
		String sink = normalise(sinkType);
		String incoming = normalise(incomingType);
		if (sink.equals(incoming)) {
			return true;
		}
		if (sink.endsWith("[]") && incoming.endsWith("[]")) {
			return accommodates(sink.substring(0, sink.length() - 2), incoming.substring(0, incoming.length() - 2));
		}
		return switch (sink) {
			// numeric is exact and unbounded, so it holds every other number type.
			case "numeric" ->
				Set.of("smallint", "integer", "bigint", "real", "double precision", "numeric").contains(incoming);
			// bigint deliberately excluded: 2^53 upwards would lose precision silently.
			case "double precision" -> Set.of("smallint", "integer", "real", "double precision").contains(incoming);
			case "real" -> Set.of("smallint", "real").contains(incoming);
			case "bigint" -> Set.of("smallint", "integer", "bigint").contains(incoming);
			case "integer" -> Set.of("smallint", "integer").contains(incoming);
			case "text" -> Set.of("text", "character varying", "character").contains(incoming);
			case "character varying" -> Set.of("character varying", "character").contains(incoming);
			case "jsonb" -> Set.of("jsonb", "json").contains(incoming);
			case "timestamp with time zone" ->
				Set.of("timestamp", "timestamp with time zone", "date").contains(incoming);
			case "timestamp" -> Set.of("timestamp", "date").contains(incoming);
			default -> false;
		};
	}

	/**
	 * Whether an in-place {@code ALTER COLUMN ... TYPE} is a provably lossless widening.
	 * Anything not listed here halts the pipeline rather than risking silent truncation.
	 */
	static boolean canWiden(String from, String to) {
		String f = normalise(from);
		String t = normalise(to);
		if (f.equals(t)) {
			return true;
		}
		return switch (f) {
			case "smallint" -> Set.of("integer", "bigint", "numeric", "real", "double precision").contains(t);
			case "integer" -> Set.of("bigint", "numeric", "double precision").contains(t);
			case "bigint" -> t.equals("numeric");
			case "real" -> Set.of("double precision", "numeric").contains(t);
			case "character" -> Set.of("character varying", "text").contains(t);
			case "character varying" -> t.equals("text");
			default -> false;
		};
	}

	/**
	 * Reduces a type name to a canonical form so the sink's reported type and the type
	 * derived from the record schema can be compared.
	 *
	 * <p>
	 * Two sources of false positives this exists to absorb. The {@code serial} family is
	 * not a distinct type — {@code bigserial} is {@code bigint} plus a sequence default —
	 * but {@code DatabaseMetaData} reports it as {@code bigserial}, which would otherwise
	 * look like an unsafe type change on every primary key. And the driver reports arrays
	 * with a leading underscore ({@code _int4}) where the record schema yields
	 * {@code integer[]}.
	 */
	private static String normalise(String sqlType) {
		String base = sqlType.toLowerCase().trim();
		int paren = base.indexOf('(');
		if (paren > 0) {
			base = base.substring(0, paren).trim();
		}
		if (base.startsWith("_")) {
			return normalise(base.substring(1)) + "[]";
		}
		if (base.endsWith("[]")) {
			return normalise(base.substring(0, base.length() - 2)) + "[]";
		}
		return switch (base) {
			case "int2", "smallserial", "serial2" -> "smallint";
			case "int4", "int", "serial", "serial4" -> "integer";
			case "int8", "bigserial", "serial8" -> "bigint";
			case "float4" -> "real";
			case "float8", "double" -> "double precision";
			case "varchar" -> "character varying";
			case "bpchar", "char" -> "character";
			case "bool" -> "boolean";
			case "decimal" -> "numeric";
			case "timestamptz" -> "timestamp with time zone";
			case "timestamp without time zone" -> "timestamp";
			case "timetz" -> "time with time zone";
			case "time without time zone" -> "time";
			default -> base;
		};
	}

}
