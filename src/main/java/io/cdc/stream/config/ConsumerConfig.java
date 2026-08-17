package io.cdc.stream.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	private String applyOriginNamePrefix;

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
	 * Last-writer-wins conflict resolution.
	 *
	 * <p>
	 * {@code none} applies every change unconditionally — last <em>arrival</em> wins, which
	 * in a bidirectional topology lets two clusters diverge permanently when the same key is
	 * written on both sides.
	 *
	 * <p>
	 * {@code timestamp} applies a change only when the sink row is older, compared on a
	 * column the table already has. That works precisely because the column is <em>in the
	 * row</em>: a local write on the sink maintains it just as a replicated write does, so
	 * unlike any out-of-band version store it needs no trigger. It rests on the application
	 * genuinely setting that column on every update — a {@code DEFAULT now()} fires only on
	 * insert.
	 */
	private ConflictResolution conflictResolution = ConflictResolution.none;

	/**
	 * Candidate version columns, tried in order. The first that exists on a table with a
	 * time-like type becomes that table's guard; a table matching none is applied
	 * unconditionally, and says so at startup.
	 */
	private List<String> conflictColumns = new ArrayList<>(
			List.of("updated_at", "modified_at", "last_modified", "updated_on"));

	/** Per-table escape hatch, keyed {@code schema.table}, for tables naming it otherwise. */
	private Map<String, String> conflictColumnOverrides = new LinkedHashMap<>();

	/**
	 * Record rejected changes in {@code conflictLogTable} rather than only logging them.
	 *
	 * <p>
	 * A rejection is a real write that lost — someone's edit was discarded. Off, that fact
	 * survives only as a log line; on, it is queryable and can be reconciled. Note this is a
	 * conflict <em>log</em>, not a dead-letter queue: nothing here should be replayed, since
	 * last-writer-wins already decided.
	 */
	private boolean conflictLog;

	/** Created in {@code applyStateSchema} alongside the watermark table. */
	private String conflictLogTable = "cdc_conflict_log";

	/**
	 * How to settle a tie, when the incoming change and the sink row carry the same version.
	 *
	 * <p>
	 * Ties are not a curiosity; left unsettled they diverge permanently. Each side rejects
	 * the other's change and keeps its own, so the two clusters end up holding different
	 * values with no further events to reconcile them. Flipping the comparison to accept
	 * ties diverges just as badly, in the other direction.
	 *
	 * <p>
	 * {@code nodeId} settles it with a rule both sides evaluate to the same answer: the
	 * change from the higher node id wins. Because the identifiers are fixed and differ, one
	 * side accepts the tie and the other rejects it, and both converge on the same value.
	 *
	 * <p>
	 * This assumes a <em>pairwise</em> topology. Settling ties among three or more writers
	 * needs the originating node in the change event itself, which nothing here carries.
	 */
	private ConflictTiebreak conflictTiebreak = ConflictTiebreak.none;

	/** This deployment's identifier. Required when {@link #conflictTiebreak} is on. */
	private String conflictNodeId;

	/** The other deployment's identifier. Must differ from {@link #conflictNodeId}. */
	private String conflictPeerId;

	/** See {@link #conflictTiebreak}. */
	public enum ConflictTiebreak {

		/** Leave ties to the comparison, which means they diverge. */
		none,
		/** The change from the higher node id wins. */
		nodeId

	}

	/**
	 * Whether an incoming change should win a tie, which is exactly whether the comparison
	 * is {@code <=} rather than {@code <}. The strictness of the guard <em>is</em> the
	 * tiebreak — no extra predicate, no extra bind.
	 */
	public boolean incomingWinsTies() {
		return conflictTiebreak == ConflictTiebreak.nodeId && conflictPeerId != null && conflictNodeId != null
				&& conflictPeerId.compareTo(conflictNodeId) > 0;
	}

	/** See {@link #conflictResolution}. */
	public enum ConflictResolution {

		/** Apply everything; last arrival wins. */
		none,
		/** Apply only when the sink row is older, on a time-like column. */
		timestamp

	}

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
		isTrue(sinkRecheckIntervalMs > 0, "consumer.sink-recheck-interval-ms must be greater than 0");
		if (conflictTiebreak == ConflictTiebreak.nodeId) {
			isTrue(conflictNodeId != null && !conflictNodeId.isBlank() && conflictPeerId != null
					&& !conflictPeerId.isBlank(),
					"consumer.conflict-tiebreak=nodeId requires both consumer.conflict-node-id and "
							+ "consumer.conflict-peer-id");
			isTrue(!conflictNodeId.equals(conflictPeerId),
					"consumer.conflict-node-id and consumer.conflict-peer-id must differ, or both sides would settle "
							+ "a tie the same way and still diverge");
		}

	}

}
