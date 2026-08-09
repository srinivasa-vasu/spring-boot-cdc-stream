package io.cdc.stream.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The origin name has to be unique per pipeline and stable across restarts. A static
 * {@code apply-origin-name} satisfies neither once two pipelines share a sink, which is
 * what composing the slot into it fixes.
 */
class OriginNamesTests {

	@Test
	void twoPipelinesOnDifferentSlotsGetDifferentOrigins() {
		// The failure this exists to prevent: both would otherwise claim 'cdc_apply' and
		// the second would die with 55006 inside Hikari's pool fill.
		assertThat(OriginNames.lane("cdc_apply", "ybdb_a", 1)).isEqualTo("cdc_apply_ybdb_a_1");
		assertThat(OriginNames.lane("cdc_apply", "ybdb_b", 1)).isEqualTo("cdc_apply_ybdb_b_1");
	}

	@Test
	void theNameIsDerivedSoARestartReusesIt() {
		// Origins are permanent catalog rows; a name that varied per run would leak one
		// every restart.
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 1)).isEqualTo(OriginNames.lane("cdc_apply", "ybdb", 1));
	}

	@Test
	void everyLaneSharesTheFamilyPrefixAPeerFiltersOn() {
		String family = OriginNames.family("cdc_apply", "ybdb");

		assertThat(family).isEqualTo("cdc_apply_ybdb");
		assertThat(OriginNames.lane("cdc_apply", "ybdb", OriginNames.SOLE_LANE)).startsWith(family + '_');
		// A lane added later still matches the same prefix, so the peer needs no change.
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 7)).startsWith(family + '_');
	}

	@Test
	void componentsThatCouldAlterTheInitSqlAreRejected() {
		// Hikari's connection-init SQL cannot be parameterised, so a quote must never reach
		// it.
		assertThatThrownBy(() -> OriginNames.lane("cdc'); DROP TABLE orders; --", "ybdb", 1))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("consumer.apply-origin-name");
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "yb db", 1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("producer.replication-slot");
	}

	@Test
	void aMissingComponentNamesTheProperty() {
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", null, 1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("producer.replication-slot");
		assertThatThrownBy(() -> OriginNames.lane("", "ybdb", 1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("consumer.apply-origin-name");
	}

	@Test
	void anOverlongComponentIsRejected() {
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "s".repeat(OriginNames.MAX_COMPONENT + 1), 1))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("over the " + OriginNames.MAX_COMPONENT);
	}

	@Test
	void lanesAreOneBased() {
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "ybdb", 0)).isInstanceOf(IllegalArgumentException.class);
	}

}
