package io.cdc.stream.config;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscription is always by pattern, so an explicit topic list has to be translated into
 * an equivalent regex. Getting the quoting wrong is the interesting failure: topic names
 * are full of dots, and an unquoted dot matches anything.
 */
class KafkaSourceConfigTests {

	@Test
	void anExplicitListBecomesAnExactAlternation() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setTopics(List.of("ybdb.public.orders", "ybdb.public.users"));

		Pattern pattern = Pattern.compile(config.subscriptionPattern());

		assertThat(pattern.matcher("ybdb.public.orders").matches()).isTrue();
		assertThat(pattern.matcher("ybdb.public.users").matches()).isTrue();
		// The dots must be literal. Unquoted, "ybdbXpublicXorders" would match too.
		assertThat(pattern.matcher("ybdbXpublicXorders").matches()).isFalse();
		assertThat(pattern.matcher("ybdb.public.reviews").matches()).isFalse();
	}

	@Test
	void aPatternFollowsTheCaptureSetAsItGrows() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setTopicPattern("ybdb\\.public\\..*");

		Pattern pattern = Pattern.compile(config.subscriptionPattern());

		assertThat(pattern.matcher("ybdb.public.orders").matches()).isTrue();
		// A table added later needs no config change.
		assertThat(pattern.matcher("ybdb.public.invoices").matches()).isTrue();
	}

	@Test
	void aSchemaAnchoredPatternExcludesTheTransactionAndHeartbeatTopics() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setTopicPattern("ybdb\\.public\\..*");

		Pattern pattern = Pattern.compile(config.subscriptionPattern());

		// Markers are meaningless under table scope, and consuming them is pure noise.
		assertThat(pattern.matcher("ybdb.transaction").matches()).isFalse();
		assertThat(pattern.matcher("__debezium-heartbeat.ybdb").matches()).isFalse();
	}

	@Test
	void aPatternTakesPrecedenceAndIsUsedVerbatim() {
		KafkaSourceConfig config = new KafkaSourceConfig();
		config.setTopicPattern("ybdb\\.all");

		assertThat(config.subscriptionPattern()).isEqualTo("ybdb\\.all");
	}

}
