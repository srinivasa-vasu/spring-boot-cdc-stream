package io.cdc.stream.apply;

/**
 * Where one event sat in its Kafka partition.
 *
 * <p>
 * Recorded in the sink inside the same transaction as the rows, which is what makes the
 * sink — not Kafka's committed offset — the authority on how far this pipeline has got.
 * Kafka's own commit is then only an optimisation that saves re-reading.
 *
 * <p>
 * This is a strictly better restart key than the LSN watermark it sits beside. A
 * YugabyteDB slot's LSNs are local to the slot and restart low when it is recreated, which
 * is the entire reason {@code consumer.skip-applied-lsn} defaults to off and
 * {@link ApplyStateStore} carries a reset heuristic. A topic-partition offset has no
 * equivalent hazard.
 *
 * @param offset the offset of the event itself. Seeking resumes at {@code offset + 1}.
 */
public record PartitionPosition(String topic, int partition, long offset) {
}
