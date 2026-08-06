package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.config.ProducerConfig;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * An append-only record of every DDL statement this pipeline has issued against the sink.
 *
 * <p>
 * Entries are written after the DDL has run, outside any transaction, because DDL
 * auto-commits and so can never be part of the row transaction. A crash in between loses
 * the entry rather than the DDL: a gap in the trail, not a divergence, and the next
 * reconcile re-derives the same statement and finds it already applied. For the same
 * reason a failed write is logged and swallowed — losing the audit trail is not a reason
 * to stop replicating.
 */
@Component
public class SchemaAuditLog {

	private final static Logger log = LoggerFactory.getLogger(SchemaAuditLog.class);

	private final JdbcTemplate jdbcTemplate;

	private final ConsumerConfig config;

	private final String slot;

	/** Schema-qualified, so it never depends on the session's search_path. */
	private final TableId table;

	/**
	 * Uses the metadata pool for the same reasons {@link SchemaEvolver} does: these writes
	 * accompany DDL, belong to no transaction, and have no business occupying the single
	 * origin-tagged apply connection.
	 */
	public SchemaAuditLog(@Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate, ConsumerConfig config,
			ProducerConfig producerConfig) {
		this.jdbcTemplate = jdbcTemplate;
		this.config = config;
		this.slot = producerConfig.getReplicationSlot();
		this.table = new TableId(config.getApplyStateSchema(), config.getSchemaAuditTable());
	}

	/** What prompted the statement. The statement text says the rest. */
	public enum Change {

		CREATE, ALTER

	}

	@PostConstruct
	void initialise() {
		if (!config.isSchemaAudit()) {
			log.info("Schema audit is disabled; DDL applied to the sink will only appear in the log");
			return;
		}
		// A random id keeps the table from clustering on one tablet. It is never read
		// back, so there is nothing to gain from making it meaningful.
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table.qualified() + " (" + "id text PRIMARY KEY, "
				+ "slot_name text NOT NULL, " + "table_schema text, " + "table_name text NOT NULL, "
				+ "change_type text NOT NULL, " + "fingerprint text, " + "statement text NOT NULL, "
				+ "applied_at timestamptz NOT NULL DEFAULT now())");
		log.info("Schema audit table {} ready; every CREATE or ALTER this pipeline issues is recorded there",
				table.qualified());
	}

	/**
	 * Records one statement that has already run.
	 * @param schema the incoming source schema this reconcile was converging on. Every
	 * statement from a single reconcile carries the same fingerprint, which makes it the
	 * grouping key for "these statements together moved the table to that shape".
	 * @param statement the exact DDL issued, which is the part an operator actually needs
	 */
	void record(TableSchema schema, Change change, String statement) {
		if (!config.isSchemaAudit()) {
			return;
		}
		try {
			jdbcTemplate.update(
					"INSERT INTO " + table.qualified() + " (id, slot_name, table_schema, table_name, change_type, "
							+ "fingerprint, statement, applied_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
					UUID.randomUUID().toString(), slot, schema.table().schema(), schema.table().table(), change.name(),
					schema.fingerprint(), statement);
		}
		catch (RuntimeException e) {
			log.warn("Could not record the schema change to {} in {}. The DDL was applied — only the audit entry is "
					+ "missing. Statement: {}", schema.table(), table.qualified(), statement, e);
		}
	}

}
