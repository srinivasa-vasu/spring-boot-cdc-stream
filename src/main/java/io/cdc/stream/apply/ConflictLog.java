package io.cdc.stream.apply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.cdc.stream.config.ConsumerConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records changes that were applied to nothing, so a discarded write is visible rather than
 * merely gone.
 *
 * <p>
 * A last-writer-wins rejection is not an error — the guard decided correctly — but it is a
 * real user's edit being dropped, and that fact is business-relevant even when the decision
 * was right. Without this it survives only as a log line.
 *
 * <p>
 * Deliberately a conflict <em>log</em> and not a dead-letter queue. Nothing here should be
 * replayed: last-writer-wins already adjudicated, and re-applying a loser would simply undo
 * the winner. The rows exist to be reconciled by a human or a job that understands the
 * business meaning of the collision.
 *
 * <p>
 * Written inside the row transaction, so a rolled-back apply cannot leave a rejection
 * recorded for something that never happened. Retention is safe to prune: unlike a version
 * store, nothing here is load-bearing for correctness.
 */
@Component
public class ConflictLog {

	private final static Logger log = LoggerFactory.getLogger(ConflictLog.class);

	private final ConsumerConfig config;

	/**
	 * Owned rather than injected.
	 *
	 * <p>
	 * Spring Boot 4 auto-configures a <em>Jackson 3</em> {@code tools.jackson.databind}
	 * mapper, so asking the context for the Jackson 2 type this serialises with finds no
	 * bean at all. Beyond that, the context's mapper is configured for HTTP payloads and can
	 * be customised by anything in the application — neither is a sensible dependency for an
	 * audit record that should serialise the same way for the life of the table. A private
	 * mapper also keeps this working in a deployment that drops the web starter entirely.
	 */
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
		// ISO-8601 rather than epoch numbers: these rows are read by people reconciling a
		// conflict, and a timestamp is the field they will be comparing.
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private final TableId table;

	private volatile boolean ready;

	private final AtomicLong recorded = new AtomicLong();

	public ConflictLog(ConsumerConfig config) {
		this.config = config;
		this.table = new TableId(config.getApplyStateSchema(), config.getConflictLogTable());
	}

	/** Why the change applied to nothing. */
	public enum Reason {

		/** The sink row was newer, so the guard refused it. */
		stale,
		/** There was no such row to update. */
		row_missing

	}

	/**
	 * @param laneTemplate the template the row transaction is open on — anything else is a
	 * different connection, and the record would survive a rollback of the apply it
	 * describes
	 */
	public void record(JdbcTemplate laneTemplate, ChangeRow row, Reason reason, Object incomingVersion) {
		long count = recorded.incrementAndGet();
		if (!config.isConflictLog()) {
			// Rate limited: a high rejection rate is usually clock skew or an application
			// that does not maintain its version column, and would otherwise flood the log.
			if (count % 1_000 == 1) {
				log.warn("Change to {} key {} was not applied ({}); incoming version {}. {} so far. Enable "
						+ "consumer.conflict-log to record these for reconciliation.", row.table(), keyOf(row), reason,
						incomingVersion, count);
			}
			return;
		}
		ensureTable(laneTemplate);
		laneTemplate.update("INSERT INTO " + table.qualified() + " (id, schema_name, table_name, row_key, payload, "
				+ "incoming_version, reason, source_tx, source_lsn, source_origin, rejected_at) "
				+ "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, now())", UUID.randomUUID().toString(),
				row.table().schema(), row.table().table(), json(keyOf(row)), json(row.values()),
				incomingVersion == null ? null : String.valueOf(incomingVersion), reason.name(), row.txId(), row.lsn(),
				row.origin());
	}

	/**
	 * The winning value is deliberately not recorded. It is already in the target table and
	 * can be joined on the key at reconciliation time, so capturing it here would only buy
	 * an extra read on the path that is already the slow one.
	 */
	private void ensureTable(JdbcTemplate laneTemplate) {
		if (ready) {
			return;
		}
		synchronized (this) {
			if (ready) {
				return;
			}
			laneTemplate.execute("CREATE TABLE IF NOT EXISTS " + table.qualified() + " (" + "id text PRIMARY KEY, "
					+ "schema_name text, " + "table_name text NOT NULL, " + "row_key jsonb NOT NULL, "
					+ "payload jsonb NOT NULL, " + "incoming_version text, " + "reason text NOT NULL, "
					+ "source_tx text, " + "source_lsn bigint, " + "source_origin text, "
					+ "rejected_at timestamptz NOT NULL DEFAULT now())");
			ready = true;
			log.info("Conflict log table {} ready; changes that apply to nothing are recorded there", table.qualified());
		}
	}

	private String json(Map<String, Object> values) {
		try {
			return objectMapper.writeValueAsString(values);
		}
		catch (Exception e) {
			// Never fail an apply over the audit trail.
			log.warn("Could not serialise a conflicting row for {}; recording it without a payload", table, e);
			return "{}";
		}
	}

	private static Map<String, Object> keyOf(ChangeRow row) {
		Map<String, Object> key = new LinkedHashMap<>();
		row.schema().keyColumns().forEach(column -> key.put(column, row.values().get(column)));
		return key;
	}

}
