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
	void aPerInstanceIdentitySeparatesInstancesSharingAConsumerGroup() {
		// Without it both instances derive the same name and the second cannot claim it.
		String shared = OriginNames.lane("cdc_apply", "cdc-apply-cloud", 1);
		String podZero = OriginNames.lane("cdc_apply", "cdc-apply-cloud_0", 1);
		String podOne = OriginNames.lane("cdc_apply", "cdc-apply-cloud_1", 1);

		assertThat(podZero).isNotEqualTo(podOne).isNotEqualTo(shared);
		// Still one family, so a peer's ignore-origins prefix covers every pod and lane.
		assertThat(podZero).startsWith("cdc_apply_cdc-apply-cloud");
		assertThat(podOne).startsWith("cdc_apply_cdc-apply-cloud");
	}

	@Test
	void lanesAreOneBased() {
		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", "ybdb", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OriginNames.lanes("cdc_apply", "ybdb", 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void realisticDeploymentNamingFitsComfortably() {
		// The composed name stacks four parts, so 63 was too tight — this is 67 and is
		// nothing unusual for Kubernetes naming.
		String name = OriginNames.lane("cdc-apply-origin", "cdc-apply-prod-us-east-1_cdc-apply-statefulset-0", 1);

		assertThat(name).isEqualTo("cdc-apply-origin_cdc-apply-prod-us-east-1_cdc-apply-statefulset-0_1");
		assertThat(name.length()).isGreaterThan(63);
	}

	@Test
	void anOverlongComponentIsRejected() {
		String tooLong = "p".repeat(OriginNames.MAX_COMPONENT + 1);

		assertThatThrownBy(() -> OriginNames.lane("cdc_apply", tooLong, 1))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("over the " + OriginNames.MAX_COMPONENT);
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
