package io.cdc.stream.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "producer")
@Setter
@Getter
public class ProducerConfig {

	private String name;

	private String connectorClass;

	/**
	 * Defaults to the JDBC store. Left unset, Debezium falls back to
	 * {@code FileOffsetBackingStore}, which then fails because
	 * {@code offset.storage.file.filename} is not configured.
	 */
	private String offsetStorage = "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore";

	private String offsetStorageJdbcUrl;

	private String offsetStorageJdbcUser;

	private String offsetStorageJdbcPassword;

	/**
	 * Maps to {@code offset.storage.jdbc.offset.table.name}, not the shorter
	 * {@code offset.storage.jdbc.table} that reads like the obvious name. Debezium
	 * validates only that the required fields are present and drops anything it does not
	 * declare, so the wrong key is accepted in silence and the store falls back to its
	 * default table, {@code debezium_offset_storage}.
	 *
	 * <p>
	 * Leave it unqualified. {@code JdbcOffsetBackingStore} checks for the table with
	 * {@code DatabaseMetaData.getTables}, passing this value as a table <em>name
	 * pattern</em>, so a schema-qualified value never matches an existing table — and the
	 * create it then runs is a bare {@code CREATE TABLE} with no {@code IF NOT EXISTS},
	 * which fails on the second startup. Put {@code currentSchema=} on
	 * {@code offsetStorageJdbcUrl} instead.
	 */
	private String offsetStorageJdbcTable;

	private String offsetFlushIntervalMs;

	private String hostname;

	private String port;

	private String user;

	private String password;

	private String dbName;

	private String pluginName;

	private String replicationSlot;

	private String tableList;

	private String schemaList;

	private String serverId;

	private String serverName;

	private String publicationMode;

	/**
	 * The embedded engine runs a single task, and a single replication slot cannot be
	 * read concurrently, so anything above 1 is misleading.
	 */
	private int taskMax = 1;

	private String publicationName;

	private String topicPrefix;

	private String timestampPrecisionMode;

	/**
	 * How {@code numeric}/{@code decimal} columns are represented. Left unset, the
	 * connector default ({@code precise}) applies, which yields exact {@code BigDecimal}
	 * values. Set to {@code double} only if you accept rounding on money columns.
	 */
	private String decimalHandlingMode;

	/**
	 * Emits BEGIN/END markers and stamps each change event with its transaction id.
	 * Required by {@code consumer.enable-transaction-boundary}.
	 */
	private boolean provideTransactionMetadata = true;

	private boolean logErrorsIncludeMessages = true;

	/**
	 * Keeps the slot's confirmed LSN moving when the captured tables are idle but the
	 * database is not. Without it, WAL and intents accumulate on the source until the
	 * next change to a captured table.
	 */
	private int heartbeatIntervalMs = 10000;

	/**
	 * Signalling table for Debezium's incremental snapshots.
	 *
	 * <p>
	 * Does not work against YugabyteDB logical replication: incremental snapshots are not
	 * supported, so setting this will not backfill a table added to the capture set later,
	 * however the signalling table is configured. Backfilling needs a separate replication
	 * slot, because slot creation is what establishes a consistent snapshot point — see
	 * "Adding a table to the capture set" in the README. Left here for a plain PostgreSQL
	 * source, where it does work.
	 */
	private String signalDataCollection;

	private int pollIntervalMs;

	private int maxBatchSize;

	private int maxQueueSize;

	private String replicaIdentity;

	private String snapshotMode;

	@Bean
	public io.debezium.config.Configuration ybSourceConnector() {
		Map<String, String> props = new LinkedHashMap<>();
		put(props, "name", name);
		put(props, "connector.class", connectorClass);
		put(props, "tasks.max", taskMax);
		put(props, "offset.storage", offsetStorage);
		put(props, "offset.storage.jdbc.url", offsetStorageJdbcUrl);
		put(props, "offset.storage.jdbc.user", offsetStorageJdbcUser);
		put(props, "offset.storage.jdbc.password", offsetStorageJdbcPassword);
		put(props, "offset.storage.jdbc.offset.table.name", offsetStorageJdbcTable);
		put(props, "offset.flush.interval.ms", offsetFlushIntervalMs);
		put(props, "database.hostname", hostname);
		put(props, "database.port", port);
		put(props, "database.user", user);
		put(props, "database.password", password);
		put(props, "database.dbname", dbName);
		put(props, "database.server.id", serverId);
		put(props, "database.server.name", serverName);
		put(props, "table.include.list", tableList);
		put(props, "schema.include.list", schemaList);
		put(props, "plugin.name", pluginName);
		put(props, "publication.autocreate.mode", publicationMode);
		put(props, "replica.identity.autoset.values", replicaIdentity);
		put(props, "slot.name", replicationSlot);
		put(props, "publication.name", publicationName);
		put(props, "errors.log.include.messages", logErrorsIncludeMessages);
		put(props, "topic.prefix", topicPrefix);
		put(props, "provide.transaction.metadata", provideTransactionMetadata);
		put(props, "timestamp.precision.mode", timestampPrecisionMode);
		put(props, "decimal.handling.mode", decimalHandlingMode);
		put(props, "heartbeat.interval.ms", heartbeatIntervalMs);
		put(props, "signal.data.collection", signalDataCollection);
		put(props, "poll.interval.ms", pollIntervalMs);
		put(props, "max.batch.size", maxBatchSize);
		put(props, "max.queue.size", maxQueueSize);
		put(props, "snapshot.mode", snapshotMode);
		return io.debezium.config.Configuration.from(props);
	}

	/** Skips unset values so they fall through to the connector's own defaults. */
	private static void put(Map<String, String> props, String key, Object value) {
		if (value == null) {
			return;
		}
		String text = String.valueOf(value);
		if (!text.isBlank()) {
			props.put(key, text);
		}
	}

}
