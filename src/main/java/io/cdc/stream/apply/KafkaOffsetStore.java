package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.KafkaSourceConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The consumed-offset watermark, stored in the sink and written inside the same transaction
 * as the rows it describes.
 *
 * <p>
 * This makes the <em>sink</em> the authority on how far the pipeline has got, and reduces
 * Kafka's own committed offset to an optimisation that saves re-reading. That matters
 * because a Kafka commit is necessarily separate from the sink write: acknowledge first and
 * a crash loses rows, acknowledge after and a crash replays them. Writing the offset
 * transactionally removes the choice — on restart the consumer seeks to what the sink
 * actually contains, so replay is bounded and exact.
 *
 * <p>
 * It is also a better restart key than the LSN watermark beside it. A YugabyteDB slot's
 * LSNs are local to the slot and restart low when it is recreated, which is why
 * {@code consumer.skip-applied-lsn} defaults to off and {@link ApplyStateStore} needs a
 * reset heuristic at all. A topic-partition offset has no such failure mode.
 *
 * <p>
 * Inert outside the Kafka profile: the embedded engine passes no positions, so nothing is
 * ever written and the table stays empty.
 */
@Component
public class KafkaOffsetStore {

	private final static Logger log = LoggerFactory.getLogger(KafkaOffsetStore.class);

	private final JdbcTemplate jdbcTemplate;

	private final KafkaSourceConfig kafkaConfig;

	private final TableId table;

	/** Created lazily, so the embedded profile never makes a table it will not use. */
	private volatile boolean ready;

	public KafkaOffsetStore(JdbcTemplate jdbcTemplate, ConsumerConfig config, KafkaSourceConfig kafkaConfig) {
		this.jdbcTemplate = jdbcTemplate;
		this.kafkaConfig = kafkaConfig;
		this.table = new TableId(config.getApplyStateSchema(), kafkaConfig.getOffsetTable());
	}

	/**
	 * Records the highest offset applied per topic-partition.
	 *
	 * <p>
	 * Must be called from inside the row transaction, through the calling lane's own
	 * template — that is the connection the transaction is open on. Any other template,
	 * including the metadata pool's, is a different connection and therefore a different
	 * transaction, which would decouple the recorded offset from the rows it describes.
	 */
	public void record(JdbcTemplate laneTemplate, List<PartitionPosition> positions) {
		if (positions.isEmpty()) {
			return;
		}
		ensureTable();
		for (PartitionPosition position : positions) {
			laneTemplate.update("INSERT INTO " + table.qualified()
					+ " (consumer_group, topic, partition_id, last_offset, applied_at) VALUES (?, ?, ?, ?, now()) "
					+ "ON CONFLICT (consumer_group, topic, partition_id) DO UPDATE SET "
					+ "last_offset = EXCLUDED.last_offset, applied_at = EXCLUDED.applied_at",
					kafkaConfig.getConsumerGroup(), position.topic(), position.partition(), position.offset());
		}
	}

	/**
	 * The offset of the last event this sink durably applied for the partition, or null if
	 * it has never applied one. The consumer seeks to {@code this + 1} on assignment.
	 */
	public Long lastApplied(String topic, int partition) {
		ensureTable();
		return jdbcTemplate.query(
				"SELECT last_offset FROM " + table.qualified()
						+ " WHERE consumer_group = ? AND topic = ? AND partition_id = ?",
				rs -> rs.next() ? rs.getLong(1) : null, kafkaConfig.getConsumerGroup(), topic, partition);
	}

	private void ensureTable() {
		if (ready) {
			return;
		}
		synchronized (this) {
			if (ready) {
				return;
			}
			jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table.qualified() + " (" + "consumer_group text NOT NULL, "
					+ "topic text NOT NULL, " + "partition_id int NOT NULL, " + "last_offset bigint NOT NULL, "
					+ "applied_at timestamptz NOT NULL DEFAULT now(), "
					+ "PRIMARY KEY (consumer_group, topic, partition_id))");
			ready = true;
			log.info("Kafka offset table {} ready for consumer group '{}'", table.qualified(),
					kafkaConfig.getConsumerGroup());
		}
	}

}
