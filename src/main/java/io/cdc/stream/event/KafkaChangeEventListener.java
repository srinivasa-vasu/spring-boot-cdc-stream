package io.cdc.stream.event;

import io.cdc.stream.apply.KafkaOffsetStore;
import io.cdc.stream.apply.PartitionPosition;
import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.KafkaSourceConfig;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.storage.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Drives the same {@link ChangeEventDispatcher} from Kafka topics instead of the embedded
 * engine.
 *
 * <p>
 * <b>Ordering is a property of the topology, not of this class.</b> The dispatcher's
 * guarantees are only as good as the order records arrive in:
 * <ul>
 * <li>{@code consumer.transaction-scope=global} requires every table <em>and</em> the
 * transaction topic funnelled into one topic on <em>one partition</em>, via Debezium's
 * {@code ByLogicalTableRouter}. Non-envelope records — transaction markers included — are
 * rerouted by that SMT with their value untouched, which is what puts BEGIN/data/END back
 * into a single ordered stream. The producer must also be idempotent, or
 * {@code max.in.flight.requests.per.connection=1}, or a retry silently reorders within the
 * partition.
 * <li>{@code consumer.transaction-scope=table} works on ordinary per-table topics. Each
 * commit unit is one transaction's changes to one table, decided from the {@code txId} each
 * row already carries, so no markers are needed and no partition has to be co-ordered with
 * any other.
 * </ul>
 *
 * <p>
 * Offsets are acknowledged to Kafka only after the rows commit, and the authoritative
 * position is written into the sink transaction itself — see {@link KafkaOffsetStore}. On
 * assignment the consumer seeks to what the sink actually contains, so a rebalance or crash
 * replays a bounded, exact amount rather than whatever Kafka last managed to commit.
 */
@Component
public class KafkaChangeEventListener {

	private final static Logger log = LoggerFactory.getLogger(KafkaChangeEventListener.class);

	private final ChangeEventDispatcher dispatcher;

	private final Converter keyConverter;

	private final Converter valueConverter;

	private final ConsumerConfig consumerConfig;

	private final KafkaSourceConfig kafkaConfig;

	public KafkaChangeEventListener(ChangeEventDispatcher dispatcher, Converter cdcKeyConverter,
			Converter cdcValueConverter, ConsumerConfig consumerConfig, KafkaSourceConfig kafkaConfig) {
		this.dispatcher = dispatcher;
		this.keyConverter = cdcKeyConverter;
		this.valueConverter = cdcValueConverter;
		this.consumerConfig = consumerConfig;
		this.kafkaConfig = kafkaConfig;
	}

	// Always a pattern subscription, even for an explicit list, so a new table's topic can
	// be picked up without a restart. See KafkaSourceConfig.subscriptionPattern().
	@KafkaListener(topicPattern = "#{@kafkaSourceConfig.subscriptionPattern()}",
			groupId = "#{@kafkaSourceConfig.consumerGroup}", containerFactory = "cdcKafkaListenerContainerFactory")
	public void onBatch(List<ConsumerRecord<byte[], byte[]>> records, Acknowledgment acknowledgment)
			throws InterruptedException {
		List<PipelineEvent> events = new ArrayList<>(records.size());
		for (ConsumerRecord<byte[], byte[]> record : records) {
			SinkRecord decoded = decode(record);
			if (decoded == null) {
				continue;
			}
			events.add(new PipelineEvent(decoded,
					new PartitionPosition(record.topic(), record.partition(), record.offset())));
		}
		if (events.isEmpty()) {
			// Nothing to apply, but the offsets must still move or the poll repeats
			// forever. Worth saying out loud: a batch that is entirely tombstones or
			// entirely undecodable would otherwise vanish without trace.
			log.warn("Received {} record(s) but none decoded to a change event; acknowledging without applying",
					records.size());
			acknowledgment.acknowledge();
			return;
		}
		log.debug("Received {} record(s), {} decoded, from {}", records.size(), events.size(),
				records.getFirst().topic());
		dispatcher.handleBatch(events, new BatchAcknowledger(acknowledgment));
	}

	/**
	 * Turns the raw bytes back into a Connect record.
	 *
	 * <p>
	 * Both key and value are converted: {@code RecordConverter} reads the key schema to
	 * work out which columns form the primary key, so dropping it would leave every table
	 * looking keyless and make upserts and deletes impossible.
	 * @return null for a tombstone, which Debezium emits after each delete and which
	 * carries nothing to apply
	 */
	private SinkRecord decode(ConsumerRecord<byte[], byte[]> record) {
		if (record.value() == null) {
			return null;
		}
		SchemaAndValue key = record.key() == null ? SchemaAndValue.NULL
				: keyConverter.toConnectData(record.topic(), record.key());
		SchemaAndValue value = valueConverter.toConnectData(record.topic(), record.value());
		return new SinkRecord(record.topic(), record.partition(), key.schema(), key.value(), value.schema(),
				value.value(), record.offset());
	}

	/**
	 * Acknowledges the whole poll batch once, at the end.
	 *
	 * <p>
	 * Per-event acknowledgement would be finer grained but buys nothing here: the sink
	 * already holds the exact applied offset, written inside the row transaction, and that
	 * is what a restart seeks to. Kafka's commit only saves re-reading.
	 */
	private record BatchAcknowledger(Acknowledgment acknowledgment) implements Acknowledger {

		@Override
		public void processed(PipelineEvent event) {
			// Offsets advance in batchFinished; the sink transaction already recorded the
			// authoritative position for this event.
		}

		@Override
		public void batchFinished() {
			acknowledgment.acknowledge();
		}

	}

	/**
	 * Logs the topology assumption at startup, where it can be checked against the
	 * connector config, rather than leaving it implicit until ordering quietly breaks.
	 */
	@jakarta.annotation.PostConstruct
	void describe() {
		if (consumerConfig.getTransactionScope() == ConsumerConfig.TransactionScope.global) {
			log.info("Ingesting from Kafka topics matching {} with global transaction scope. This assumes ALL tables and the "
					+ "transaction topic are funnelled into a SINGLE PARTITION; if they are not, commit boundaries "
					+ "are not the source's and ordering across tables is not preserved.", kafkaConfig.subscriptionPattern());
		}
		else {
			log.info("Ingesting from Kafka topics matching {} with {} transaction scope (concurrency {})",
					kafkaConfig.subscriptionPattern(), consumerConfig.getTransactionScope(), kafkaConfig.getConcurrency());
		}
	}

}
