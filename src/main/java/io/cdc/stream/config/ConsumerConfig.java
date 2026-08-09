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
 * The apply path is always single-threaded and always preserves the order it received.
 * {@link TransactionScope} selects <em>what</em> gets committed as one unit — see that
 * enum, and the ingestion-side note in {@code KafkaChangeEventListener} about which scopes
 * a given Kafka topology can actually support.
 */
@Configuration
@ConfigurationProperties(prefix = "consumer")
@Setter
@Getter
public class ConsumerConfig {

	/**
	 * What commits as one sink transaction.
	 *
	 * <p>
	 * {@code global} is the strongest and the most demanding: it needs BEGIN/END markers
	 * delivered in the same ordered stream as the rows. The embedded engine gives that for
	 * free. On Kafka it holds only if the transaction topic is funnelled into the same
	 * single partition as the data, because nothing co-orders separate partitions.
	 *
	 * <p>
	 * {@code table} is the option that works on ordinary per-table topics. It uses no
	 * markers: every row carries its own {@code txId}, so a change of transaction or of
	 * table ends the unit. A source transaction spanning three tables becomes three sink
	 * transactions — each one atomic, none of them exposing a partial table — which is the
	 * most Kafka can offer without cross-partition reassembly.
	 */
	private TransactionScope transactionScope = TransactionScope.none;

	/**
	 * Number of adjacent source transactions to group into one sink commit. Raising this
	 * trades commit granularity for throughput without ever exposing a partial source
	 * transaction. Only meaningful when {@code transactionScope} is {@code global}.
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
	 * {@link OriginNames} — with one name per apply lane, and how many lanes it runs is its
	 * business. Configure the family prefix once and every lane is covered, so the peer
	 * adding a lane cannot silently start leaking changes back.
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
	 * This is the <em>prefix</em> of the actual origin, not the origin itself:
	 * {@link OriginNames} composes {@code <prefix>_<pipeline>_<lane>} so that the pipeline
	 * identities disambiguate two deployments sharing a sink, and each apply lane gets its
	 * own name. With a single lane the claimed origin is {@code <prefix>_<pipeline>_1}.
	 *
	 * <p>
	 * Set against the sink ({@code spring.datasource}), since the origin is session state
	 * on the writer. Requires {@code spring.datasource.hikari.maximum-pool-size=1}, because
	 * an origin can only be active in one session at a time.
	 */
	private String applyOriginName;

	/**
	 * Size of the secondary pool used for the sink precondition check —
	 * {@code DatabaseMetaData} reads. Separate from the apply pool because that one is
	 * pinned to a single connection to hold the replication origin, and metadata reads
	 * have no reason to queue behind row applies.
	 */
	private int metadataPoolSize = 2;

	/** What commits as one sink transaction. See {@link #transactionScope}. */
	public enum TransactionScope {

		/** Ordered batches of {@code batchSize} rows. A transaction may be split. */
		none,
		/** One whole source transaction, across every table it touched. Needs markers. */
		global,
		/** One source transaction's changes to one table. Needs no markers. */
		table

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
