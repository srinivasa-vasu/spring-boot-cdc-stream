package io.cdc.stream.event;

import io.cdc.stream.apply.PartitionPosition;
import org.apache.kafka.connect.connector.ConnectRecord;

/**
 * One change event, decoded and located.
 *
 * <p>
 * Typed on {@link ConnectRecord} rather than on {@code SinkRecord}, because everything the
 * apply side reads — the envelope, the key, the topic — is declared there. Nothing in the
 * dispatcher or the buffer depends on where the record came from.
 *
 * @param position the Kafka coordinates of this event, written into the sink transaction so
 * the sink is the authority on how far the pipeline has got
 */
public record PipelineEvent(ConnectRecord<?> record, PartitionPosition position) {
}
