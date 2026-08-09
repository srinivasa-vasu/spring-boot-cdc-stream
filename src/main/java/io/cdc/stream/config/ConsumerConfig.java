package io.cdc.stream.config;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.springframework.util.Assert.isTrue;

/**
 * Apply-side configuration.
 *
 * <p>
 * The apply path is always single-threaded and always preserves source order. The
 * {@code enableTransactionBoundary} flag selects <em>what</em> gets committed as one
 * unit:
 * <ul>
 * <li>{@code false} — rows are coalesced into batches of at most {@code batchSize} and
 * committed per batch. Source order is preserved, but a source transaction may be split
 * across several sink commits, so a reader of the sink can observe a partial transaction.
 * <li>{@code true} — rows are buffered per source transaction and the whole transaction
 * is committed atomically. Requires {@code producer.provide-transaction-metadata=true}.
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "consumer")
@Setter
@Getter
public class ConsumerConfig {

	/**
	 * Buffer each source transaction and apply it as a single atomic sink transaction.
	 */
	private boolean enableTransactionBoundary;

	/**
	 * Number of adjacent source transactions to group into one sink commit. Raising this
	 * trades commit granularity for throughput without ever exposing a partial source
	 * transaction. Only meaningful when {@code enableTransactionBoundary} is set.
	 */
	private int transactionsPerCommit = 1;

	/**
	 * Maximum rows per JDBC batch. In non-atomic mode this doubles as the commit size.
	 */
	private int batchSize = 500;

	/**
	 * Safety valve for atomic mode. A source transaction larger than this is flushed in
	 * pieces — atomicity is lost for that transaction and it is logged as a warning —
	 * rather than buffering without bound.
	 */
	private int maxBufferedRows = 50_000;

	/**
	 * Flush interval for partially filled buffers, so a trickle of changes is not held
	 * back indefinitely. Never splits a transaction in atomic mode.
	 */
	private int flushIntervalMs = 2000;

	/**
	 * How long to wait for in-flight work to drain on shutdown.
	 */
	private int drainIntervalMs = 30000;

	/**
	 * Table holding the applied-LSN watermark, written in the same transaction as the
	 * rows so replay after a crash is idempotent.
	 */
	private String applyStateTable = "cdc_apply_state";

	/**
	 * Schema for the apply-state table. Explicit rather than relying on
	 * {@code search_path}, so the table always lands somewhere predictable.
	 */
	private String applyStateSchema = "public";

	/**
	 * Discard transactions whose commit LSN is at or below the stored watermark.
	 *
	 * <p>
	 * Off by default, and it should stay off unless you have a specific reason. Every
	 * statement this pipeline generates is idempotent — upsert by key, delete by key — so
	 * re-applying a replayed transaction already converges on the same state; skipping is
	 * only an optimisation. The risk is not symmetric: a YugabyteDB replication slot's
	 * LSNs are local to that slot and restart low when the slot is recreated, so a
	 * watermark left over from an earlier slot makes every new transaction look
	 * already-applied and the pipeline silently discards all of it.
	 *
	 * <p>
	 * The watermark is written either way, so {@code cdc_apply_state} stays useful for
	 * monitoring how far the sink has got.
	 */
	private boolean skipAppliedLsn;

	/**
	 * Replication origin names to discard, matched against {@code source.origin} on each
	 * event.
	 *
	 * <p>
	 * This is loop prevention. In a bidirectional or same-database topology the rows this
	 * pipeline writes are themselves captured and streamed straight back; naming the
	 * origin those writes carry stops them being re-applied forever. An origin is
	 * recorded per transaction, so filtering on it discards whole transactions and can
	 * never leave a partially applied one.
	 *
	 * <p>
	 * For this to match anything the writer has to tag its transactions, via
	 * {@code pg_replication_origin_session_setup(...)} on its session. A plain JDBC write
	 * carries no origin at all and is indistinguishable from an ordinary application write.
	 *
	 * <p>
	 * Entries match as <em>prefixes</em>. A peer's origins are a family — see
	 * {@link OriginNames} — whose trailing components change when its slot is recreated or
	 * lanes are added, neither of which it would consult you about. Name the family
	 * ({@code <their-prefix>_<their-slot>}) once and the filter survives both.
	 */
	private Set<String> ignoreOrigins = new LinkedHashSet<>();

	/**
	 * Discard every change that carries any replication origin at all, rather than
	 * matching specific names.
	 *
	 * <p>
	 * This is the rule to use for bidirectional replication. A change written by a user
	 * has no origin; a change written by a replication apply does. Forwarding only
	 * un-originated changes therefore breaks the loop in any topology without either side
	 * needing to know the other's origin name — which {@link #ignoreOrigins} would
	 * otherwise require both deployments to agree on and keep in sync.
	 *
	 * <p>
	 * The trade-off is that it also stops changes cascading: in an A→B→C chain, C never
	 * sees A's changes, because by the time they are in B they carry B's origin. Use
	 * {@link #ignoreOrigins} instead when you want to forward some origins but not
	 * others.
	 */
	private boolean ignoreReplicatedChanges;

	/**
	 * Replication origin to tag this pipeline's own writes with, via
	 * {@code pg_replication_origin_session_setup}. Blank disables tagging.
	 *
	 * <p>
	 * The write half of loop prevention, and the half that makes {@code ignoreOrigins}
	 * useful. It applies only to <em>this</em> pipeline's own apply connection. Writes
	 * made by your application must stay untagged — that is precisely what marks them as
	 * changes worth replicating, and tagging them would cause the peer to discard them.
	 *
	 * <p>
	 * This is a <em>prefix</em>, not the origin itself: {@link OriginNames} composes
	 * {@code <prefix>_<slot>_<lane>} so that two pipelines reading different slots but
	 * writing to one sink cannot collide on a single static name.
	 *
	 * <p>
	 * Set against the sink ({@code spring.datasource}), since the origin is session state on
	 * the writer. Requires {@code spring.datasource.hikari.maximum-pool-size=1}, because an
	 * origin can only be active in one session at a time.
	 */
	private String applyOriginName;

	/**
	 * What to do about a column the change events carry that the sink does not have.
	 *
	 * <p>
	 * {@code skipIfNull} — the default — leaves the column out of the write when its value
	 * is null, and fails naming the column and key when it is not. This is the operable
	 * choice: a source column added ahead of the sink migration is a routine, temporary
	 * state, and halting the entire pipeline over one that may never carry a value is a
	 * large blast radius for nothing. Nothing is lost silently, because the decision is made
	 * per row against the actual value.
	 *
	 * <p>
	 * {@code fail} restores the stricter behaviour: refuse to start applying a table whose
	 * events carry any column the sink lacks, before a single row is written. Use it when
	 * the sink is expected to be exactly in step with the source and you would rather find
	 * out at startup.
	 */
	private UnknownColumns unknownColumns = UnknownColumns.skipIfNull;

	/**
	 * How long a sink shape stays cached once it is known to be missing columns.
	 *
	 * <p>
	 * Verification is normally keyed on the schema fingerprint, which describes the
	 * <em>source</em>. That is the right key for detecting source DDL — it is the only
	 * signal available, since logical decoding emits none — but it says nothing about the
	 * sink. So when the fix is a sink migration, the fingerprint never changes, the cached
	 * result never expires, and the pipeline keeps dropping a column that now exists until
	 * it is restarted. Someone does exactly the right thing and nothing happens.
	 *
	 * <p>
	 * This bounds that window. It applies <em>only</em> while a table is known to be missing
	 * columns; a healthy table is still verified once per schema shape and costs nothing.
	 */
	private int sinkRecheckIntervalMs = 30_000;

	/** See {@link #unknownColumns}. */
	public enum UnknownColumns {

		/** Omit the column when its value is null; fail when it is not. */
		skipIfNull,
		/** Refuse to apply the table at all. */
		fail

	}

	/**
	 * Size of the secondary pool used for the sink precondition check —
	 * {@code DatabaseMetaData} reads. Separate from the apply pool because that one is
	 * pinned to a single connection to hold the replication origin, and metadata reads
	 * have no reason to queue behind row applies.
	 */
	private int metadataPoolSize = 2;

	@PostConstruct
	public void validate() {
		isTrue(batchSize > 0 && batchSize <= 10000, "consumer.batch-size must be between 1 and 10000");
		isTrue(flushIntervalMs > 50, "consumer.flush-interval-ms must be greater than 50");
		isTrue(drainIntervalMs <= 180000, "consumer.drain-interval-ms must be less than or equal to 180000");
		isTrue(transactionsPerCommit > 0, "consumer.transactions-per-commit must be greater than 0");
		isTrue(maxBufferedRows >= batchSize, "consumer.max-buffered-rows must be greater than consumer.batch-size");
		isTrue(metadataPoolSize >= 1, "consumer.metadata-pool-size must be at least 1");
		isTrue(sinkRecheckIntervalMs > 0, "consumer.sink-recheck-interval-ms must be greater than 0");
	}

}
