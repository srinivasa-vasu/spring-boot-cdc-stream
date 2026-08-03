package io.cdc.stream.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Fails startup if the pool is sized such that replication-origin tagging cannot work.
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
					+ "spring.datasource.hikari.maximum-pool-size=1 (currently %d). A replication origin can "
					+ "be held by only one session at a time, so any further connection would fail to claim "
					+ "it and the pool could not be filled.", origin, hikari.getMaximumPoolSize()));
		}
		log.info("Pool sized to 1 connection, holding replication origin '{}'. Init SQL: {}", origin,
				hikari.getConnectionInitSql());
	}

}
