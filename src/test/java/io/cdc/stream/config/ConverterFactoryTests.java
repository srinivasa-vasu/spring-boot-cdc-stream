package io.cdc.stream.config;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The converter is chosen by name so Avro stays off the default classpath. What matters is
 * that the shorthands resolve, that settings meaningless to a converter are not sent to it,
 * and that a missing Avro jar fails with something an operator can act on.
 */
class ConverterFactoryTests {

	@Test
	void shorthandsResolveAndAnythingElseIsAClassName() {
		assertThat(ConverterFactory.className("json")).isEqualTo(ConverterFactory.JSON);
		assertThat(ConverterFactory.className("avro")).isEqualTo(ConverterFactory.AVRO);
		assertThat(ConverterFactory.className("AVRO")).isEqualTo(ConverterFactory.AVRO);
		assertThat(ConverterFactory.className("com.example.MyConverter")).isEqualTo("com.example.MyConverter");
		// Unset means JSON, which is the only converter bundled by default.
		assertThat(ConverterFactory.className(null)).isEqualTo(ConverterFactory.JSON);
		assertThat(ConverterFactory.className("  ")).isEqualTo(ConverterFactory.JSON);
	}

	@Test
	void schemasEnableGoesToJsonOnly() {
		KafkaSourceConfig json = new KafkaSourceConfig();
		json.setConverter("json");

		assertThat(ConverterFactory.settings(json, false)).containsEntry("schemas.enable", "true");

		KafkaSourceConfig avro = new KafkaSourceConfig();
		avro.setConverter("avro");
		avro.setSchemaRegistryUrl("http://registry:8081");

		// Avro always carries a schema, and Connect's config classes log unknown keys —
		// sending it would put a permanent warning in the startup log.
		assertThat(ConverterFactory.settings(avro, false)).doesNotContainKey("schemas.enable")
			.containsEntry("schema.registry.url", "http://registry:8081");
	}

	@Test
	void keyAndValueConvertersAreToldWhichTheyAre() {
		KafkaSourceConfig config = new KafkaSourceConfig();

		assertThat(ConverterFactory.settings(config, true)).containsEntry("converter.type", "key");
		assertThat(ConverterFactory.settings(config, false)).containsEntry("converter.type", "value");
	}

	@Test
	void passthroughPropertiesOverrideDerivedOnes() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setConverter("avro");
		config.setSchemaRegistryUrl("http://derived:8081");
		config.setConverterProperties(Map.of("schema.registry.url", "http://explicit:8081",
				"basic.auth.credentials.source", "USER_INFO"));

		Map<String, Object> settings = ConverterFactory.settings(config, false);

		assertThat(settings).containsEntry("schema.registry.url", "http://explicit:8081")
			.containsEntry("basic.auth.credentials.source", "USER_INFO");
	}

	@Test
	void aMissingConverterClassSaysWhichOneAndHowToGetIt() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		// A name that is absent under every profile, so this does not quietly change
		// meaning depending on whether -Pavro was used to build.
		config.setConverter("io.cdc.stream.NoSuchConverter");

		assertThatThrownBy(() -> ConverterFactory.create(config, false)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("io.cdc.stream.NoSuchConverter")
			.hasMessageContaining("-Pavro")
			.hasCauseInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void aClassThatIsNotAConverterIsRejected() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setConverter("java.lang.String");

		assertThatThrownBy(() -> ConverterFactory.create(config, false)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("must be an org.apache.kafka.connect.storage.Converter");
	}

	@Test
	void jsonIsAlwaysAvailable() {
		KafkaSourceConfig config = new KafkaSourceConfig();

		assertThat(ConverterFactory.create(config, false)).isNotNull();
	}

}
