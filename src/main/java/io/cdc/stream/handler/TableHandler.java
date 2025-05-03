package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Base;
import io.cdc.stream.event.OPERATION;
import io.cdc.stream.mapper.EntityMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TableHandler<T extends Base> {

	private final static Logger log = LoggerFactory.getLogger(TableHandler.class);

	private static final String OP = "op";

	private static final String BEFORE = "before";

	private static final String AFTER = "after";

	protected final ExecutorService executors = Executors.newVirtualThreadPerTaskExecutor();

	protected final ConsumerConfig config;

	private final EntityMapper entityMapper = new EntityMapper();

	private final BlockingQueue<T> queue;

	private final int MAX_BATCH_SIZE;

	private final int FLUSH_INTERVAL_MS;

	protected TableHandler(ConsumerConfig cfg) {
		config = cfg;
		queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
		MAX_BATCH_SIZE = config.getBatchSize();
		FLUSH_INTERVAL_MS = config.getFlushIntervalMs();
	}

	final void handle(Struct payload, Class<T> entityClass) {
		switch (OPERATION.valueOf(payload.getString(OP))) {
			case OPERATION.c, OPERATION.u, OPERATION.r ->
				save(entityMapper.mapStructToEntity(payload.getStruct(AFTER), entityClass));
			case OPERATION.d -> delete(entityMapper.mapStructToEntity(payload.getStruct(BEFORE), entityClass));
			default -> {
				log.warn("Unsupported operation: {}", payload.getString(OP));
			}
		}
	}

	final void handleQueue(Struct payload, Class<T> entityClass) {
		try {
			OPERATION operation = OPERATION.valueOf(payload.getString(OP));
			switch (operation) {
				case OPERATION.c, OPERATION.u, OPERATION.r ->
					offer(operation, entityMapper.mapStructToEntity(payload.getStruct(AFTER), entityClass));
				case OPERATION.d ->
					offer(operation, entityMapper.mapStructToEntity(payload.getStruct(BEFORE), entityClass));
				default -> {
				}
			}
		}
		catch (InterruptedException e) {
			log.warn("Interrupted while waiting for queue to be empty");
		}
	}

	final void processQueue(Class<T> entityClass) {
		final List<T> wlist = new ArrayList<>();
		final List<T> dlist = new ArrayList<>();
		OPERATION previous = OPERATION.d;
		long lastFlush = System.currentTimeMillis();
		try {
			do {
				T entity = queue.take();
				if (entity.operation() == OPERATION.c || entity.operation() == OPERATION.u
						|| entity.operation() == OPERATION.r) {
					if (previous == OPERATION.d) {
						delete(dlist);
						dlist.clear();
						previous = OPERATION.c;
					}
					wlist.add(entity);
					if (wlist.size() >= MAX_BATCH_SIZE || (System.currentTimeMillis() - lastFlush) >= FLUSH_INTERVAL_MS
							|| queue.isEmpty()) {
						save(wlist);
						wlist.clear();
						lastFlush = System.currentTimeMillis();
					}
				}
				else {
					if (previous == OPERATION.c) {
						save(wlist);
						wlist.clear();
						previous = OPERATION.d;
					}
					dlist.add(entity);
					if (dlist.size() >= MAX_BATCH_SIZE || (System.currentTimeMillis() - lastFlush) >= FLUSH_INTERVAL_MS
							|| queue.isEmpty()) {
						delete(dlist);
						dlist.clear();
						lastFlush = System.currentTimeMillis();
					}
				}
			}
			while (true);
		}
		catch (InterruptedException e) {
			log.warn("Interrupted while waiting for queue to be empty, persisting the pending in-flights. "
					+ "Queue Size: " + queue.size() + " In-flight create/update list size: " + wlist.size()
					+ " In-flight delete list size: " + dlist.size());
			if (previous == OPERATION.c) {
				save(wlist);
				delete(dlist);
			}
			else {
				delete(dlist);
				save(wlist);
			}
		}
	}

	protected void offer(OPERATION key, T value) throws InterruptedException {
		value.operation(key);
		queue.put(value);
	}

	@PreDestroy
	final void gracefulDrain() {
		var consumer = this.getClass().getSimpleName();
		try {
			log.info("Initiated graceful drain");
			if (!queue.isEmpty()) {
				log.info("Pending events to be processed in : " + consumer + ": " + queue.size());
				Thread.sleep(config.getDrainIntervalMs());
				if (!queue.isEmpty()) {
					log.error("Unable to gracefully process events in " + consumer + ": " + queue.size()
							+ " events left to be processed");
				}
				else {
					log.info("Successfully consumed all the pending events in " + consumer);
				}
			}
		}
		catch (InterruptedException e) {
			log.error(consumer + ": Interrupted while waiting for consumer events to be processed");
			throw new RuntimeException(e);
		}
		finally {
			if (!executors.isShutdown()) {
				executors.shutdown();
			}
		}
	}

	protected abstract void delete(T entity);

	protected abstract void save(T entity);

	protected abstract void save(List<T> objects);

	protected abstract void delete(List<T> objects);

	public abstract void handle(Struct payload);

	public abstract void handleQueue(Struct payload);

}
