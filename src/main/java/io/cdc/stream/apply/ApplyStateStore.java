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
 * Debezium's own offset store is flushed asynchronously (see
 * {@code producer.offset-flush-interval-ms}), so it always lags the apply and re-delivery
 * after a crash is a certainty rather than an edge case. Because this watermark commits
 * atomically with the data, a replayed transaction can be recognised and skipped, which
 * turns at-least-once delivery into effectively exactly-once apply.
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
			io.cdc.stream.config.ProducerConfig producerConfig) {
		this.jdbcTemplate = jdbcTemplate;
		this.config = config;
		this.slot = producerConfig.getReplicationSlot();
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
		// Logged explicitly because the table is easy to go looking for in the wrong
		// place:
		// it lives in the sink, and stays empty until the first transaction commits.
		log.info("Apply state table {} ready on {} (database '{}', current_schema '{}')", table.qualified(),
				jdbcTemplate.queryForObject("SELECT inet_server_addr()::text || ':' || inet_server_port()",
						String.class),
				jdbcTemplate.queryForObject("SELECT current_database()", String.class),
				jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
		log.info("Apply watermark for slot '{}' starts at LSN {} (LSN-based skipping {})", slot, lastLsn,
				config.isSkipAppliedLsn() ? "enabled" : "disabled");
	}

	/** True when this LSN was already committed to the sink by an earlier run. */
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
		// A legitimate replay re-sends transactions at or below the watermark, but so
		// does a
		// recreated slot. They are indistinguishable from the LSN alone, so err towards
		// applying: the statements are idempotent, and dropping data is not recoverable.
		if (lsn <= lastLsn) {
			slotReset = true;
			log.warn("First commit LSN {} is at or below the stored watermark {} for slot '{}'. "
					+ "This is either a replay or a recreated slot; LSN-based skipping is disabled for this run "
					+ "so nothing is discarded. Every statement is idempotent, so replay is safe.", lsn, lastLsn, slot);
		}
	}

	/**
	 * Advances the watermark. Must be called from inside the row transaction so it
	 * commits or rolls back with the rows.
	 */
	void record(String txId, long lsn) {
		jdbcTemplate.update("INSERT INTO " + table.qualified()
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
