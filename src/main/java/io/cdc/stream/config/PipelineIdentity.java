package io.cdc.stream.config;

import org.springframework.stereotype.Component;

/**
 * A stable name for this pipeline.
 *
 * <p>
 * Several things key on "which pipeline is this" — the applied-LSN watermark, the Kafka
 * offset rows, and the replication origin family. The Kafka consumer group is that name:
 * two deployments writing to the same sink must already have distinct groups, so it is
 * exactly the identity those things need.
 */
@Component
public class PipelineIdentity {

	private final String id;

	public PipelineIdentity(KafkaSourceConfig kafkaConfig) {
		this.id = kafkaConfig.getConsumerGroup();
	}

	public String id() {
		return id;
	}

}
