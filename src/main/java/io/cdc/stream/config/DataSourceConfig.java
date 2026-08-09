package io.cdc.stream.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two pools against the same sink, because they have incompatible requirements.
 *
 * <p>
 * <b>The apply pool</b> ({@code @Primary}) carries the replication origin and is pinned
 * to a single connection. Both constraints come from the same fact: an origin is session
 * state held by one session at a time, so it must be re-claimed on every new connection —
 * done here by Hikari's {@code connectionInitSql} — and a second connection could not
 * claim it at all. Everything that must be tagged or must share the apply transaction
 * uses this pool: the row writes and the LSN watermark.
 *
 * <p>
 * <b>The metadata pool</b> is an ordinary pool with no origin. Schema reconciliation
 * reads {@code DatabaseMetaData}. Keeping it off the single apply connection stops
 * metadata chatter serialising behind row applies.
 *
 * <p>
 * The apply pool is deliberately {@code @Primary}: a component that forgets to qualify
 * its {@code JdbcTemplate} then lands on the tagged, transactional connection. That is
 * wrong in the harmless direction — slower — whereas defaulting to the untagged pool
 * would silently break loop prevention.
 *
 * <p>
 * Bootstrap ordering: {@code connection-init-sql} would fail on the first ever connection
 * if the origin did not exist, and Hikari seals its configuration the moment a pool
 * starts, so the origin cannot be created through the apply pool and the init SQL then
 * added. It is registered over a one-off {@link DriverManager} connection before either
 * pool is used.
 */
@Configuration
public class DataSourceConfig {

	private final static Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

	/**
	 * Row applies and the watermark write. Pinned to one connection and tagged with the
	 * replication origin.
	 */
	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource.hikari")
	public HikariDataSource dataSource(DataSourceProperties properties, ConsumerConfig config,
			ProducerConfig producerConfig) {
		HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		dataSource.setPoolName("cdc-apply");

		String prefix = config.getApplyOriginName();
		if (prefix == null || prefix.isBlank()) {
			log.info("consumer.apply-origin-name is not set, so writes are not tagged with a replication origin. "
					+ "Bidirectional replication needs it, or the peer cannot distinguish this pipeline's applies "
					+ "from application writes.");
			return dataSource;
		}

		// A prefix, not the origin itself: composing in the slot is what stops two
		// pipelines against the same sink claiming one name. See OriginNames.
		String origin = OriginNames.lane(prefix, producerConfig.getReplicationSlot(), OriginNames.SOLE_LANE);
		registerOrigin(properties, origin);
		verifyOriginIsFree(properties, origin);
		dataSource.setConnectionInitSql("SELECT pg_replication_origin_session_setup('" + origin + "')");
		log.info(
				"Every connection in the apply pool will claim replication origin '{}', so writes to {} carry it. "
						+ "The peer reading that database sees it as source.origin and must discard it — name the "
						+ "family '{}' in its consumer.ignore-origins, which is matched as a prefix and so survives "
						+ "a slot rename or a future lane.",
				origin, properties.determineUrl(), OriginNames.family(prefix, producerConfig.getReplicationSlot()));
		return dataSource;
	}

	/**
	 * Schema reconciliation only: {@code DatabaseMetaData} reads and DDL. No origin, and
	 * sized independently so it does not queue behind the single apply connection.
	 */
	@Bean(destroyMethod = "close")
	public HikariDataSource metadataDataSource(DataSourceProperties properties, ConsumerConfig config) {
		HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		dataSource.setPoolName("cdc-metadata");
		dataSource.setMaximumPoolSize(config.getMetadataPoolSize());
		dataSource.setMinimumIdle(1);
		return dataSource;
	}

	@Bean
	@Primary
	public JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean
	public JdbcTemplate metadataJdbcTemplate(@Qualifier("metadataDataSource") DataSource metadataDataSource) {
		return new JdbcTemplate(metadataDataSource);
	}

