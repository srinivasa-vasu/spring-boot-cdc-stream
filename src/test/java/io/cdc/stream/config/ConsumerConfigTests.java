package io.cdc.stream.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerConfigTests {

	@Test
	void unknownColumnsIsLenientByDefault() {
		// A source column added ahead of its sink migration is routine; halting the whole
		// pipeline over one that may never carry a value is a large blast radius.
		assertThat(new ConsumerConfig().getUnknownColumns()).isEqualTo(ConsumerConfig.UnknownColumns.skipIfNull);
	}

	@Test
	void theSinkRecheckIntervalMustBePositive() {
		// Zero or negative would either spin on metadata reads or never re-check, and the
		// re-check is the only thing that notices a sink migration.
		ConsumerConfig config = new ConsumerConfig();
		config.setSinkRecheckIntervalMs(0);

		assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sink-recheck-interval-ms");
	}

	@Test
	void defaultsValidate() {
		assertThatCode(new ConsumerConfig()::validate).doesNotThrowAnyException();
	}

}
