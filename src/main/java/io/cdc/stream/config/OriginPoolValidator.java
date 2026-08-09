package io.cdc.stream.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Fails startup if the apply pool is sized such that replication-origin tagging cannot work.
 *
 * <p>
 * This constrains a <em>pool</em>, not the pipeline. Parallel apply is still available: each
 * listener thread gets its own single-connection pool with its own origin — see
 * {@code DataSourceConfig.applyLanes} — so the total connection count is
 * {@code kafka.concurrency}, while each individual pool stays at one.
 *
 * <p>
 * A replication origin can be held by one session at a time. With more than one pooled
 * connection, the second one's {@code connectionInitSql} fails with
 * {@code 55006 object_in_use} and Hikari simply cannot fill the pool — which surfaces as
 * opaque connection-acquisition failures rather than anything pointing at the cause.
 * Checked here, after {@code spring.datasource.hikari.*} has been bound, so the message
 * names the real problem.
 *
 * <p>
 * Pool size 1 has a second benefit: since Hikari never exceeds the maximum, the outgoing
 * connection is always closed before its replacement is opened, so a recycle cannot
 * overlap two sessions competing for the same origin.
 *
 * <p>
 * This checks the primary pool, which is apply lane 1. Lanes 2..n are built from it by
 * {@code DataSourceConfig.applyLanes} with the same size and a different origin each, so
 * the constraint holds for them by construction.
 */
@Component
public class OriginPoolValidator {

	private final static Logger log = LoggerFactory.getLogger(OriginPoolValidator.class);

	private final DataSource dataSource;

	private final ConsumerConfig config;

	public OriginPoolValidator(@Qualifier("dataSource") DataSource dataSource, ConsumerConfig config) {
		this.dataSource = dataSource;
		this.config = config;
	}

	@PostConstruct
	void validate() {
		String origin = config.getApplyOriginName();
		if (origin == null || origin.isBlank() || !(dataSource instanceof HikariDataSource hikari)) {
			return;
		}
		if (hikari.getMaximumPoolSize() != 1) {
			throw new IllegalStateException(String.format("consumer.apply-origin-name is set to '%s', which requires "
					+ "spring.datasource.hikari.maximum-pool-size=1 (currently %d). An origin is claimed by the "
					+ "pool's connection-init SQL, which is one static string, so every connection in a pool would "
					+ "claim the same origin and only the first could succeed. "
					+ "This is a per-pool limit, not a limit on parallelism: raise kafka.concurrency instead and "
					+ "each listener thread gets its own single-connection pool with its own origin.",
					origin, hikari.getMaximumPoolSize()));
		}
		log.info("Apply lane 1 sized to 1 connection, holding a replication origin. Init SQL: {}",
				hikari.getConnectionInitSql());
	}

}
