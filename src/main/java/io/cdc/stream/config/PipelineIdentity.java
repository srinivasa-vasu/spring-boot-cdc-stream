package io.cdc.stream.config;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * A stable name for this pipeline, and for this instance of it.
 *
 * <p>
 * Two things key on it: the applied-LSN watermark row, and the replication origin family.
 * Both need an identity that is <em>stable across restarts</em> and <em>distinct between
 * anything that runs concurrently</em>. Origins make the first requirement sharp — they are
 * permanent catalog rows that nothing drops, so an identity that changes on every restart
 * leaks a family each time.
 *
 * <p>
 * The consumer group supplies the first half: two pipelines writing to the same sink must
 * already have distinct groups. {@code kafka.instance-id} supplies the second, and is only
 * needed when several instances share a group — without it they would all derive the same
 * origin names and only the first to start could claim them.
 *
 * <p>
 * Deliberately <em>not</em> used to key {@link io.cdc.stream.apply.KafkaOffsetStore}. Those
 * rows are keyed by consumer group and partition, because a partition moves between
 * instances on a rebalance and its new owner must be able to read where the previous one
 * got to. Making offsets per-instance would lose the position at exactly that moment.
 */
@Component
public class PipelineIdentity {

	private final static Logger log = LoggerFactory.getLogger(PipelineIdentity.class);

	private final String id;

	/**
	 * -- GETTER --
	 * Whether this identity distinguishes instances, not just pipelines.
	 */
	@Getter
	private final boolean perInstance;

	public PipelineIdentity(KafkaSourceConfig kafkaConfig) {
		String instance = kafkaConfig.getInstanceId();
		this.perInstance = instance != null && !instance.isBlank();
		this.id = perInstance ? kafkaConfig.getConsumerGroup() + '_' + instance.trim()
				: kafkaConfig.getConsumerGroup();
		log.info("Pipeline identity '{}'{}", id,
				perInstance ? "" : " (kafka.instance-id unset — shared by every instance in the group)");
	}

	/** Consumer group, plus the instance id when one is configured. */
	public String id() {
		return id;
	}

}
