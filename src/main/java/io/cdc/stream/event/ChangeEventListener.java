package io.cdc.stream.event;

import io.debezium.config.Configuration;
import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class ChangeEventListener {

	private final static Logger log = LoggerFactory.getLogger(ChangeEventListener.class);
	private final Executor executor = Executors.newSingleThreadExecutor();

	private final DebeziumEngine<RecordChangeEvent<SourceRecord>> changeEventEngine;

	public ChangeEventListener(ChangeEventDispatcher dispatcher, Configuration config) {

		this.changeEventEngine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
				.using(config.asProperties())
				.notifying(dispatcher::handleBatch)
				.build();
	}

	@PostConstruct
	private void start() {
		this.executor.execute(this::failSafeRun);
	}

	@Retryable(
			retryFor = {Throwable.class},
			maxAttempts = 3,
			backoff = @Backoff(delay = 5000, multiplier = 2)
	)
	public void failSafeRun() {
		try {
			log.info("Starting Debezium engine");
			changeEventEngine.run();
			log.info("Debezium engine completed normally");
		}
		catch (Throwable e) {
			log.error("Debezium engine failed", e);
			throw e;
		}
	}

	@Recover
	public void recoverDebeziumFailure(Exception e) throws IOException {
		log.error("Failed to start Debezium engine after multiple retries", e);
		stop();
	}

	@PreDestroy
	private void stop() throws IOException {
		if (this.changeEventEngine != null) {
			this.changeEventEngine.close();
			if (executor instanceof ExecutorService ex) {
				ex.shutdown();
			}
		}
	}
}
