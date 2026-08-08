package io.cdc.stream.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replication origin names are derived, not generated. The properties that matter are that
 * lane {@code n} is stable across restarts, that two pipelines sharing a sink cannot
 * collide, and that the family prefix a peer filters on covers every lane.
 */
class OriginNamesTests {

	@Test
	void laneNamesAreDerivedAndStable() {
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 1)).isEqualTo("cdc_apply_ybdb_1");
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 8)).isEqualTo("cdc_apply_ybdb_8");
		// Same inputs, same name: a restart reuses the origin it already registered rather
		// than leaking a new catalog row.
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 1)).isEqualTo(OriginNames.lane("cdc_apply", "ybdb", 1));
	}

	@Test
	void thePipelineComponentSeparatesTwoPipelinesSharingASink() {
		assertThat(OriginNames.lane("cdc_apply", "ybdb", 1)).isNotEqualTo(OriginNames.lane("cdc_apply", "other", 1));
	}

	@Test
	void lanesAreBoundedByConcurrency() {
		assertThat(OriginNames.lanes("cdc_apply", "ybdb", 4)).containsExactly("cdc_apply_ybdb_1", "cdc_apply_ybdb_2",
				"cdc_apply_ybdb_3", "cdc_apply_ybdb_4");
		assertThat(OriginNames.lanes("cdc_apply", "ybdb", 1)).containsExactly("cdc_apply_ybdb_1");
	}

	@Test
	void everyLaneStartsWithTheFamilyPrefix() {
		String family = OriginNames.family("cdc_apply", "ybdb");

		assertThat(OriginNames.lanes("cdc_apply", "ybdb", 16)).allMatch(name -> name.startsWith(family + '_'));
	}

	@Test
	void lanesAreOneBased() {
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "ybdb", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OriginNames.lanes("cdc_apply", "ybdb", 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void componentsThatCouldAlterTheInitSqlAreRejected() {
		// The name is interpolated into pg_replication_origin_session_setup('...'), which
		// cannot be parameterised, so a quote must never reach it.
		assertThatThrownBy(() -> OriginNames.lane("cdc'); DROP TABLE orders; --", "ybdb", 1))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "yb db", 1)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> OriginNames.lane("", "ybdb", 1)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> OriginNames.lane(null, "ybdb", 1)).isInstanceOf(IllegalStateException.class);
	}

}
