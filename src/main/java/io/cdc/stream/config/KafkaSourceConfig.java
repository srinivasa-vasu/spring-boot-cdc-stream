package io.cdc.stream.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.springframework.util.Assert.isTrue;

/**
 * Kafka ingestion settings, used when the {@code kafka} profile is active.
 *
 * <p>
 * Always present as a bean, even outside that profile, because
 * {@link io.cdc.stream.apply.KafkaOffsetStore} needs the consumer group to key its rows and
 * must not become profile-conditional itself.
 *
 * <p>
 * The source half of the pipeline is gone in this mode: no replication slot, no
 * publication, no Debezium offset table. Kafka Connect owns all of that. What is left here
 * is which topics to read and how to commit.
 */
@Configuration
@ConfigurationProperties(prefix = "kafka")
@Setter
@Getter
public class KafkaSourceConfig {

	private String bootstrapServers = "localhost:9092";

	/**
	 * Consumer group, and the key under which applied offsets are stored in the sink. Two
	 * deployments writing to the same sink must not share it.
	 */
	private String consumerGroup = "cdc-apply";

	/**
	 * Explicit topics to consume. Mutually exclusive with {@link #topicPattern}.
	 *
	 * <p>
	 * With {@code consumer.transaction-scope=global} this must be the single funnelled
	 * topic that carries every table <em>and</em> the transaction markers, on one partition
	 * — see {@link #topology}. With {@code table} scope it is the per-table topics.
	 */
	private List<String> topics = new ArrayList<>();

	/**
	 * Java regex matching the topics to consume, instead of listing them.
	 *
	 * <p>
	 * This is how a new table gets picked up without a restart: Debezium names per-table
	 * topics {@code <prefix>.<schema>.<table>}, so {@code ybdb\.public\..*} follows the
	 * capture set as it grows. Discovery is not instant — the consumer only notices a new
	 * topic on a metadata refresh, so {@link #metadataMaxAgeMs} bounds the lag, and picking
	 * it up triggers a rebalance.
	 *
	 * <p>
	 * Anchor it at the schema. A bare {@code ybdb\..*} would also match the transaction
	 * topic {@code ybdb.transaction}, whose markers are meaningless once the partitions are
	 * not co-ordered, and would drag in whatever else shares the prefix.
	 *
	 * <p>
	 * Only useful with {@code transaction-scope=table} or {@code none}. Global scope needs
	 * exactly one funnelled topic, and a pattern that matched several would quietly break
	 * the ordering it depends on.
	 */
	private String topicPattern;

	/**
	 * Records per poll. Kept at or below {@code consumer.batch-size} so one poll maps
	 * roughly onto one commit unit.
	 */
	private int maxPollRecords = 500;

	/**
	 * A new consumer group must not silently skip history, so this defaults to earliest.
	 * The sink-stored offset takes precedence over it whenever one exists.
	 */
	private String autoOffsetReset = "earliest";

	/**
	 * How stale topic metadata may get, and so the worst-case delay before a topic newly
	 * matching {@link #topicPattern} is discovered. Kafka's default is five minutes.
	 */
	private int metadataMaxAgeMs = 300_000;

	/**
	 * Listener threads. Must stay at 1 while {@code consumer.apply-origin-name} is set: the
	 * apply pool holds a replication origin and is therefore pinned to one connection, so a
	 * second thread would only contend for it.
	 */
	private int concurrency = 1;

	/**
	 * On partition assignment, seek to the offset the sink says it durably applied rather
	 * than trusting Kafka's committed offset.
	 *
	 * <p>
	 * On by default, and it is what makes the apply effectively exactly-once. The sink
	 * offset is written inside the row transaction, so it cannot disagree with the data;
	 * Kafka's commit happens afterwards and can lag it or race a rebalance.
	 */
	private boolean seekToSinkOffset = true;

	/** Table holding those offsets, created in {@code consumer.apply-state-schema}. */
	private String offsetTable = "cdc_kafka_offsets";

