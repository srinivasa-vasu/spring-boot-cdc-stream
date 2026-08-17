# spring-boot-cdc-stream

A change-data-capture **sink** for YugabyteDB, built on Spring Boot and Kafka. It consumes
Debezium change events from Kafka topics and applies them to a target YugabyteDB cluster.

The source half runs in Kafka Connect. This application holds no source-side configuration
at all — no replication slot, no publication, no Debezium offset store — and does one thing:
turn change events back into rows, in order, transactionally.

What it is careful about:

- **Order is never re-derived.** Kafka preserves it per partition and the apply path never
  reorders what it receives; batching only ever coalesces *adjacent* rows.
- **You choose what commits together.** `consumer.transaction-scope` selects ordered batches,
  one whole source transaction, or one transaction's changes to one table — the last being
  the strongest guarantee ordinary per-table topics can support.
- **The sink is the authority on progress.** Applied offsets are written *inside* the row
  transaction, and the consumer seeks to them on assignment rather than trusting Kafka's
  committed offset, which makes replay after a crash bounded and exact.
- **Data only, never DDL.** The sink's schema is yours to migrate; the pipeline verifies
  read-only that it can accept the rows and says precisely what is missing when it cannot.
- **Bidirectional-safe.** Writes are tagged with a replication origin so a peer can tell this
  pipeline's applies from real user writes and refuse to send them back.

Parallel apply is available for table scope: each listener thread gets its own connection,
transaction and replication origin.

> An embedded-engine variant — Debezium in-process, no Kafka required — is on the
> `feat/cdc-txn-apply` branch.

