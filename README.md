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
wget https://github.com/yugabyte/debezium/releases/download/dz.2.5.2.yb.2024.2.3/debezium-connector-yugabytedb-dz.2.5.2.yb.2024.2.3-jar-with-dependencies.jar

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
  -Dfile=debezium-connector-yugabytedb-dz.2.5.2.yb.2024.2.3-jar-with-dependencies.jar \
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

### Schema changes (DDL)

PostgreSQL and YugabyteDB logical decoding emit no DDL events, so there is no DDL stream to
subscribe to. Instead, every change event carries its Connect schema, and that schema lists
all columns of the table regardless of which ones the row changed. A change in the derived
fingerprint is therefore a reliable signal that the source was altered, and diffing it
against the sink's real columns yields the DDL to apply.

DDL is **off by default** (`consumer.schema-evolution: none`), because altering the sink is a
different kind of act from writing rows to it — the sink may be owned by another team, under
migration control, or read by something a new column breaks — and because a fingerprint diff
is the least certain inference in this pipeline (see the two limits below). `none` still
verifies the sink read-only before the first batch of each table, so a missing table or a
missing `ON CONFLICT` target fails immediately with the cause named, rather than surfacing
mid-apply as `42P01` or `42P10` wrapped in `BadSqlGrammarException`.

Opting in with `consumer.schema-evolution: basic`, which is also the master switch for
`auto-create-tables` and `allow-column-drop`:

| Source change | Sink |
|---|---|
| `ADD COLUMN` | `ADD COLUMN` (nullable — existing sink rows have no value for it) |
| Lossless type widening (`int4`→`int8`, `varchar`→`text`, …) | `ALTER COLUMN ... TYPE` |
| Narrowing or incompatible type change | **pipeline halts** with the column named |
| `DROP COLUMN` | column kept, `NOT NULL` dropped (set `allow-column-drop` to propagate) |
| New table already in `table-list` | `CREATE TABLE` from the record schema, PK from the record key |

Halting on an unsafe change is deliberate: there is no automatic answer that is safe, and
stopping visibly beats truncating silently.

Two limits follow from having no DDL stream, and neither can be worked around from here.
A **rename** is indistinguishable from a drop plus an add, so the sink ends up with both
columns — the old one frozen at its last value, the new one NULL for every existing row.
And `ADD COLUMN` has **no backfill**: the column's source default is not propagated, so
until each row is next updated the sink reads NULL where the source reads the default. An
incremental snapshot is the only cure for either.

Every `CREATE` and `ALTER` issued against the sink is recorded in
`consumer.schema-audit-table`. Nothing reads it back — this is not Debezium's
`schema.history.internal.*`, which exists for connectors reading a schema-less physical log
and has no equivalent here. It is an audit trail, and it is on by default because the
information is otherwise unrecoverable: the fingerprint diff that produced a column happened
once, in a process that has since restarted, and neither the source nor the sink can
reconstruct it afterwards.

```sql
SELECT applied_at, change_type, statement
  FROM cdc_schema_audit
 WHERE table_name = 'orders'
 ORDER BY applied_at;
```

The entry is written after the DDL has run, and outside it — DDL auto-commits, so it can
never join the row transaction. A crash in between loses the entry, not the DDL.

### Adding a table to the capture set

Adding a table is not fully automatic, and the reasons are on the source side:

1. Add it to `producer.table-list` and **restart** — Debezium reconciles the publication at
   connector startup only.
2. `snapshot-mode: initial` does not re-snapshot once offsets exist, so a table that already
   holds rows needs an incremental snapshot. Create the signalling table and set
   `producer.signal-data-collection` (commented example in `application-local.yml`).
3. Check the replica identity. `replica.identity.autoset.values` is applied at startup, and
   in YugabyteDB the replica identity is captured when the replication slot is created — a
   table created afterwards picks up the server default instead, and an existing slot may
   need to be recreated to get `FULL`. Verify this against your YugabyteDB version.

The sink side needs no code change: the table is created from the record schema on first
event.


