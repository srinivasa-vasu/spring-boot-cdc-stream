package io.cdc.stream.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.connect.storage.Converter;

/**
 * Builds the Connect {@link Converter} that turns topic bytes back into a {@code Struct}
 * and {@code Schema}.
 *
 * <p>
 * Loaded by class name, the way Kafka Connect loads its own converters, rather than by a
 * closed enum. Two things fall out of that: the Avro converter stays off the default
 * classpath — its transitive tree is large and a JSON deployment has no use for it — and an
 * unanticipated converter can be named in configuration without a code change.
 *
 * <p>
 * <b>The format does not reach the apply side.</b> Everything below this boundary works off
 * the Connect data model, so JSON and Avro are indistinguishable to {@code RecordConverter}
 * and {@code TypeMapper}. What has to survive the round trip is the schema <em>name</em> —
 * {@code TypeMapper} dispatches on {@code io.debezium.time.MicroTimestamp} and a dozen
 * others to pick sink types. JSON carries it inline; Confluent's Avro converter round-trips
 * it through the {@code connect.name} property. Either works; schemaless JSON does not,
 * because there is then no name and no schema to read at all.
 */
public final class ConverterFactory {

	static final String JSON = "org.apache.kafka.connect.json.JsonConverter";

	static final String AVRO = "io.confluent.connect.avro.AvroConverter";

	private ConverterFactory() {
	}

	/** Resolves the {@code json} and {@code avro} shorthands; anything else is a class name. */
	static String className(String converter) {
		if (converter == null || converter.isBlank()) {
			return JSON;
		}
		return switch (converter.trim().toLowerCase()) {
			case "json" -> JSON;
			case "avro" -> AVRO;
			default -> converter.trim();
		};
	}

	/**
	 * The settings handed to {@code Converter.configure}.
	 *
	 * <p>
	 * {@code schemas.enable} is passed only to the JSON converter. It is meaningless to Avro
	 * — the schema is always present there — and Connect's config classes log unknown keys,
	 * so sending it anyway would put a permanent warning in the startup log.
	 */
	static Map<String, Object> settings(KafkaSourceConfig config, boolean isKey) {
		Map<String, Object> settings = new LinkedHashMap<>();
		String className = className(config.getConverter());
		if (JSON.equals(className)) {
			settings.put("schemas.enable", String.valueOf(config.isSchemasEnabled()));
		}
		if (config.getSchemaRegistryUrl() != null && !config.getSchemaRegistryUrl().isBlank()) {
			settings.put("schema.registry.url", config.getSchemaRegistryUrl());
		}
		// Last, so registry credentials and subject-naming strategies can override anything
		// derived above.
		settings.putAll(config.getConverterProperties());
		settings.put("converter.type", isKey ? "key" : "value");
		return settings;
	}

	static Converter create(KafkaSourceConfig config, boolean isKey) {
		String className = className(config.getConverter());
		try {
			Converter converter = (Converter) Class.forName(className).getDeclaredConstructor().newInstance();
			converter.configure(settings(config, isKey), isKey);
			return converter;
		}
		catch (ClassNotFoundException e) {
			throw new IllegalStateException(String.format(
					"Converter class '%s' is not on the classpath. Avro is not bundled by default because its "
							+ "dependency tree is large and a JSON deployment does not need it — build with "
							+ "-Pavro, or add io.confluent:kafka-connect-avro-converter yourself.",
					className), e);
		}
		catch (ReflectiveOperationException | ClassCastException e) {
			throw new IllegalStateException(String.format(
					"Could not instantiate converter '%s'. It must be an org.apache.kafka.connect.storage.Converter "
							+ "with a public no-argument constructor.",
					className), e);
		}
	}

}
