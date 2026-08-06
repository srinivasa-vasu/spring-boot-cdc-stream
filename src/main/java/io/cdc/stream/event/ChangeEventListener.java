package io.cdc.stream.event;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.RetryConfig;
import io.debezium.config.Configuration;
import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Owns the embedded engine's lifecycle.
 *
 * <p>
 * The retry is a plain loop rather than {@code @Retryable}. Two reasons: the previous
 * annotation was never active, because {@code start()} invoked {@code failSafeRun}
 * through a method reference on {@code this} and so bypassed the Spring proxy that
 * implements the advice; and a {@link DebeziumEngine} cannot be re-run once it has
 * stopped, so each attempt has to build a fresh engine, which retry advice around a
 * single instance would not do.
 */
@Component
public class ChangeEventListener {

	private final static Logger log = LoggerFactory.getLogger(ChangeEventListener.class);

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(runnable -> new Thread(runnable, "cdc-engine"));

	private final Configuration connectorConfig;

	private final ChangeEventDispatcher dispatcher;

	private final RetryConfig retryConfig;

	private final ConsumerConfig consumerConfig;

	private volatile DebeziumEngine<RecordChangeEvent<SourceRecord>> engine;

	private volatile boolean shuttingDown;

	public ChangeEventListener(ChangeEventDispatcher dispatcher, Configuration connectorConfig, RetryConfig retryConfig,
			ConsumerConfig consumerConfig) {
		this.dispatcher = dispatcher;
		this.connectorConfig = connectorConfig;
		this.retryConfig = retryConfig;
		this.consumerConfig = consumerConfig;
	}

	@PostConstruct
	void start() {
		executor.execute(this::runWithRetries);
	}

	private void runWithRetries() {
		int maxAttempts = Math.max(1, retryConfig.getMaxAttempts());
		long delay = retryConfig.getInitialInterval();
		for (int attempt = 1; attempt <= maxAttempts && !shuttingDown; attempt++) {
			try {
				log.info("Starting change event listener (attempt {}/{})", attempt, maxAttempts);
				engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
					.using(connectorConfig.asProperties())
					.notifying(dispatcher::handleBatch)
					.build();
				engine.run();
				if (shuttingDown) {
					return;
				}
				log.warn("Change event engine returned without being asked to stop");
			}
			catch (Throwable e) {
				if (shuttingDown) {
					return;
				}
				log.error("Change event listener failed on attempt {}/{}", attempt, maxAttempts, e);
			}

			if (dispatcher.fatal() != null) {
				log.error("Not retrying: the pipeline stopped for a reason a retry cannot fix. "
						+ "Resolve the cause and restart the application.", dispatcher.fatal());
				return;
			}
			if (attempt < maxAttempts && !sleep(delay)) {
				return;
			}
			delay = Math.min((long) (delay * retryConfig.getMultiplier()), retryConfig.getMaxInterval());
		}
		if (!shuttingDown) {
			log.error("Change event listener gave up after {} attempt(s). No changes are being replicated.",
					maxAttempts);
		}
	}

	private boolean sleep(long millis) {
		try {
			log.info("Retrying change event listener in {}ms", millis);
			Thread.sleep(millis);
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Closing the engine first is what makes the dispatcher's shutdown safe: no new
	 * events can arrive while it decides what to do with anything still buffered. Bean
	 * destruction order gives us this, since the dispatcher is a dependency of this bean.
	 */
	@PreDestroy
	void stop() throws IOException {
		shuttingDown = true;
		DebeziumEngine<RecordChangeEvent<SourceRecord>> current = engine;
		if (current != null) {
			current.close();
		}
		executor.shutdown();
		try {
			if (!executor.awaitTermination(consumerConfig.getDrainIntervalMs(), TimeUnit.MILLISECONDS)) {
				log.warn("Change event listener did not stop within {}ms; forcing shutdown",
						consumerConfig.getDrainIntervalMs());
				executor.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
		log.info("Stopped change event listener");
	}

}
