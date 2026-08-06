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
	 * {@code none} to never issue DDL against the sink, {@code basic} to propagate
	 * additive and safely widening source schema changes.
	 *
	 * <p>
	 * Off by default, and it is the master switch: nothing below it issues DDL while this
	 * is {@code none}, {@link #autoCreateTables} included. Writing rows is what this
	 * pipeline is for, but altering someone else's tables is a different kind of act — the
	 * sink may be owned by another team, under migration control, or shared with readers
	 * that a new column breaks. Inferring DDL from a fingerprint diff is also the least
	 * certain thing here: logical decoding carries no DDL, so a rename is indistinguishable
	 * from a drop plus an add and there is no backfill for a new column. Turning this on is
	 * a deliberate statement that the sink is this pipeline's to reshape.
	 *
	 * <p>
	 * With {@code none} the sink is still checked, read-only, before any rows are applied:
	 * a missing table or a missing {@code ON CONFLICT} target fails immediately with the
	 * cause named, rather than surfacing mid-apply as an opaque SQLSTATE.
	 */
	private SchemaEvolution schemaEvolution = SchemaEvolution.none;

	/**
	 * Create the sink table from the record schema when it does not exist. Subordinate to
	 * {@link #schemaEvolution}: creating a table is a schema change, so this does nothing
	 * unless evolution is enabled.
	 */
	private boolean autoCreateTables = true;

	/**
	 * Issue {@code DROP COLUMN} when a column disappears from the source. Off by default:
	 * a column drop is unrecoverable, and leaving the column in place and nullable is
	 * almost always the safer default.
	 */
	private boolean allowColumnDrop;

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
	 * Record every DDL statement this pipeline issues against the sink in
	 * {@code schemaAuditTable}.
	 *
	 * <p>
	 * Purely an audit: nothing reads it back, and turning it off changes no behaviour.
	 * It is on by default because the information is otherwise unrecoverable. Logical
	 * decoding carries no DDL, so a sink column is the result of a fingerprint diff
	 * computed once by a process that has since restarted — after the fact, neither the
	 * source nor the sink can say what the change was or when it happened, and the only
	 * other trace is a log line.
	 */
	private boolean schemaAudit = true;

	/**
	 * Table holding that trail, created in {@code applyStateSchema} alongside the
	 * watermark table.
	 */
	private String schemaAuditTable = "cdc_schema_audit";

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
	 * carries no origin at all and is indistinguishable from an ordinary application
	 * write.
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
	 * Set against the sink ({@code spring.datasource}), since the origin is session state
	 * on the writer. Requires {@code spring.datasource.hikari.maximum-pool-size=1},
	 * because an origin can only be active in one session at a time.
	 */
	private String applyOriginName;

	/**
	 * Size of the secondary pool used for schema reconciliation —
	 * {@code DatabaseMetaData} reads and DDL. Separate from the apply pool because that
	 * one is pinned to a single connection to hold the replication origin, and metadata
	 * work has no reason to queue behind row applies.
	 */
	private int metadataPoolSize = 2;

	public enum SchemaEvolution {

		none, basic

	}

	@PostConstruct
	void validate() {
		isTrue(batchSize > 0 && batchSize <= 10000, "consumer.batch-size must be between 1 and 10000");
		isTrue(flushIntervalMs > 50, "consumer.flush-interval-ms must be greater than 50");
		isTrue(drainIntervalMs <= 180000, "consumer.drain-interval-ms must be less than or equal to 180000");
		isTrue(transactionsPerCommit > 0, "consumer.transactions-per-commit must be greater than 0");
		isTrue(maxBufferedRows >= batchSize, "consumer.max-buffered-rows must be greater than consumer.batch-size");
		isTrue(metadataPoolSize >= 1, "consumer.metadata-pool-size must be at least 1");
	}

}
