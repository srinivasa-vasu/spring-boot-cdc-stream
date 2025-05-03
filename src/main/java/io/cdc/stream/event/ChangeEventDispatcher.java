package io.cdc.stream.event;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.ProducerConfig;
import io.cdc.stream.handler.TableHandler;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import org.springframework.stereotype.Component;

@SuppressWarnings("rawtypes")
@Component
public class ChangeEventDispatcher {

	private final Map<String, TableHandler> handlers = new HashMap<>();
	private static final String IGNORE_EVENT = "debezium-heartbeat";
	private final ExecutorService executor;
	private final ProducerConfig producerConfig;
	private final ConsumerConfig consumerConfig;

	public ChangeEventDispatcher(ProducerConfig producerConfig, ConsumerConfig consumerConfig) {
		this.producerConfig = producerConfig;
		this.consumerConfig = consumerConfig;
		this.executor = Executors.newFixedThreadPool(this.producerConfig.getExecutorThreads());
	}

	public void registerHandler(String tableName, TableHandler handler) {
		handlers.put(tableName, handler);
	}

	public void handle(RecordChangeEvent<SourceRecord> event) {
		SourceRecord sourceRecord = event.record();
		if (sourceRecord != null && !sourceRecord.topic().contains(IGNORE_EVENT)) {
			Struct value = (Struct) sourceRecord.value();
			if (value != null) {
				String table = value.getStruct("source").getString("table");
				var handler = handlers.get(table);
				if (handler != null) {
					if (consumerConfig.isEnableBatch()) {
						handler.handleQueue(value);
					}
					else {
						handler.handle(value);
					}
				}
			}
		}
	}

	public void handleBatch(List<RecordChangeEvent<SourceRecord>> events,
			DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
		for (RecordChangeEvent<SourceRecord> event : events) {
			if (producerConfig.isEnableAsync() && producerConfig.getExecutorThreads() > 1) {
				executor.execute(() -> handle(event));
			}
			else {
				handle(event);
			}
			committer.markProcessed(event);
		}
		committer.markBatchFinished();
	}
}
