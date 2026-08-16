package io.cdc.stream.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.cdc.stream.apply.ApplyLane;
import io.cdc.stream.apply.ApplyLanes;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;
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
			PipelineIdentity identity) {
		HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		dataSource.setPoolName("cdc-apply");

		String prefix = config.getApplyOriginNamePrefix();
		if (prefix == null || prefix.isBlank()) {
			log.info("consumer.apply-origin-name is not set, so writes are not tagged with a replication origin. "
					+ "Bidirectional replication needs it, or the peer cannot distinguish this pipeline's applies "
					+ "from application writes.");
			return dataSource;
		}

		if (!identity.isPerInstance()) {
			log.warn("Replication origin family '{}' is derived from the consumer group alone, so every instance in "
					+ "that group would claim the same origins and only the first to start could succeed. Running "
					+ "more than one instance requires kafka.instance-id — stable per instance, never random, since "
					+ "origins are permanent catalog rows.", OriginNames.family(prefix, identity.id()));
		}
		String origin = OriginNames.lane(prefix, identity.id(), 1);
		registerOrigin(properties, origin);
		dataSource.setConnectionInitSql("SELECT pg_replication_origin_session_setup('" + origin + "')");
		log.info(
				"Every connection in the apply pool will claim replication origin '{}', so writes to {} carry it. "
						+ "The peer reading that database sees it as source.origin and must discard it — configure "
						+ "its consumer.ignore-origins with the family prefix '{}', which is matched as a prefix and "
						+ "so covers every lane.",
				origin, properties.determineUrl(), OriginNames.family(prefix, identity.id()));
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
					+ "' on the sink. This needs sufficient privilege to write pg_replication_origin; "
					+ "clear consumer.apply-origin-name to disable tagging.", e);
		}
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

	/**
	 * One apply lane per listener thread.
	 *
	 * <p>
	 * Lane 1 is Spring's own pool, so a single-lane deployment behaves exactly as it did
	 * before lanes existed. Lanes 2..n are additional pools of one connection each, copied
	 * from lane 1 so they inherit every setting that matters — {@code reWriteBatchedInserts}
	 * above all, which the batching in {@code ChangeEventApplier} depends on — and differing
	 * only in the replication origin their init SQL claims.
	 *
	 * <p>
	 * They are created eagerly rather than on demand, so a lane whose origin is already held
	 * by another process fails at startup instead of on the first row it tries to apply.
	 */
	@Bean
	public ApplyLanes applyLanes(DataSourceProperties properties, @Qualifier("dataSource") HikariDataSource primary,
			PlatformTransactionManager transactionManager, ConsumerConfig config, KafkaSourceConfig kafkaConfig,
			PipelineIdentity identity) {
		String prefix = config.getApplyOriginNamePrefix();
		boolean tagged = prefix != null && !prefix.isBlank();
		List<ApplyLane> lanes = new ArrayList<>();
		List<HikariDataSource> owned = new ArrayList<>();

		lanes.add(new ApplyLane(1, tagged ? OriginNames.lane(prefix, identity.id(), 1) : null, new JdbcTemplate(primary),
				new TransactionTemplate(transactionManager)));

		for (int lane = 2; lane <= kafkaConfig.getConcurrency(); lane++) {
			String origin = tagged ? OriginNames.lane(prefix, identity.id(), lane) : null;
			if (origin != null) {
				registerOrigin(properties, origin);
			}
			HikariConfig copy = new HikariConfig();
			primary.copyStateTo(copy);
			copy.setPoolName("cdc-apply-" + lane);
			copy.setMaximumPoolSize(1);
			copy.setMinimumIdle(1);
			copy.setConnectionInitSql(
					origin == null ? null : "SELECT pg_replication_origin_session_setup('" + origin + "')");
			HikariDataSource dataSource = new HikariDataSource(copy);
			owned.add(dataSource);
			lanes.add(new ApplyLane(lane, origin, new JdbcTemplate(dataSource),
					new TransactionTemplate(new DataSourceTransactionManager(dataSource))));
		}

		log.info("Apply lanes: {} ({})", lanes.size(),
				lanes.stream().map(ApplyLane::toString).reduce((a, b) -> a + ", " + b).orElse("none"));
		return new ApplyLanes(lanes, owned);
	}

}
