package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Base;
import io.cdc.stream.event.OPERATION;
import io.cdc.stream.mapper.EntityMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.connect.data.Struct;

public abstract class TableHandler<T extends Base> {
	private final EntityMapper entityMapper = new EntityMapper();
	private final BlockingQueue<T> queue;
	protected final ExecutorService executors = Executors.newVirtualThreadPerTaskExecutor();
	protected final ConsumerConfig config;
	private final int MAX_BATCH_SIZE;
	private final int FLUSH_INTERVAL_MS;
	private static final String OP = "op";
	private static final String BEFORE = "before";
	private static final String AFTER = "after";

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
			// do nothing
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
			// todo log
		}
	}

	final void processQueue(Class<T> entityClass) {
		List<T> wlist = new ArrayList<>();
		List<T> dlist = new ArrayList<>();
		OPERATION previous = OPERATION.d;
		long lastFlush = System.currentTimeMillis();
		try {
			do {
				T entity = queue.take();
				if (entity.operation() == OPERATION.c || entity.operation() == OPERATION.u || entity.operation() == OPERATION.r) {
					if (previous == OPERATION.d) {
						delete(dlist);
						dlist.clear();
						previous = OPERATION.c;
					}
					wlist.add(entity);
					if (wlist.size() >= MAX_BATCH_SIZE || (System.currentTimeMillis() - lastFlush) >= FLUSH_INTERVAL_MS || queue.isEmpty()) {
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
					if (dlist.size() >= MAX_BATCH_SIZE || (System.currentTimeMillis() - lastFlush) >= FLUSH_INTERVAL_MS || queue.isEmpty()) {
						delete(dlist);
						dlist.clear();
						lastFlush = System.currentTimeMillis();
					}
				}
			}
			while (true);
		}
		catch (InterruptedException e) {
			if (previous == OPERATION.c) {
				save(wlist);
				delete(dlist);
			}
			else {
				delete(dlist);
				save(wlist);
			}
		}
//		save(queue.stream().takeWhile(val -> val.operation() == OPERATION.c || val.operation() == OPERATION.u)
//				.toList());
//		delete(queue.stream().takeWhile(val -> val.operation() == OPERATION.d).toList());
	}

	protected void offer(OPERATION key, T value) throws InterruptedException {
		value.operation(key);
		queue.put(value);
	}

	protected abstract void delete(T entity);

	protected abstract void save(T entity);

	protected abstract void save(List<T> objects);

	protected abstract void delete(List<T> objects);

	public abstract void handle(Struct payload);

	public abstract void handleQueue(Struct payload);
}
