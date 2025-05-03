package io.cdc.stream.config;

import java.io.IOException;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConfigurationProperties(prefix = "producer")
@Setter
@Getter
public class ProducerConfig {

	private String name;

	private String connectorClass;

	private String offsetStorage;

	private String offsetStorageJdbcUrl;

	private String offsetStorageJdbcUser;

	private String offsetStorageJdbcPassword;

	private String offsetStorageJdbcDriver;

	private String offsetStorageJdbcTable;

	private String offsetStorageJdbcSchema;

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

	private int taskMax;

	private String publicationName;

	private String topicPrefix;

	private String timestampPrecisionMode;

	private boolean provideTransactionMetadata;

	private boolean logErrorsIncludeMessages;

	private int pollIntervalMs;

	private int maxBatchSize;

	private int maxQueueSize;

	private int executorThreads;

	private boolean enableAsync;

	private String replicaIdentity;

	private String snapshotMode;

	@Bean
	public io.debezium.config.Configuration ybSourceConnector(Environment env) throws IOException {
		return io.debezium.config.Configuration.create()
			.with("name", name)
			.with("connector.class", connectorClass)
			.with("tasks.max", taskMax)
			.with("offset.storage", offsetStorage)
			.with("offset.storage.jdbc.url", offsetStorageJdbcUrl)
			.with("offset.storage.jdbc.user", offsetStorageJdbcUser)
			.with("offset.storage.jdbc.password", offsetStorageJdbcPassword)
			.with("offset.storage.jdbc.driver", offsetStorageJdbcDriver)
			.with("offset.storage.jdbc.table", offsetStorageJdbcTable)
			.with("offset.storage.jdbc.schema", offsetStorageJdbcSchema)
			.with("database.hostname", hostname)
			.with("database.port", port) // defaults to 5432
			.with("database.user", user)
			.with("database.password", password)
			.with("database.dbname", dbName)
			.with("database.server.id", serverId)
			.with("database.server.name", serverName)
			.with("table.include.list", tableList)
			.with("schema.include.list", schemaList)
			.with("plugin.name", pluginName)
			.with("publication.autocreate.mode", publicationMode)
			.with("replica.identity.autoset.values", replicaIdentity)
			.with("slot.name", replicationSlot)
			.with("publication.name", publicationName)
			.with("errors.log.include.messages", true)
			.with("topic.prefix", topicPrefix)
			.with("provide.transaction.metadata", provideTransactionMetadata)
			.with("timestamp.precision.mode", timestampPrecisionMode)
			.with("poll.interval.ms", pollIntervalMs)
			.with("max.batch.size", maxBatchSize)
			.with("max.queue.size", maxQueueSize)
			.with("snapshot.mode", snapshotMode)
			.build();
	}

}