## Prerequisites
- Java Development Kit (JDK) 21 or higher: [Download JDK](https://sdkman.io/jdks)
- Apache Maven: [Download Maven](https://maven.apache.org/download.cgi)
- YugabyteDB: [Install YugabyteDB](https://docs.yugabyte.com/stable/reference/configuration/yugabyted/)
- A Kafka cluster and a Kafka Connect worker
- YugabyteDB Debezium [Connector](https://github.com/yugabyte/debezium/releases), installed
  into the **Connect worker** — this application no longer embeds it

## Architecture

The source half runs in Kafka Connect; this application is the sink half only.

```
YugabyteDB ──▶ Kafka Connect ──▶ Kafka topics ──▶ this app ──▶ YugabyteDB
  (source)     (YB connector)                     (apply)        (sink)
```

Connect owns the replication slot, the publication and its own offsets. This application
consumes topics and applies rows, and holds no source-side configuration at all.

## Step 1: Configure the connector

Publish per-table topics as `<prefix>.<schema>.<table>`, with a converter that keeps the
Connect schema on the wire:

```properties
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=true
key.converter=org.apache.kafka.connect.json.JsonConverter
key.converter.schemas.enable=true
provide.transaction.metadata=true
```

Or Avro against a registry:

```properties
value.converter=io.confluent.connect.avro.AvroConverter
value.converter.schema.registry.url=http://localhost:8081
key.converter=io.confluent.connect.avro.AvroConverter
key.converter.schema.registry.url=http://localhost:8081
```

A schema must arrive either way. The apply side derives sink types from it — `TypeMapper`
dispatches on Debezium logical names like `io.debezium.time.MicroTimestamp`, and
`RecordConverter` needs it to spot the `yboutput` `{value, set}` column envelope. Both
converters preserve those names: JSON carries them inline, Avro round-trips them through
`connect.name`. Schemaless JSON is the one option that cannot work.

## Step 2: Configure this application

Edit `src/main/resources/application-[profile].yml`: the sink datasource under `spring`,
ingestion under `kafka`, and apply behaviour under `consumer`.

## Step 3: Build and run

```sh
mvn -DskipTests -Dspring-boot.run.profiles=[REPLACE_PROFILE] clean install

mvn -DskipTests -Dspring-boot.run.profiles=[REPLACE_PROFILE] spring-boot:run
```

The sink tables must already exist with a primary key or unique constraint on the same key
columns — this pipeline issues no DDL. `SinkPrecondition` checks that up front and names
what is missing.

## How the apply side works

Changes are applied by a schema-driven path with no per-table queues and no worker pool.
Order comes from the transport, not from re-sorting: Kafka preserves it per partition, and
the apply side never reorders what it receives. Statements are generated from each record's
own Connect schema and cached, which is what allows a new source column to flow through
without a code change.

Throughput comes from batching and coarser commits, not parallelism. Adjacent rows that
share a statement are coalesced into one JDBC batch — never reordered — and
`consumer.transactions-per-commit` groups several adjacent source transactions into one sink
commit.

### Commit granularity

`consumer.transaction-scope` selects what commits as one sink transaction:

| | `none` | `table` | `global` |
|---|---|---|---|
| Source order | preserved | preserved | preserved |
| Commit unit | `batch-size` rows | one transaction's changes to one table | one whole source transaction |
| Partial transaction visible in the sink? | yes | per table only | **no** |
| Needs BEGIN/END markers | no | no | **yes** |

`global` buffers until the transaction's END marker, so a reader never observes half a
source transaction. A transaction spanning several delivery batches stays buffered and is
never acknowledged, so an incomplete one is re-delivered rather than half-applied. The
initial snapshot emits no markers and falls back to size-based flushing.

`table` needs no markers at all — every row carries its own `txId`, so a change of
transaction or of table ends the unit. A transaction touching three tables becomes three
sink transactions, each atomic. This exists because it is the strongest guarantee ordinary
per-table Kafka topics can support.

Offsets advance only after the rows have committed. Alongside them an LSN watermark is
written to `consumer.apply-state-table` **inside the same transaction as the rows**, so a
replay after a crash is recognisable — Debezium's own offset store is flushed asynchronously
and always lags the apply.

### Ingestion and ordering

**Ordering is a property of the topology, not of the consumer.** Kafka gives per-partition
order and nothing more, so which `transaction-scope` values are actually available depends
on how the connector publishes:

| Topology | Supported scope |
|---|---|
| Per-table topics (Debezium default) | `none`, `table` |
| All tables **and** the transaction topic funnelled into one single-partition topic | `none`, `table`, `global` |

The funnel is a `ByLogicalTableRouter` SMT with `topic.regex: (.*)`. Transaction markers
survive it — the SMT reroutes non-envelope records with their value untouched — which is
what puts BEGIN/data/END back into one ordered stream. Create the target topic with
`partitions=1`, and either leave the producer idempotent (the Kafka 3.x default) or set
`max.in.flight.requests.per.connection=1`, or a retry reorders within the partition and
nothing tells you.

A single partition costs less than it looks: `ChangeEventApplier` is single-threaded by
design, so the applier is already the ceiling.

**Offsets are stored in the sink.** `KafkaOffsetStore` writes `(consumer_group, topic,
partition, offset)` inside the row transaction, and the consumer seeks to that on
assignment rather than trusting Kafka's committed offset. The sink therefore cannot
disagree with its own progress record, and replay after a crash or rebalance is bounded and
exact. This is a better restart key than the LSN watermark beside it — a topic offset has
none of the slot-recreation hazards that make `skip-applied-lsn` unsafe to enable.

**Both JSON and Avro are supported**, selected by `kafka.converter`. The format never
reaches the apply side — both yield a Connect `Struct` and `Schema`, and everything below
the converter works off that — so the choice is about the wire, not correctness:

| | JSON + `schemas.enable=true` | Avro + registry |
|---|---|---|
| Payload | Whole schema in **every message**, commonly 5–10× on a wide table | 5-byte header + compact binary |
| Effective batch | Fat records may not fill `max-poll-records` within `max.partition.fetch.bytes`, so commit units shrink | 500 records fits comfortably |
| Incompatible source change | Surfaces late, as a halted pipeline from `SinkPrecondition` | Rejected at the connector, before publish |
| Operational | No registry to run or depend on | Registry must be up to produce and consume |

Avro is not bundled by default — its dependency tree is large and a JSON deployment has no
use for it. Build with `-Pavro` and set `kafka.converter: avro` plus
`kafka.schema-registry-url`. `ConverterFactory` loads converters by class name the way
Connect does, so any other `Converter` implementation can be named directly, with
`kafka.converter-properties` passed through for registry credentials and subject-naming
strategies.

**Topics can be discovered dynamically.** Subscription is always by pattern, so
`kafka.topic-pattern: "ybdb\\.public\\..*"` picks up a newly captured table without a
restart — Debezium names per-table topics `<prefix>.<schema>.<table>`. Anchor it at the
schema: a bare `ybdb\..*` would also pull in `ybdb.transaction`, whose markers mean nothing
once partitions are not co-ordered. Discovery costs a metadata refresh
(`kafka.metadata-max-age-ms`, five minutes by default) and triggers a rebalance, so it is
eventual rather than immediate. An explicit `kafka.topics` list still works and is quoted
into an equivalent exact pattern.

**Replication origins are named per lane.** `consumer.apply-origin-name` is a *prefix*:
`OriginNames` composes `<prefix>_<slot-or-consumer-group>_<lane>`, so a single-lane pipeline
claims `cdc_apply_ybdb_1`. The pipeline component stops two deployments sharing a sink from
colliding, and the lane component is what parallel apply will need — origins are held by one
session at a time, so each lane needs its own. The names are derived rather than random, so
a restart reuses the origins it already registered instead of leaking a permanent catalog
row each time. On the read side `consumer.ignore-origins` matches **prefixes**, so naming
the family `cdc_apply_ybdb` once covers every lane the peer runs.

**Parallel apply, for `table` scope only.** `kafka.concurrency` gives each listener thread
its own *apply lane*: a pool of exactly one connection, its own replication origin, and its
own transaction. One connection per lane rather than one pool of N is forced by how the
origin is claimed — Hikari's `connectionInitSql` is a single static string, so every
connection in a pool would claim the same origin and only the first could succeed.

Lanes are rejected with `global` scope, where one partition means one thread could ever
hold an assignment anyway, and with `skip-applied-lsn`, whose single-row watermark no longer
describes independently advancing lanes. With more than one lane the LSN watermark is not
written at all; the per-partition Kafka offsets carry restart state, and they are already
keyed per partition so lanes never contend.

`kafka.concurrency` bounds **threads, not topics** — subscribe to as many topics as you
like, and their partitions are shared across the lanes. It is capped at 16, which is a
backstop against a typo rather than a target: every lane holds a sink connection and
registers a permanent replication origin, and lanes beyond the assigned partition count only
idle. Match it to how much parallelism the sink can absorb, not to the table count.

What you give up is cross-table ordering. Each table stays ordered within its partition, and
that is what `table` scope already concedes.

**Deployment: use a StatefulSet, not a Deployment.** Two independent reasons, both about
the replication origin:

- **Stable identity.** `kafka.instance-id` becomes part of the origin name, and origins are
  permanent catalog rows that nothing drops. A StatefulSet pod keeps its name across
  restarts (`cdc-apply-0`); a Deployment pod gets a fresh random name each time, leaking an
  origin family per restart.
- **No overlap.** A StatefulSet's `RollingUpdate` terminates a pod before recreating the
  same ordinal, so one origin is never claimed twice. A Deployment's `RollingUpdate` starts
  the new pod first, and the two fight over the origin — which is why a Deployment would
  need `strategy: Recreate`.

Offsets are deliberately **not** keyed per instance — `cdc_kafka_offsets` uses
`(consumer_group, topic, partition)`, because a partition moves between instances on a
rebalance and its new owner must read where the previous one got to.

### Kubernetes manifest

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: cdc-apply
spec:
  serviceName: cdc-apply
  # >1 only with consumer.transaction-scope=table. Global scope needs a single
  # partition, so a second instance would sit idle holding an origin.
  replicas: 2
  podManagementPolicy: Parallel      # each ordinal has its own origin, so no ordering needed
  selector:
    matchLabels: { app: cdc-apply }
  template:
    metadata:
      labels: { app: cdc-apply }
    spec:
      # consumer.drain-interval-ms is 30s; the K8s default grace period is also 30s,
      # which leaves no room for the buffer to be discarded cleanly.
      terminationGracePeriodSeconds: 60
      containers:
        - name: cdc-apply
          image: your-registry/cdc-apply:latest
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: cloud
            # THE ONE THING THIS SECTION EXISTS FOR. Resolves to cdc-apply-0,
            # cdc-apply-1, ... — stable for the life of the ordinal, which is what
            # makes the derived origin name reusable across restarts.
            - name: KAFKA_INSTANCE_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: kafka:9092
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef: { name: cdc-apply-sink, key: password }
          # No actuator dependency yet, so this is a TCP probe on the web port.
          # Add spring-boot-starter-actuator for a real /actuator/health check.
          readinessProbe:
            tcpSocket: { port: 8080 }
            initialDelaySeconds: 20
          resources:
            requests: { cpu: "500m", memory: "1Gi" }
```

`KAFKA_INSTANCE_ID` is the only variable this feature requires — everything else is ordinary
Spring Boot relaxed binding (`KAFKA_BOOTSTRAP_SERVERS` → `kafka.bootstrap-servers`, and so
on), so any property can be overridden the same way.

Two things to size against the sink, since they are per pod and multiply by `replicas`:

| | |
|---|---|
| Connections | `kafka.concurrency` apply lanes (one connection each) + `consumer.metadata-pool-size` (default 2) |
| Replication origins | `kafka.concurrency` per pod, permanently registered, named `<prefix>_<group>_<instance>_<lane>` |

So `replicas: 2` with `concurrency: 4` is 12 connections and 8 origins against the sink.

### Schema changes are not replicated

This pipeline replicates **data**. It issues no DDL against the sink, ever, and the sink's
schema is expected to be managed out of band — by your migration tool, or by whoever owns the
target. Source and sink must be migrated in a compatible order: add the column to the sink
before the source starts sending it.

That is a deliberate boundary rather than a missing feature. PostgreSQL and YugabyteDB
logical decoding emit no DDL events, so the only way to infer a schema change here is to diff
each change event's Connect schema against the sink — an inference that cannot tell a rename
from a drop plus an add, cannot backfill a new column with the source's default, and would be
reshaping tables this pipeline does not own.

What it does do, once per table per distinct schema shape, is verify read-only that the sink
can accept the rows, before any are written:

| Checked | Why it is worth failing early |
|---|---|
| Table exists | otherwise `42P01`, from deep inside the first batch |
| Every event column exists in the sink | otherwise a column silently absent from the write |
| Sink column can store the incoming values | otherwise truncation or out-of-range, thousands of rows in |
| A unique constraint matches the key columns | otherwise `42P10` — reported as a syntax-class error against SQL that is well formed |

Spring folds the SQLSTATE cases into `BadSqlGrammarException`, so the generated SQL rarely
identifies the cause on its own. Failing up front names the table, the columns, and what to
do. A sink column that is deliberately *wider* than the source is fine and is not reported.

The check is not retryable — no amount of waiting adds a missing column — so it halts the
pipeline with `UnrecoverableApplyException` rather than burning through the backoff.

### Conflict resolution

Off by default. With `consumer.conflict-resolution: none` every change is applied
unconditionally — **last arrival wins**. In a bidirectional topology that means two clusters
writing the same key concurrently end up holding different values, permanently: each applies
the other's change last, and no further events exist to reconcile them.

`timestamp` applies a change only when the sink row is older, compared on a column the table
**already has**:

```yaml
consumer:
  conflict-resolution: timestamp
  conflict-columns: [updated_at, modified_at, last_modified, updated_on]
  conflict-column-overrides:
    public.orders: modified_on
```

This works with no schema change and no trigger because the column is **in the row**. A local
write on the sink maintains it exactly as a replicated write does, so the comparison always
reads the row's real current version. Every out-of-band alternative — a version column the
pipeline owns, a side table keyed by row — is blind to direct local writes and would need a
trigger on every replicated table to close that hole.

Identifying the column per table costs nothing. `SinkPrecondition` already reads
`DatabaseMetaData.getColumns` for its own checks and caches the result, so the first candidate
present with a time-like type is picked from data already in hand. Each table logs which
column guards it, and a table matching none logs that it is applied unconditionally — "some
tables are guarded" is a very different posture from "all are", and the difference is
otherwise invisible.

The guard becomes part of the statement:

```sql
-- upsert: no extra bind, the incoming value is already being inserted
DO UPDATE SET ... WHERE "orders"."updated_at" < EXCLUDED."updated_at"
-- partial update: version bound as a parameter
UPDATE ... WHERE "id" = ? AND "updated_at" < ?
-- delete: never strict
DELETE ... WHERE "id" = ? AND "updated_at" <= ?
```

Delete is deliberately non-strict. The before image carries the version the *deleter* saw, so
a sink row that has moved on since holds changes the deleter did not know about and survives;
one that is identical has not, and goes.

**With parallel lanes this needs no extra coordination.** Kafka partitions by message key, so
a given key always lands on one partition, one listener thread and therefore one apply lane —
two lanes never contend for the same row. The comparison is a single compare-and-apply
statement in any case, so it is atomic even where that affinity does not hold.

#### Ties

Equal versions diverge if left alone — each side rejects the other's change and keeps its own.
Flipping the comparison to accept ties diverges just as badly, in the other direction.

`conflict-tiebreak: nodeId` settles it with a rule both sides evaluate to the same answer: the
change from the higher node id wins. Give each deployment a distinct id, **mirrored** — see
`application-a2b.yml` and `application-b2a.yml`, which are exactly this pair:

```yaml
# a2b                          # b2a
conflict-tiebreak: nodeId      conflict-tiebreak: nodeId
conflict-node-id: a            conflict-node-id: b
conflict-peer-id: b            conflict-peer-id: a
```

Identical ids are rejected at startup. **Un-mirrored ids are not**, and cannot be: from either
side in isolation `node=a, peer=b` is legitimate config, and nothing compares the two
deployments. Getting this wrong has both sides settle ties the same way and diverge anyway, so
it is worth checking by hand.

This is **pairwise only**. Settling ties among three or more writers needs the originating node
in the change event, which nothing in the payload carries.

#### Rejected changes

A rejection is a real edit being discarded. Even when the decision was right, that fact is
often business-relevant, so it is never silent:

| `conflict-log` | Behaviour |
|---|---|
| `false` (default) | Rate-limited warning, one per 1000 rejections |
| `true` | A row in `consumer.conflict-log-table` with the key, the full payload, the incoming version, the reason, and the source transaction |

It is a conflict **log**, not a dead-letter queue — nothing there should be replayed, since
last-writer-wins already adjudicated and re-applying a loser would undo the winner. Retention
is safe to prune: unlike a version store, nothing in it is load-bearing for correctness. The
row is written through the lane's own connection, inside the row transaction, so a rolled-back
apply cannot leave a rejection recorded for something that never happened.

Two reasons are recorded. `stale` means the guard refused it; `row_missing` means there was no
row to update, which was previously only a log line. An upsert can never be `row_missing` — it
would have inserted — so that case is decided without a probe.

Rejections are identified from the JDBC batch's per-row affected counts, which is exact rather
than inferred. That takes one deliberate concession: a guarded upsert is generated with a
`RETURNING` clause whose rows nobody reads. Its purpose is to make the statement ineligible for
`reWriteBatchedInserts`, because a rewritten batch collapses into a single statement and reports
`SUCCESS_NO_INFO` instead of counts. Guarded tables therefore give up the multi-row-insert
optimisation — but they keep the batch, so it is still one round trip, not one per row.

The alternative of reading the sink afterwards does not work, and the reason is worth stating
because it looks like it should. Once the batch has run, a row that applied and a row that lost
a tie **both** leave the sink holding the incoming version; they differ only in data the
comparison never sees. There is no query that separates them, so the decision has to be captured
at apply time or not at all.

Only the two ambiguous cases still probe: a partial update or a delete that affected nothing may
have been refused by the guard or may have had no row to act on. That probe is **read-only** —
replaying a row that lost a tie would re-apply it and undo the decision, so the measurement must
not be able to change the outcome.

#### What you are assuming

- **The application sets the column on every UPDATE.** A `DEFAULT now()` fires only on insert.
  A code path that forgets leaves the version frozen and lets a stale change win.
- **Clocks are close enough.** The comparison is wall-clock across clusters, bounded by skew —
  the same basis xCluster uses for its own last-writer-wins.
- **Deletes can still resurrect.** Once a row is gone so is its version, so a late-arriving
  older insert recreates it. Only soft deletes close that.
- **Partial images must carry the column.** Under `REPLICA IDENTITY CHANGE` an update that did
  not touch it has nothing to compare, and the guard is dropped for that statement. With
  `REPLICA IDENTITY FULL` on the connector this does not arise.

### Adding a table to the capture set

Only **streaming** works for a table added later. An existing slot will start delivering
changes to the new table, but it will never backfill the rows already in it, and neither
mechanism Debezium normally offers for that works against YugabyteDB logical replication:

- **`snapshot-mode: initial` does not help.** The snapshot is taken once, when the
  replication slot is created, and establishes that slot's consistent point. A table added to
  `table.include.list` afterwards is past that point, and offsets already exist, so nothing
  re-snapshots.
- **`signal.data.collection` does not work.** Incremental snapshots driven by a signalling
  table are not supported here, so `producer.signal-data-collection` will not backfill the
  table however it is configured.

To pick up a table that has no pre-existing rows:

1. Add it to `producer.table-list` and **restart** — Debezium reconciles the publication at
   connector startup only. 
2. Create the table in the **sink** first, with a primary key or unique constraint on the same
   key columns. This pipeline issues no DDL, so `SinkPrecondition` halts on the table's first
   event otherwise.

To backfill a table that already holds rows, run a **separate replication slot** — creating a
slot is what establishes a new consistent snapshot point, so a second deployment gets one:

1. Add the table to the main pipeline's `table-list` and restart it, so streaming for that
   table begins. Do this **first**.
2. Then start a second deployment with its own `replication-slot`, `publication-name` and
   `offset-storage-jdbc-table`, a `table-list` containing only the new table, and
   `snapshot-mode: initial`. Its slot creation snapshots the existing rows, after which it
   streams.
3. Once it has caught up, retire it. The main pipeline carries the table from then on.

The order matters. Starting the backfill slot first leaves a window between its snapshot point
and the main pipeline picking up the table, and changes in that window are lost. The order
above overlaps the two instead — and overlap is harmless, because every statement this
pipeline generates is idempotent (upsert by key, delete by key), so the same row applied twice
converges on the same state.