	/**
	 * Bound to the apply pool, so {@code TransactionTemplate} and the watermark write
	 * share the one tagged connection.
	 */
	@Bean
	@Primary
	public PlatformTransactionManager transactionManager(DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	/**
	 * Registers the origin if absent, over a connection outside either pool.
	 *
	 * <p>
	 * Existence is read from the {@code pg_replication_origin} catalog rather than
	 * inferred from the create call failing, because the failure is reported
	 * inconsistently: YugabyteDB raises a unique-index violation on
	 * {@code pg_replication_origin_roname_index} ({@code 23505}) where PostgreSQL raises
	 * {@code 42710 duplicate_object}. The duplicate is still tolerated, as a race guard
	 * between the two pipeline directions.
	 */
	private void registerOrigin(DataSourceProperties properties, String origin) {
		try (Connection connection = DriverManager.getConnection(properties.determineUrl(),
				properties.determineUsername(), properties.determinePassword())) {
			if (originExists(connection, origin)) {
				log.debug("Replication origin '{}' already registered", origin);
				return;
			}
			try (PreparedStatement create = connection.prepareStatement("SELECT pg_replication_origin_create(?)")) {
				create.setString(1, origin);
				create.execute();
				log.info("Created replication origin '{}'", origin);
			}
			catch (SQLException e) {
				if (alreadyExists(e)) {
					log.debug("Replication origin '{}' was created concurrently", origin);
					return;
				}
				throw e;
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Could not register replication origin '" + origin
					+ "' on the sink. This needs sufficient privilege and YugabyteDB replication origin support; "
					+ "clear consumer.apply-origin-name to disable tagging.", e);
		}
	}

	/**
	 * Claims and immediately releases the origin over a throwaway connection, so a name
	 * already held elsewhere is reported here rather than as a pool-fill failure.
	 *
	 * <p>
	 * Composition makes a collision unlikely, not impossible — two deployments with
	 * copy-pasted configuration still share prefix and slot. Left undetected, that surfaces
	 * as Hikari failing to fill the pool with {@code 55006} buried in the cause chain, which
	 * says nothing about origins. This runs before the pool starts, so it never races our
	 * own claim.
	 */
	private void verifyOriginIsFree(DataSourceProperties properties, String origin) {
		try (Connection connection = DriverManager.getConnection(properties.determineUrl(),
				properties.determineUsername(), properties.determinePassword())) {
			// Parameterised here — only Hikari's static init SQL forces interpolation.
			try (PreparedStatement claim = connection.prepareStatement("SELECT pg_replication_origin_session_setup(?)")) {
				claim.setString(1, origin);
				claim.execute();
			}
			try (PreparedStatement release = connection
				.prepareStatement("SELECT pg_replication_origin_session_reset()")) {
				release.execute();
			}
			log.debug("Replication origin '{}' is free to claim", origin);
		}
		catch (SQLException e) {
			if (inUse(e)) {
				throw new IllegalStateException(String.format(
						"Replication origin '%s' is already held by another session. An origin can be active in only "
								+ "one session at a time, so another deployment is using the same "
								+ "consumer.apply-origin-name AND producer.replication-slot — give one of them a "
								+ "distinct value.",
						origin), e);
			}
			// Anything else is not this check's business; the pool will report it in
			// context if it matters.
			log.debug("Could not pre-check replication origin '{}'; leaving it to the pool", origin, e);
		}
	}

	/** {@code 55006 object_in_use} is what PostgreSQL raises for an origin already taken. */
	private static boolean inUse(SQLException error) {
		for (SQLException cause = error; cause != null; cause = cause.getNextException()) {
			if ("55006".equals(cause.getSQLState())) {
				return true;
			}
			String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
			if (message.contains("already active for pid") || message.contains("could not find free replication state")) {
				return true;
			}
		}
		return false;
	}

	private boolean originExists(Connection connection, String origin) throws SQLException {
		try (PreparedStatement lookup = connection
			.prepareStatement("SELECT 1 FROM pg_catalog.pg_replication_origin WHERE roname = ?")) {
			lookup.setString(1, origin);
			try (ResultSet rs = lookup.executeQuery()) {
				return rs.next();
			}
		}
		catch (SQLException e) {
			log.debug("Could not read pg_replication_origin; will create and tolerate a duplicate", e);
			return false;
		}
	}

	/**
	 * Matches message text as well as SQLSTATE, since the codes differ between engines.
	 */
	private static boolean alreadyExists(SQLException error) {
		for (SQLException cause = error; cause != null; cause = cause.getNextException()) {
			if ("42710".equals(cause.getSQLState()) || "23505".equals(cause.getSQLState())) {
				return true;
			}
			String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
			if (message.contains("pg_replication_origin_roname_index") || message.contains("already exists")) {
				return true;
			}
		}
		return false;
	}

}
