package io.cdc.stream.event;

/**
 * How the dispatcher tells a transport that an event is safely applied.
 *
 * <p>
 * Called only after the rows have committed to the sink — never before. Marking an event
 * processed while its row is still buffered is what would make the pipeline at-most-once,
 * and the ordering here is the only thing preventing it.
 */
public interface Acknowledger {

	/**
	 * The event is durable in the sink and its offset may advance.
	 */
	void processed(PipelineEvent event) throws InterruptedException;

	/**
	 * End of a delivery batch. The embedded engine uses this to flush its offset store;
	 * the Kafka driver commits the poll batch here, having written the authoritative
	 * offsets into the sink transaction already.
	 */
	default void batchFinished() throws InterruptedException {
	}

}
