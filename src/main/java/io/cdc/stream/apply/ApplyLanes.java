package io.cdc.stream.apply;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The set of apply lanes, and the binding from consuming thread to lane.
 *
 * <p>
 * A thread keeps the lane it is first given. Listener threads are long-lived and each owns
 * a disjoint set of partitions, so the binding is stable and a lane is never used by two
 * threads at once — which matters absolutely, because two threads on one connection would
 * interleave their statements into each other's transactions.
 *
 * <p>
 * Running out of lanes is treated as fatal rather than wrapped around. Sharing a lane
 * between two threads would not fail loudly; it would produce transactions containing rows
 * from two different commit units, and a sink that looks fine until someone reads it.
 */
public class ApplyLanes {

	private final static Logger log = LoggerFactory.getLogger(ApplyLanes.class);

	private final List<ApplyLane> lanes;

	/** Pools this class created and must therefore close. Lane 1 is Spring's, not ours. */
	private final List<HikariDataSource> owned;

	private final Map<Long, ApplyLane> bound = new ConcurrentHashMap<>();

	private final AtomicInteger allocated = new AtomicInteger();

	public ApplyLanes(List<ApplyLane> lanes, List<HikariDataSource> owned) {
		this.lanes = List.copyOf(lanes);
		this.owned = List.copyOf(owned);
	}

	/**
	 * The calling thread's lane, allocated on first call and stable thereafter.
	 */
	public ApplyLane current() {
		return bound.computeIfAbsent(Thread.currentThread().threadId(), threadId -> {
			int index = allocated.getAndIncrement();
			if (index >= lanes.size()) {
				throw new IllegalStateException(String.format(
						"Thread '%s' asked for apply lane %d but only %d exist. Every consuming thread needs its own "
								+ "lane — sharing one would interleave two commit units into a single transaction. "
								+ "Raise kafka.concurrency to match the number of listener threads.",
						Thread.currentThread().getName(), index + 1, lanes.size()));
			}
			ApplyLane lane = lanes.get(index);
			log.info("Bound consuming thread '{}' to {}", Thread.currentThread().getName(), lane);
			return lane;
		});
	}

	public int size() {
		return lanes.size();
	}

	public List<ApplyLane> all() {
		return lanes;
	}

	/** True when there is a single lane, which is the only case the LSN watermark suits. */
	public boolean isSingleLane() {
		return lanes.size() == 1;
	}

	@PreDestroy
	void close() {
		owned.forEach(HikariDataSource::close);
	}

}
