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


	@Test
	void tiesAreLeftToDivergeUnlessATiebreakIsConfigured() {
		assertThat(new ConsumerConfig().incomingWinsTies()).isFalse();
	}

	@Test
	void theTwoSidesSettleATieOppositely() {
		// The point of the rule: both evaluate the same comparison and reach opposite
		// answers, so exactly one accepts the tie and they converge on one value.
		ConsumerConfig a = tiebreak("a", "b");
		ConsumerConfig b = tiebreak("b", "a");

		assertThat(a.incomingWinsTies()).isTrue();
		assertThat(b.incomingWinsTies()).isFalse();
	}

	@Test
	void identicalNodeIdsAreRejected() {
		// Both sides would settle a tie the same way and still diverge — the exact failure
		// the tiebreak exists to prevent, so it must not be configurable.
		ConsumerConfig config = tiebreak("same", "same");

		assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must differ");
	}

	@Test
	void aTiebreakWithoutIdentifiersIsRejected() {
		ConsumerConfig config = new ConsumerConfig();
		config.setConflictTiebreak(ConsumerConfig.ConflictTiebreak.nodeId);

		assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conflict-node-id");
	}

	private static ConsumerConfig tiebreak(String node, String peer) {
		ConsumerConfig config = new ConsumerConfig();
		config.setConflictTiebreak(ConsumerConfig.ConflictTiebreak.nodeId);
		config.setConflictNodeId(node);
		config.setConflictPeerId(peer);
		return config;
	}

}
