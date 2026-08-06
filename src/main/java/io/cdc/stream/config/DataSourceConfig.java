package io.cdc.stream.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;
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
 * reads {@code DatabaseMetaData} and issues DDL, neither of which belongs in the apply
 * transaction (DDL auto-commits anyway) and neither of which needs tagging, since logical
 * replication does not capture DDL. Keeping it off the single apply connection stops
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
	 * Origin names are interpolated into the init SQL, which cannot be parameterised, so
	 * the name is restricted to characters that cannot alter the statement.
	 */
	private static final Pattern SAFE_ORIGIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,63}");

	/**
	 * Row applies and the watermark write. Pinned to one connection and tagged with the
	 * replication origin.
	 */
	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource.hikari")
	public HikariDataSource dataSource(DataSourceProperties properties, ConsumerConfig config) {
		HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		dataSource.setPoolName("cdc-apply");

		String origin = config.getApplyOriginName();
		if (origin == null || origin.isBlank()) {
			log.info("consumer.apply-origin-name is not set, so writes are not tagged with a replication origin. "
					+ "Bidirectional replication needs it, or the peer cannot distinguish this pipeline's applies "
					+ "from application writes.");
			return dataSource;
		}
		if (!SAFE_ORIGIN_NAME.matcher(origin).matches()) {
			throw new IllegalStateException(
					"consumer.apply-origin-name '" + origin + "' must match " + SAFE_ORIGIN_NAME.pattern()
							+ ". It is interpolated into the connection init SQL, which cannot be parameterised.");
		}

		registerOrigin(properties, origin);
		dataSource.setConnectionInitSql("SELECT pg_replication_origin_session_setup('" + origin + "')");
		log.info(
				"Every connection in the apply pool will claim replication origin '{}', so writes to {} carry it. "
						+ "The pipeline reading that database will see it as source.origin and must discard it.",
				origin, properties.determineUrl());
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
