package io.cdc.stream.apply;

import io.cdc.stream.config.ConsumerConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The applied-LSN watermark, stored in the sink and written inside the same transaction
 * as the rows it describes.
 *
 * <p>
 * Secondary to {@link KafkaOffsetStore} now that ingestion is from Kafka: the offset rows
 * are what a restart seeks to, and they carry none of the slot-recreation hazards an LSN
 * does. This remains useful for monitoring how far the sink has got in the source's own
 * terms, and is written only when there is a single apply lane — lanes advance
 * independently, so no single value describes their combined progress.
 */
@Component
public class ApplyStateStore {

	private final static Logger log = LoggerFactory.getLogger(ApplyStateStore.class);

	private final JdbcTemplate jdbcTemplate;

	private final ConsumerConfig config;

	private final String slot;

	/** Schema-qualified, so it never depends on the session's search_path. */
	private final TableId table;

	private volatile long lastLsn;

	/** Set when the slot's LSN sequence appears to have restarted; disables skipping. */
	private volatile boolean slotReset;

	private boolean sequenceChecked;

	public ApplyStateStore(JdbcTemplate jdbcTemplate, ConsumerConfig config,
			io.cdc.stream.config.PipelineIdentity identity) {
		this.jdbcTemplate = jdbcTemplate;
		this.config = config;
		// Slot name under the embedded engine, consumer group under Kafka. Reading the
		// slot directly would key every Kafka-mode row on null.
		this.slot = identity.id();
		this.table = new TableId(config.getApplyStateSchema(), config.getApplyStateTable());
	}

	@PostConstruct
	void initialise() {
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table.qualified() + " (" + "slot_name text PRIMARY KEY, "
				+ "last_txn_id text, " + "last_lsn bigint NOT NULL DEFAULT 0, "
				+ "applied_at timestamptz NOT NULL DEFAULT now())");
		Long stored = jdbcTemplate.query("SELECT last_lsn FROM " + table.qualified() + " WHERE slot_name = ?",
				rs -> rs.next() ? rs.getLong(1) : null, slot);
		lastLsn = stored == null ? 0L : stored;
		log.info("Apply state table {} ready on {} (database '{}', current_schema '{}')", table.qualified(),
				jdbcTemplate.queryForObject("SELECT inet_server_addr()::text || ':' || inet_server_port()",
						String.class),
				jdbcTemplate.queryForObject("SELECT current_database()", String.class),
				jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
		log.info("Apply watermark for slot '{}' starts at LSN {} (LSN-based skipping {})", slot, lastLsn,
				config.isSkipAppliedLsn() ? "enabled" : "disabled");
	}

	/**
	 * Whether this LSN was already committed by an earlier run.
	 *
	 * <p>
	 * Requires {@code consumer.skip-applied-lsn}, and additionally refuses to skip once
	 * an LSN regression has been seen. A YugabyteDB slot's LSNs are local to the slot and
	 * restart low when it is recreated, so a stale watermark would otherwise mark every
	 * incoming transaction as already-applied and quietly discard the entire stream.
	 */
	boolean alreadyApplied(Long lsn) {
		if (!config.isSkipAppliedLsn() || lsn == null || lastLsn <= 0 || slotReset) {
			return false;
		}
		return lsn <= lastLsn;
	}

	/**
	 * Detects that the slot's LSN sequence restarted. Called with the commit LSN of the
	 * first transaction of a run, before anything is skipped.
	 */
	void observeCommitLsn(long lsn) {
		if (sequenceChecked || lastLsn <= 0) {
			sequenceChecked = true;
			return;
		}
		sequenceChecked = true;
		if (lsn <= lastLsn) {
			slotReset = true;
			log.warn("First commit LSN {} is at or below the stored watermark {} for slot '{}'. "
					+ "This is either a replay or a recreated slot; LSN-based skipping is disabled for this run "
					+ "so nothing is discarded. Every statement is idempotent, so replay is safe.", lsn, lastLsn, slot);
		}
	}

	/**
	 * Advances the watermark. Must be called from inside the row transaction, through the
	 * lane's own template — any other template is a different connection and so a different
	 * transaction, which would let the watermark survive a rolled-back apply.
	 */
	void record(JdbcTemplate laneTemplate, String txId, long lsn) {
		laneTemplate.update("INSERT INTO " + table.qualified()
				+ " (slot_name, last_txn_id, last_lsn, applied_at) VALUES (?, ?, ?, now()) "
				+ "ON CONFLICT (slot_name) DO UPDATE SET last_txn_id = EXCLUDED.last_txn_id, "
				+ "last_lsn = EXCLUDED.last_lsn, applied_at = EXCLUDED.applied_at", slot, txId, lsn);
	}

	/** Publishes the new watermark only once the transaction has actually committed. */
	void committed(long lsn) {
		if (lsn > lastLsn) {
			lastLsn = lsn;
		}
	}

	long lastLsn() {
		return lastLsn;
	}

}
