package io.cdc.stream.config;

import io.cdc.stream.apply.KafkaOffsetStore;
import io.cdc.stream.event.ChangeEventDispatcher;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.connect.storage.Converter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

/**
 * Wiring for Kafka ingestion.
 *
 * <p>
 * Records arrive as raw bytes and are turned back into Connect {@code Struct}s by the
 * converter named in {@code kafka.converter} — see {@link ConverterFactory}. The apply side
 * is written against the Connect schema, so the wire format never reaches it; what it needs
 * is that a schema arrives at all, which rules out schemaless JSON but leaves JSON-with-
 * schemas and Avro equivalent.
 */
@Configuration
@EnableKafka
public class KafkaListenerConfig {

	private final static Logger log = LoggerFactory.getLogger(KafkaListenerConfig.class);

	@Bean
	public Converter cdcKeyConverter(KafkaSourceConfig config) {
		return ConverterFactory.create(config, true);
	}

	@Bean
	public Converter cdcValueConverter(KafkaSourceConfig config) {
		Converter converter = ConverterFactory.create(config, false);
		log.info("Decoding topic bytes with {}", converter.getClass().getName());
		return converter;
	}

	@Bean
	public ConsumerFactory<byte[], byte[]> cdcConsumerFactory(KafkaSourceConfig config) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getConsumerGroup());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
		// Offsets are committed by the listener, after the rows are durable.
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.getAutoOffsetReset());
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getMaxPollRecords());
		// Bounds how long a topic newly matching kafka.topic-pattern stays undiscovered.
		props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, config.getMetadataMaxAgeMs());
		return new DefaultKafkaConsumerFactory<>(props);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> cdcKafkaListenerContainerFactory(
			ConsumerFactory<byte[], byte[]> cdcConsumerFactory, KafkaSourceConfig config,
			io.cdc.stream.config.ConsumerConfig consumerConfig, KafkaOffsetStore offsetStore,
			ChangeEventDispatcher dispatcher, io.cdc.stream.apply.ApplyLanes lanes) {
		if (config.getConcurrency() > 1) {
			// Each thread gets its own buffer and its own apply lane, so parallel apply is
			// safe — but only where the ordering it discards was not load-bearing.
			if (consumerConfig.getTransactionScope() == io.cdc.stream.config.ConsumerConfig.TransactionScope.global) {
				throw new IllegalStateException(String.format(
						"kafka.concurrency is %d with consumer.transaction-scope=global. Global scope means one "
								+ "sink transaction per whole source transaction, which requires every table on one "
								+ "partition — so only one thread can ever hold an assignment, and the rest idle. "
								+ "Use transaction-scope=table for parallel apply, or concurrency 1.",
						config.getConcurrency()));
			}
			if (consumerConfig.isSkipAppliedLsn()) {
				throw new IllegalStateException(
						"consumer.skip-applied-lsn cannot be used with kafka.concurrency > 1. The LSN watermark is "
								+ "one row for the whole pipeline, and lanes advance independently, so no single "
								+ "value describes their combined progress. The per-partition Kafka offsets carry "
								+ "restart state instead.");
			}
			if (lanes.size() != config.getConcurrency()) {
				throw new IllegalStateException(String.format(
						"kafka.concurrency is %d but %d apply lane(s) were built. Every listener thread needs its "
								+ "own lane; sharing one would interleave two commit units into one transaction.",
						config.getConcurrency(), lanes.size()));
			}
			log.info("Parallel apply: {} lanes across {} listener threads, table-scoped transactions. Cross-table "
					+ "ordering is not preserved — each table's changes stay ordered within their partition.",
					lanes.size(), config.getConcurrency());
		}
		if (consumerConfig.getTransactionScope() == io.cdc.stream.config.ConsumerConfig.TransactionScope.global
				&& (config.getTopics().size() > 1 || config.getTopicPattern() != null)) {
			log.warn("consumer.transaction-scope=global subscribing to '{}'. Global scope requires exactly ONE topic "
					+ "on ONE partition, carrying every table and the transaction markers; anything that resolves to "
					+ "several topics leaves nothing co-ordering them, and commit boundaries will not be the "
					+ "source's.", config.subscriptionPattern());
		}

		ConcurrentKafkaListenerContainerFactory<byte[], byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(cdcConsumerFactory);
		factory.setBatchListener(true);
		factory.setConcurrency(config.getConcurrency());
		ContainerProperties properties = factory.getContainerProperties();
		properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
		properties.setConsumerRebalanceListener(rebalanceListener(config, offsetStore, dispatcher));
		return factory;
	}

	/**
	 * Seeks to what the sink durably applied, and drops buffered work when partitions are
	 * taken away.
	 *
	 * <p>
	 * Both halves matter. Seeking to the sink's offset rather than Kafka's committed one is
	 * what bounds replay exactly, since the sink offset was written inside the row
	 * transaction and so cannot disagree with the data. Discarding on revocation is the
	 * rebalance equivalent of the shutdown path: a partially buffered commit unit was never
	 * acknowledged, so it must not be applied by whoever picks the partition up next.
	 */
	private ConsumerAwareRebalanceListener rebalanceListener(KafkaSourceConfig config, KafkaOffsetStore offsetStore,
			ChangeEventDispatcher dispatcher) {
		return new ConsumerAwareRebalanceListener() {
			@Override
			public void onPartitionsRevokedBeforeCommit(@NonNull Consumer<?, ?> consumer, @NonNull Collection<TopicPartition> partitions) {
				dispatcher.discardUncommitted();
			}

			@Override
			public void onPartitionsAssigned(@NonNull Consumer<?, ?> consumer, @NonNull Collection<TopicPartition> partitions) {
				log.info("Assigned {} partition(s): {}", partitions.size(), partitions);
				if (partitions.isEmpty()) {
					log.warn("No partitions assigned. The subscription pattern '{}' matched no topic, or another "
							+ "member of group '{}' holds them all.", config.subscriptionPattern(),
							config.getConsumerGroup());
				}
				if (!config.isSeekToSinkOffset()) {
					return;
				}
				for (TopicPartition partition : partitions) {
					Long applied = offsetStore.lastApplied(partition.topic(), partition.partition());
					if (applied == null) {
						log.info("No sink offset for {}; starting from the group's committed position", partition);
						continue;
					}
					consumer.seek(partition, applied + 1);
					log.info("Seeking {} to offset {}, the first event after what the sink durably applied", partition,
							applied + 1);
				}
			}
		};
	}

}