	/**
	 * Which Connect converter decodes the topic bytes: {@code json}, {@code avro}, or a
	 * fully-qualified {@code Converter} class name.
	 *
	 * <p>
	 * The choice does not reach the apply side — both produce a Connect {@code Struct} and
	 * {@code Schema}, and everything downstream works off that. What it changes is the wire:
	 * JSON with schemas enabled repeats the whole schema in every message, commonly five to
	 * ten times the size on a wide table, which also means fewer records fit in a fetch and
	 * commit units come out smaller than {@code max-poll-records} suggests. Avro sends a
	 * five-byte header and compact binary, and gets compatibility enforced at publish time
	 * rather than surfacing here as a halted pipeline.
	 *
	 * <p>
	 * Avro needs {@code io.confluent:kafka-connect-avro-converter} on the classpath — build
	 * with {@code -Pavro} — and a {@link #schemaRegistryUrl}.
	 */
	private String converter = "json";

	/** Required for {@code avro}. Ignored by the JSON converter. */
	private String schemaRegistryUrl;

	/**
	 * Passed straight through to {@code Converter.configure}, after the settings derived
	 * from the properties above and so able to override them. This is where registry
	 * credentials and subject-naming strategies go, e.g.
	 * {@code basic.auth.credentials.source}, {@code schema.registry.basic.auth.user.info},
	 * {@code value.subject.name.strategy}.
	 */
	private Map<String, String> converterProperties = new LinkedHashMap<>();

	/**
	 * Whether the value bytes carry their Connect schema inline. Applies to {@code json}
	 * only — Avro always carries a schema.
	 *
	 * <p>
	 * The whole apply side derives sink types from the Connect schema — {@code TypeMapper}
	 * reads Debezium logical names like {@code io.debezium.time.MicroTimestamp}, and
	 * {@code RecordConverter} needs the schema to spot the {@code yboutput}
	 * {@code {value, set}} column envelope. So the connector must run either
	 * {@code JsonConverter} with {@code schemas.enable=true} or an Avro converter against a
	 * registry. Plain schemaless JSON cannot drive this pipeline.
	 */
	private boolean schemasEnabled = true;

	/**
	 * Documentation of the source-side topology this consumer assumes. Not used at runtime;
	 * it exists so the assumption is stated somewhere that fails review rather than
	 * production.
	 */
	private String topology = "single-partition funnel for global scope; per-table topics for table scope";

	/**
	 * What the listener actually subscribes to.
	 *
	 * <p>
	 * Always a pattern, so there is one subscription path rather than two: an explicit list
	 * is quoted and OR-ed into an equivalent regex. {@code Pattern.quote} matters — a topic
	 * name contains dots, and unquoted they would each match any character.
	 */
	public String subscriptionPattern() {
		if (topicPattern != null && !topicPattern.isBlank()) {
			return topicPattern;
		}
		return topics.stream().map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
	}

	@PostConstruct
	void validate() {
		isTrue(maxPollRecords > 0, "kafka.max-poll-records must be greater than 0");
		isTrue(concurrency >= 1, "kafka.concurrency must be at least 1");
		isTrue(metadataMaxAgeMs > 0, "kafka.metadata-max-age-ms must be greater than 0");
		boolean avro = ConverterFactory.AVRO.equals(ConverterFactory.className(converter));
		isTrue(!avro || (schemaRegistryUrl != null && !schemaRegistryUrl.isBlank()),
				"kafka.converter=avro requires kafka.schema-registry-url — the converter cannot resolve a schema id "
						+ "without it");
		isTrue(!ConverterFactory.JSON.equals(ConverterFactory.className(converter)) || schemasEnabled,
				"kafka.schemas-enabled=false cannot drive this pipeline: sink types are derived from the Connect "
						+ "schema, so schemaless JSON leaves nothing to derive them from. Use avro, or enable "
						+ "schemas on the connector's JsonConverter.");
		boolean hasPattern = topicPattern != null && !topicPattern.isBlank();
		isTrue(!(hasPattern && !topics.isEmpty()),
				"Set either kafka.topics or kafka.topic-pattern, not both — they are alternative ways to say the "
						+ "same thing and having both hides which one is in effect");
		isTrue(hasPattern || !topics.isEmpty(), "Set kafka.topics or kafka.topic-pattern; there is nothing to consume");
		try {
			java.util.regex.Pattern.compile(subscriptionPattern());
		}
		catch (java.util.regex.PatternSyntaxException e) {
			throw new IllegalStateException("kafka.topic-pattern is not a valid Java regex: " + e.getMessage(), e);
		}
	}

}
