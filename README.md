# spring-boot-cdc-stream
[Spring Boot](https://spring.io/projects/spring-boot) and embedded [debezium](https://debezium.io/) based CDC solution for [postgres](https://www.postgresql.org/docs/) and [yugabytedb](https://docs.yugabyte.com/)

Implementing CDC using Spring Boot, Debezium's embedded engine, and YugabyteDB offers a compelling strategy for real-time data synchronization without the complexities of a dedicated middleware like Kafka. This approach provides several key advantages, including simplicity in architecture and deployment, reduced operational overhead, direct integration of change events within the application logic, and the ability to leverage YugabyteDB's strong compatibility with PostgreSQL. The PostgreSQL compatibility of YugabyteDB plays a crucial role in making this approach accessible and efficient for a lot of tools to easily integrate with YugabyteDB if they already support PostgreSQL as a source or target. It is important to emphasize that while this middleware-less approach offers considerable benefits for specific use cases, the optimal choice of CDC strategy should always be driven by the particular requirements of the application. Factors such as the need for high scalability, robust fault tolerance, broad event distribution, and integration with a diverse range of external systems will influence whether this direct integration is the most suitable solution. However, for scenarios prioritizing simplicity, low latency, and reduced operational complexity, this combination presents a powerful and efficient way to achieve real-time data synchronization.

![cdc](assets/cdc.jpg)

## Prerequisites
Before you begin, ensure you have the following installed:
- Java Development Kit (JDK) 21 or higher: [Download JDK](https://sdkman.io/jdks)
- Apache Maven: [Download Maven](https://maven.apache.org/download.cgi)
- Git: [Install Git](https://git-scm.com/downloads)
- YugabyteDB: [Install YugabyteDB](https://docs.yugabyte.com/stable/reference/configuration/yugabyted/)
- YugabyteDB Debezium [Connector](https://github.com/yugabyte/debezium/releases/tag/dz.2.5.2.yb.2024.2.3)

## Get Started
You can find the complete source at [GitHub](https://github.com/srinivasa-vasu/spring-boot-cdc-stream.git). 

## Step 1: Clone the Repository

```sh
git clone [REPO]

cd spring-boot-cdc-stream
```

## Step 2: Install YBDB Debezium connector

```sh
# download the ybdb connector jar
wget https://github.com/yugabyte/debezium/releases/download/dz.2.5.2.yb.2025.2.3/yugabytedb-source-connector-dz.2.5.2.yb.2025.2.3-jar-with-dependencies.jar

# create ybdb pom
cat > debezium-yugabyte-2.5.2.Final.pom << EOF
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-yugabyte</artifactId>
    <version>2.5.2.Final</version>
    <description>YugabyteDB Debezium Connector</description>
    <packaging>jar</packaging>
</project>
EOF

# install connector to the local repo
mvn install:install-file \
  -Dfile=yugabytedb-source-connector-dz.2.5.2.yb.2025.2.3-jar-with-dependencies.jar \
  -DpomFile=debezium-yugabyte-2.5.2.Final.pom \
  -DgroupId=io.debezium \
  -DartifactId=debezium-yugabyte \
  -Dversion=2.5.2.Final \
  -Dpackaging=jar

```

## Step 3: Configure the Application

Update the `application-[profile].yml` file located in `src/main/resources/` with producer, consumer and datasource connection details.

## Step 4: Build and Run the Application
```sh
mvn -DskipTests -Dspring-boot.run.profiles=[REPLACE_PROFILE] clean install

mvn -DskipTests -Dspring-boot.run.profiles=[REPLACE_PROFILE] spring-boot:run 
```

## Step 5: Verify CDC Functionality

To test the CDC pipeline:
- Insert or update data in the source database.

## How the apply side works

Changes are applied by a single-threaded, schema-driven path. There are no per-table queues
and no worker pool: the replication slot already delivers changes in commit order and the
embedded engine invokes the consumer on one thread, so concurrency on the apply side would
only discard a guarantee that already exists. Statements are generated from each record's
own Connect schema and cached, which is what allows a new source column to flow through
without a code change.

Throughput comes from batching and coarser commits, not parallelism. Adjacent rows that
share a statement are coalesced into one JDBC batch — never reordered — and
`consumer.transactions-per-commit` groups several adjacent source transactions into one sink
commit.

### Commit granularity

`consumer.enable-transaction-boundary` selects what commits as one unit:

| | `false` | `true` |
|---|---|---|
| Source order | preserved | preserved |
| Commit unit | `batch-size` rows | one source transaction |
| Partial transaction visible in the sink? | yes | **no** |
| Requires `producer.provide-transaction-metadata` | no | **yes** |

With the flag on, rows are buffered until the transaction's END marker and committed
together, so a reader of the sink never observes half a source transaction. A transaction
that spans several engine batches stays buffered; its offsets are never marked, so an
incomplete transaction is re-delivered rather than half-applied. The initial snapshot emits
no transaction markers and falls back to size-based flushing.

Offsets advance only after the rows have committed. Alongside them, an LSN watermark is
written to `consumer.apply-state-table` **inside the same transaction as the rows**, so a
replay after a crash is recognised and skipped — Debezium's own offset store is flushed
asynchronously and always lags the apply.

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
| Every event column exists in the sink | see below — a warning by default, not a failure |
| Sink column can store the incoming values | otherwise truncation or out-of-range, thousands of rows in |
| A unique constraint matches the key columns | otherwise `42P10` — reported as a syntax-class error against SQL that is well formed |

Spring folds the SQLSTATE cases into `BadSqlGrammarException`, so the generated SQL rarely
identifies the cause on its own. Failing up front names the table, the columns, and what to
do. A sink column that is deliberately *wider* than the source is fine and is not reported.

The check is not retryable — no amount of waiting adds a missing column — so it halts the
pipeline with `UnrecoverableApplyException` rather than burning through the backoff.

**A source column the sink lacks is handled per row, not per schema.** With
`consumer.unknown-columns: skipIfNull` (the default) the column is left out of the write
while its value is null, and the first non-null value stops the pipeline naming the column
and the key. That is the distinction the schema-level check cannot make — it never sees a
value, so on its own it could only assume the worst and halt everything over a column that
may never carry anything. A source column added ahead of its sink migration is routine and
temporary; losing data is not, so neither is tolerated silently. Set `unknown-columns: fail`
to refuse the table outright instead. A missing **key** column stays fatal either way —
there is nothing to target the row with.

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