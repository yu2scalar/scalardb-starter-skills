# ScalarDB 3.19.1 Reference — Design decision flow and anti-patterns

This file re-packages the facts of the other axes (10–60) as **material for guiding and warning a user at the moment a design
decision is made**. The flows below can be turned into **Q&A**, and the anti-patterns into **check rules**.

> **Why this axis exists**: a reference that is only "available" does not save beginners (they do not read it, or cannot
> decide from it). The frequent and expensive traps — range × index, cross-partition scan cost, isolation level vs
> `Consistency`, key design, the multi-process transaction APIs and the Coordinator table — must be removed
> **at design time, before code is written**.

Applies to ScalarDB 3.19.1. Official docs: `scalardb-cluster/deployment-patterns-for-microservices.mdx`,
`scalardb-cluster/api-guide.mdx` (Microservice Transaction API), `consensus-commit.mdx`, `database-adapters.mdx`,
`backup-restore.mdx`, `configurations.mdx`, `releases/release-notes.mdx`.

Fact base: the other files in this directory —
[`10-configuration-core.md`](./10-configuration-core.md), [`11-configuration-cluster.md`](./11-configuration-cluster.md),
[`20-data-model-and-types.md`](./20-data-model-and-types.md), [`30-crud-api-and-limits.md`](./30-crud-api-and-limits.md),
[`40-sql-support-and-limits.md`](./40-sql-support-and-limits.md), [`50-backend-feature-matrix.md`](./50-backend-feature-matrix.md),
[`60-error-codes.md`](./60-error-codes.md). Section references are written as `30 §4.3`.

### Legend

| Symbol / tag | Meaning |
|---|---|
| ✋ | A warning to show at that point of the flow |
| ✅ / ❌ | Supported / not supported (throws or is rejected) |
| `DB-CORE-1xxxx` etc. | Full error codes (`<component>-<category digit><4-digit id>`, 1 = USER, 2 = CONCURRENCY, 3 = INTERNAL, 4 = UNKNOWN status; → `60 §1`) |

### Terms that are easy to confuse

| Term | Meaning in this file |
|---|---|
| **Isolation level** vs **`Consistency`** | Isolation level (`SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED`, `scalar.db.consensus_commit.isolation_level`) controls visibility **between transactions**. `Consistency` (`SEQUENTIAL` / `EVENTUAL` / `LINEARIZABLE`) is the storage read/write consistency of **one non-transactional operation**; transactional CRUD ignores it (→ `30 §5.2`) |
| `scalar.db.jdbc.isolation_level` | The RDB connection isolation level of the JDBC pool — a third, unrelated setting (→ `10 §4.4`) |
| Global transaction / Microservice Transaction API | `GlobalTransactionManager` / `GlobalTransaction` / `BranchTransaction` (→ `30 §5.5`) |
| Shared-cluster pattern | All microservices use one ScalarDB Cluster; a `GlobalTransactionManager` without the Transaction Coordinator runs single-phase inside that cluster (`deployment-patterns-for-microservices.mdx:17`) |
| Separated-cluster pattern | Each microservice has its own ScalarDB Cluster; the **Transaction Coordinator (TC)** process drives two-phase commit across them (`deployment-patterns-for-microservices.mdx:50`, → `11 §9`) |
| 2PC interface | The old `TwoPhaseCommitTransactionManager` / `TwoPhaseCommitTransaction` API (deprecated; scheduled for removal in 3.20.0). Not the same as the internal `TwoPhaseCommitCoordinator` / `TwoPhaseCommitParticipant` primitives (→ `30 §1`) |
| Coordinator table | `coordinator.state`, the table that holds each transaction's final state (→ `20 §6.7`) |

---

## Part A. Design decision flows (for Q&A)

Each flow has the form "question to the user → branches → recommendation / warning".

### A1. Choosing the backend (storage)

```
Q: Which database will you use / can you use?
├─ A relational database is available (MySQL / PostgreSQL / Oracle / SQL Server / Db2 / MariaDB / TiDB / YugabyteDB / Spanner …)
│    → JDBC recommended. The only backend where cross-partition-scan ordering works (30 §4.2, 50 §1).
│      Suits applications with ranges and complex conditions.
│      Aurora: plain jdbc:mysql:/jdbc:postgresql:, or jdbc:aws-wrapper: for fast failover (see below).
├─ A managed NoSQL service is required (AWS / Azure / GCP)
│    → DynamoDB / Cosmos DB / Cassandra. Mind the NoSQL restrictions (warnings below).
├─ Object storage only (S3 / Azure Blob / Cloud Storage)
│    → Private Preview in 3.19; no secondary index at all (50 §1).
└─ Just trying it out / single node
     → JDBC (SQLite or PostgreSQL) or Cassandra are the easiest to start with. SQLite is for development only.
```

Warnings to show:

- ✋ **DynamoDB / Cosmos DB / Cassandra / Object Storage reject cross-partition-scan ordering**: turning on
  `scalar.db.cross_partition_scan.ordering.enabled` makes the storage fail at startup, and any `ScanAll` with orderings is
  rejected at run time (→ `50 §1`).
- ✋ **Cassandra cannot use `TIMESTAMP`** (without time zone); use `TIMESTAMPTZ` (`DB-CORE-10227`; → `20 §3.1`).
- ✋ **BLOB as a key differs per backend**: Cosmos DB and DynamoDB reject a BLOB clustering key; DynamoDB allows BLOB only as
  the last partition-key column; Oracle and Db2 reject BLOB keys and BLOB index columns (→ `20 §5`).
- ✋ **Object Storage has no secondary index** (`DB-CORE-10251`) and is **Private Preview** in 3.19 (`requirements.mdx:246`).
- ✋ **Key values, not only types, are restricted on NoSQL**: Cosmos DB rejects `: / \ # ?` in every primary-key TEXT value
  (clustering key included), Object Storage rejects `/` and `!`, DynamoDB rejects `\u0000` (→ `20 §5.1`).
- ✋ **BIGINT outside ±2^53 is rejected on Cosmos DB and Object Storage** (→ `20 §2.2`). Snowflake-style IDs need another backend.
- For the full capability differences (atomicity unit, secondary index, import, ALTER, backup, versions) see
  [`50-backend-feature-matrix.md`](./50-backend-feature-matrix.md). Key points for selection:
  - **DynamoDB is atomicity unit STORAGE with at most 100 mutations per `DistributedStorage.mutate` call**.
    **Transactions are not limited**: Consensus Commit splits its writes into batches of at most the storage's maximum atomic
    mutation count (→ `30 §6.1`).
  - **SQLite: development and testing only, no import, no type changes** (`database-adapters.mdx:113-114`).
  - **TiDB has no SERIALIZABLE**; per the docs ScalarDB uses REPEATABLE READ instead (`database-adapters.mdx:117-118`).
    ScalarDB does not check this at run time (→ `50 §2`).
  - **Spanner (JDBC) cannot rename columns or tables and allows only BLOB→TEXT type changes** (→ `50 §2`).
    `jdbc:spanner:` URLs are accepted in addition to `jdbc:cloudspanner:`.
  - **Backups need an explicit pause unless you use one database that supports transactions** (NoSQL, or more than one database)
    (`backup-restore.mdx:20-33`; → `50 §3`).
- ✋ **Aurora with the AWS Advanced JDBC Wrapper**: only `jdbc:aws-wrapper:postgresql:` /
  `jdbc:aws-wrapper:mysql:` are accepted; MariaDB URLs and a MySQL URL that turns out to be TiDB fail at startup with
  `DB-CORE-10302`. Non-Aurora RDS is not detected and is unsupported
  (`docs/configurations.mdx:430-434`). → AP-25.
- ✋ **HikariCP `exceptionOverrideClassName` makes every JDBC deployment fail at startup (`DB-CORE-10303`)**, also
  when it arrives through the `hikaricp.configurationFile` system property. → AP-24.

### A2. Choosing the transaction method (one process)

```
Q: What do you need to guarantee?
├─ Update several records / tables consistently
│    → DistributedTransaction (transaction_manager=consensus-commit). The default and most common choice (30 §1).
├─ Several services / processes must take part in one transaction (microservices)
│    → Go to A5. Do NOT start new code on the 2PC interface or join()/resume() (deprecated, scheduled for removal in 3.20.0).
├─ Only single-record, single-operation access (high throughput)
│    → single-crud-operation. No begin, one operation per mutate/batch (30 §5.4).
├─ One relational database only, and you want ScalarDB to use its native transaction
│    → transaction_manager=jdbc (no Consensus Commit metadata) (10 §2).
└─ No transaction guarantee needed, fastest raw access
     → DistributedStorage (non-transactional). No ACID; atomic only within the storage's atomicity unit (30 §1, §2.3).
```

Warnings:

- ✋ **When in doubt, use `consensus-commit`.** `single-crud-operation`, `jdbc` and `DistributedStorage` are optimizations to
  choose only after understanding their limits; do not make them a beginner default.
- ✋ The mode is fixed per process (and per Cluster). `scalar.db.consensus_commit.*` keys are silently ignored in the other
  modes (→ `10 §2`).
- ✋ `single-crud-operation` rejects `begin` / `resume` / `getState` / `rollback(txId)`,
  and `mutate` / `batch` take exactly one operation (`DB-CORE-10138` / `10242`).
- ✋ `transaction_manager=jdbc` and `single-crud-operation` reject `finishTransaction`, `recoverRecord`, the 2PC primitives
  and Global transactions (`DB-CORE-10289`–`10298`; → `30 §5.4`).
- ✋ **Group commit (`scalar.db.consensus_commit.coordinator.group_commit.enabled`) cannot be combined with the 2PC interface**
  (`DB-CORE-10141`) **nor with the Transaction Coordinator**. → A6.

### A3. Choosing the isolation level

```
Q: How strict must concurrent correctness be? (transactions only; this is not Consistency, 30 §5.2)
├─ Strict serializability (stock, balance — a conflict is fatal)
│    → SERIALIZABLE. Re-reads the read/scan sets at commit and aborts on an anti-dependency (costly).
├─ An ordinary business application (the default is fine)
│    → SNAPSHOT (default). Read skew / write skew / read-only anomalies are possible.
└─ Read-mostly, speed over freshness
     → READ_COMMITTED. Can return a non-latest committed value (30 §5.2).
       Can be chosen per transaction with the attribute cc-transaction-isolation (read at begin only).
```

Warnings:

- ✋ **Do not confuse the isolation level with `Consistency`** (→ AP-5). With transactions only the isolation level matters.
- ✋ **ScalarDB's SNAPSHOT is "read-committed snapshot isolation"**, single-version with no begin timestamp
  (`consensus-commit.mdx:166-170`). Results are cached per `Get` / `Scan` **object**; reading the same record through a
  different `Get` / `Scan` can see a value committed in the meantime (→ `30 §5.2.1`).
- ✋ **SERIALIZABLE rejects secondary-index Get/Scan on tables without before-image indexes** (`DB-CORE-10260` / `10261`).
  Tables created before 3.16.5 / 3.17.3 / 3.18.0 need `repairTable()` first
  (`consensus-commit.mdx:157-160`; → `30 §4.3`, `20 §6.4`).
- ✋ **On non-JDBC storages, a cross-partition scan can run at SNAPSHOT even when SERIALIZABLE is configured**; only a WARN is
  logged at startup (`docs/api-guide.mdx:1042`; → `10 §1`).
- ✋ **READ_COMMITTED does not avoid in-flight exceptions for index-based reads**: a Get/Scan through a
  secondary index, or a `ScanAll` with a condition on an indexed column, forces recovery "regardless of isolation level" and
  throws `UncommittedRecordException` on a row prepared less than 15 s ago (→ `30 §5.2.2`). Primary-key reads in
  READ_COMMITTED return the before-image instead. → AP-23.

### A4. Schema design (start from the access patterns) — most important

Stop the RDB habit of "create the tables first, add indexes later". Fix **how the data is read** first, then choose the keys.

```
Q1: How will this data mainly be searched? (list the main access patterns per table)
Q2: For each pattern —
    ├─ Point lookup by one key (1 record)
    │    → partition key (+ clustering key).
    ├─ Fetch a range (date range, ID range, sorted top N)
    │    → make that column a clustering key (ranges and sort order exist only on clustering keys, 30 §4.1/§4.3).
    │      Put the partition key on the "outer" filter: a range scan requires the partition key fully specified.
    ├─ Equality search on one non-key column (not a range)
    │    → secondary index on that column (equality only, single column, 30 §4.3).
    └─ Conditional search across all partitions
         → cross-partition scan (ScanAll). Do not make it the routine OLTP path (AP-4); needs the filtering flag (AP-27).
Q3: Are you about to put a secondary index on a column you want to search by range?
    → ✋ A secondary index is equality-only, no range. Ranges go on the clustering key (AP-1).
Q4: Do you want several column conditions to be fast together?
    → ✋ Only one column per search is an index key. Express composite conditions with composite partition / clustering keys (AP-3).
Q5: Which backends must the schema run on? (A1)
    → ✋ Check types and key values against 20 §5 / §5.1 (TIMESTAMP on Cassandra, BLOB keys, BIGINT > 2^53, ':' in Cosmos keys).
```

What the design must fix as output: partition key (one or more columns, ordered), clustering key (order + ASC/DESC), secondary
indexes (single columns that need equality lookups only), and each column's type (`20 §2`, backend restrictions `20 §5`).
Supporting facts: `ScanWithIndex.withStart/withEnd` always throw; one index key per search; a partition `Scan` needs the full
partition key.

- **Reserved names**: user columns named like Consensus Commit metadata (`tx_id`, `tx_state`, …) or `before_<other column>`
  are rejected at table creation (`DB-CORE-10101` / `10102`; → `20 §6.1`).
- **Hidden size**: each record also stores 10 metadata columns and a before-image copy of every non-key column;
  plan DynamoDB (400 KB) and Cosmos DB (2 MB) record sizes with that in mind
  (`database-adapters.mdx:166,219`; → `20 §6.6`).
- **Expressing the same design in SQL**: keys with `PRIMARY KEY((pk…), ck…)` + `WITH CLUSTERING ORDER BY`, indexes with a
  separate single-column `CREATE INDEX`. The split "range → clustering key, equality →
  index" is the same in SQL; only `=` is promoted to a key or index lookup (→ `40 §6`). Designs that
  rely on JOIN / aggregation run client side and hold the data in memory (→ AP-10, AP-26).

### A5. Transactions across processes / microservices

```
Q1: Do several processes (microservices) need to take part in ONE transaction?
├─ No → A2 (one process). Prefer this; keep each transaction inside one service when the business allows.
└─ Yes →
   Q2: Do you run ScalarDB Cluster?
   ├─ No (Core library only)
   │    → ✋ The Global Transaction API is not available: TransactionFactory.getGlobalTransactionManager()
   │      throws DB-CORE-10296 with consensus-commit (and 10297 / 10298 with jdbc / single-crud) (AP-16).
   │      The remaining Core option, the 2PC interface + join/resume, is deprecated and scheduled for removal in 3.20.0 (AP-15).
   │      → Plan for ScalarDB Cluster, or redesign so that one process owns the transaction.
   └─ Yes → use the Global Transaction API (GlobalTransactionManager / GlobalTransaction / BranchTransaction), CRUD interface only.
        Q3: Do the services share one ScalarDB Cluster, or does each own one?
        ├─ Share one cluster (shared-cluster pattern)
        │    → scalar.db.cluster.client.transaction_coordinator.enabled=false (default).
        │      Single-phase inside the cluster. Branches must touch disjoint data and run one at a time (AP-17).
        └─ Each owns a cluster (separated-cluster pattern)
             → Deploy the Transaction Coordinator; enable transaction_participant on every cluster's nodes;
               set the same cluster IDs in three places; share ONE Coordinator table (A6, AP-18).
        Q4: Is the service written with SQL or Spring Data JDBC?
             → ✋ The Microservice Transaction API is CRUD-interface only in 3.19 (A7).
```

Facts behind the flow:

- Availability: Core `consensus-commit` never hands out a `GlobalTransactionManager` (`DB-CORE-10296`); the Cluster client
  does — through the TC when `scalar.db.cluster.client.transaction_coordinator.enabled=true`, otherwise single-phase over the
  one cluster (→ `30 §5.5`).
- Application code is the same in both patterns; the pattern is a deployment choice and can be changed later
  (`deployment-patterns-for-microservices.mdx:88-94`). Mixing patterns in one system is allowed (`deployment-patterns-for-microservices.mdx:135`).
- Trade-offs per the docs: shared-cluster = fewer components, weaker resource isolation, one team administers the cluster;
  separated-cluster = better isolation, but the TC must be operated and the Coordinator table must be reachable from the TC
  and every cluster (`deployment-patterns-for-microservices.mdx:110-127`).
- Only the CRUD interface supports the Microservice Transaction API; SQL and Spring Data JDBC do not
  (`deployment-patterns-for-microservices.mdx:141`). The SQL cluster client has no TC keys (→ `11 §2.2`).
- Separated-cluster configuration in three places (TC: `scalar.db.cluster.transaction_coordinator.clusters` etc.; each cluster
  node: `scalar.db.cluster.node.transaction_participant.enabled=true` and `scalar.db.cluster.id`; each application:
  `scalar.db.cluster.client.transaction_coordinator.enabled` / `.contact_points` and `scalar.db.cluster.id`) — the same ID string
  must appear in all three (→ `11 §9`). A participant node cannot also run the replication log writer.

### A6. The Coordinator table and its options

```
Q1: Where does the Coordinator table live?
    → One physical table (database + scalar.db.consensus_commit.coordinator.namespace, default "coordinator").
      With multi-storage: map its namespace explicitly (namespace_mapping=coordinator:<storage>), otherwise it goes to default_storage (10 §4.5).
      With the Transaction Coordinator: the TC and EVERY participating cluster must resolve to the same physical table (AP-18).
Q2: Do you want group commit (coordinator.group_commit.enabled)?
    → ✋ Not with the 2PC interface, not on the TC (A2). Creates/needs the tx_child_ids column.
Q3: Do you want write-set logging (coordinator.write_set_logging.enabled)?
    → Needed only if you will call finishTransaction() (A6 facts, AP-20). Creates/needs the tx_write_set column.
Q4: Is the Coordinator table already created?
    → ✋ If you turn on Q2 or Q3 now, run Admin.repairCoordinatorTables() / Schema Loader --coordinator --repair-all FIRST (AP-19).
```

Facts:

- The optional columns exist only when the flag was on at creation or repair time;
  `repairCoordinatorTables` adds a missing optional column with `ALTER TABLE ADD COLUMN` and keeps one that already exists.
  The reader treats both columns as optional (→ `20 §6.7`).
- Per the docs, transactions fail when they write their status until the column is added (`consensus-commit.mdx:139-143`).
- The participant reads Coordinator state through **its own** storage and may write ABORTED there for an expired prepared
  record; the TC writes its decision through its own storage. Hence the "same physical table" rule (→ `11 §9`).

### A7. SQL interface or CRUD API

```
Q: Will the application use SQL (ScalarDB SQL / JDBC driver / Spring Data JDBC) or the Java CRUD API?
├─ CRUD API → the full feature set, including the Global Transaction API (A5).
└─ SQL →
     ✋ No Microservice Transaction API (A5).
     ✋ Every result is materialized in memory — in the app (direct mode) or in the Cluster node (AP-26).
     ✋ Any SELECT/UPDATE/DELETE without a usable key equality becomes a cross-partition scan; with a WHERE it needs
        cross_partition_scan.filtering.enabled=true, with ORDER BY also ordering.enabled=true (JDBC only) (AP-27).
     ✋ JOIN / GROUP BY / aggregates / HAVING run client side; LIMIT does not bound them (AP-10, AP-11).
     ✋ Do not generate SET MODE TWO_PHASE_COMMIT_TRANSACTION, PREPARE, VALIDATE, JOIN, SUSPEND, RESUME (deprecated, AP-15).
```

Sources: `deployment-patterns-for-microservices.mdx:141` (→ `40 §5`, `40 §8.1`).

---

## Part B. Anti-patterns

Each entry: **Symptom / Why it is wrong / Correct alternative / Sources**. Use them as "warn when the user's input has this
shape".

### AP-1. Putting a secondary index on a column you want to search by range

- **Symptom**: "I want to search `created_at` by range, so I add an index."
- **Why it is wrong**: a secondary index is equality-only and cannot be used for ranges (`ScanWithIndex.withStart` /
  `withEnd` always throw `UnsupportedOperationException`). NoSQL indexes are not sorted structures: a DynamoDB GSI is
  created with `KeyType.HASH` only; Cassandra uses a native secondary index. A range in `where()` is only a filter
  (→ `30 §4.3`).
- **Correct alternative**: make `created_at` a **clustering key** (ranges and sort order exist only on clustering keys), with the
  outer filter as the partition key.
- Sources: `30 §4.3`, `20 §5`.

### AP-2. "Create the tables now, add indexes later" (RDB habit)

- **Symptom**: tables are created without deciding the access patterns; indexes are added once things get slow.
- **Why it is wrong**: ScalarDB indexes are much less general (equality only, one column per search, conditions on non-key
  columns rewritten into an OR with the before-image column inside transactions). Adding an index later rarely saves a key
  design that does not match the queries. Key columns cannot be changed by ALTER either (→ `40 §2.2`).
- **Correct alternative**: **design keys from the access patterns** (A4). Ranges on keys, index only for equality point lookups.
- Sources: `30 §4.3`, `40 §2.2`.

### AP-3. Trying to speed up multi-column conditions with indexes

- **Symptom**: "If I index both `status` and `income`, a search on both will be fast."
- **Why it is wrong**: only one column per search is an index key. The other conditions become a filter; through a
  transaction a condition on a non-key column becomes `(col op x) OR (before_col op x)`, which makes a physical index access
  path less likely. Whether the database uses the `before_*` index is the database planner's decision.
- **Correct alternative**: express the main composite conditions with composite partition / clustering keys. If unavoidable, a
  manual index table written in the same transaction (AP-8).
- Sources: `30 §4.3` pitfalls 1–5.

### AP-4. Making cross-partition scans the routine OLTP path

- **Symptom**: `ScanAll` + filtering without a partition key is the normal way to search.
- **Why it is wrong**: it is a full scan + filter, not an index range; cost grows linearly with the table. On NoSQL the filter
  runs **inside ScalarDB** after the limit is removed, so a selective filter still reads the whole table. Ordering works only
  on JDBC, and on non-JDBC storages SERIALIZABLE can effectively become SNAPSHOT for such scans (`docs/api-guide.mdx:1042`).
  `filtering` and `ordering` are **off by default** — see AP-27.
- **Correct alternative**: partition / clustering keys that match the access pattern. Range aggregation for analytics belongs to
  ScalarDB Analytics (out of scope).
- Sources: `30 §4.2`, `50 §1`, `10 §1`.

### AP-5. Confusing the isolation level with `Consistency`

- **Symptom**: "Setting `Consistency` to `LINEARIZABLE` makes my transaction strict."
- **Why it is wrong**: different layers. Transactional CRUD ignores `Consistency` and always uses `LINEARIZABLE`;
  concurrent correctness is decided by the isolation level. The `Insert` / `Upsert` / `Update` classes do not even accept a
  consistency. A third setting, `scalar.db.jdbc.isolation_level`, is the RDB connection level (→ `10 §4.4`).
- **Correct alternative**: set the isolation level (`SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED`) for transactions.
  `Consistency` matters only for the non-transactional `DistributedStorage`, and on Cosmos DB / JDBC not even there (→ `30 §3.4`).
- Sources: `30 §5.2` comparison table, `30 §3.4`.

### AP-6. INSERT without column names on NoSQL

- **Symptom**: `INSERT INTO t VALUES (v1, v2, …)` with values only.
- **Why it is wrong**: without a column list, ScalarDB SQL assigns the values in the order of the table metadata's columns.
  On DynamoDB, Cosmos DB and Object Storage the non-key column order is not kept (the metadata stores the columns in a hash
  map; → `20 §6.3`), so values can land in the wrong columns. Even on JDBC the stored order is "partition key, clustering
  key, then the other columns", which is not necessarily the order written in `CREATE TABLE`.
- **Correct alternative**: **always name the columns** in INSERT / UPSERT.
- Sources: `20 §6.3`, `40 §3.1`.

### AP-7. Expecting one non-transactional batch to span partitions (storage-API misunderstanding)

- **Symptom**: "`DistributedStorage.mutate` / `put(List)` updates several partitions atomically."
- **Why it is wrong**: the non-transactional API is atomic only within the storage's atomicity unit — PARTITION for Cassandra,
  Cosmos DB and Object Storage, STORAGE for JDBC and DynamoDB (`StorageInfo` values, → `50 §1`). Crossing it is rejected with
  `IllegalArgumentException` `DB-CORE-10019` (PARTITION) / `10212`–`10215` (`put(List)` delegates to `mutate`). The
  exception is `IllegalArgumentException`; no `MultiPartitionException` is thrown. DynamoDB also rejects more than 100
  mutations per call (`DB-CORE-10108`).
- **Correct alternative**: if atomicity across partitions or tables is required, use **`DistributedTransaction`**. Transactions
  are not bound by the storage unit or the 100 limit: Consensus Commit groups and splits the write set itself (→ `30 §6.1`).
- Sources: `30 §2.3`, `30 §6.1`, `50 §1`.

### AP-8. Writing a "manual index table" outside a transaction

- **Symptom**: a separate lookup table is written independently of the main table (non-transactionally).
- **Why it is wrong**: the two tables can diverge.
- **Correct alternative**: a manual index table is a valid technique, but **write it in the same `DistributedTransaction` as the
  main record**; ScalarDB then guarantees that both commit or neither does (the commit writes all records of the transaction
  under one Coordinator state, → `30 §5`). Remember that reading it is an extra Get.
- Sources: `30 §1`.

### AP-9. Putting a condition or a `Consistency` on Insert / Upsert (and generating deprecated `put()`)

- **Symptom**: `insert(...).withCondition(...)`, or new code generated with `put()` / `enableImplicitPreRead()`.
- **Why it is wrong**: `Insert` and `Upsert` cannot carry a condition or consistency; the getters and setters throw
  `UnsupportedOperationException` (→ `30 §2.2`). `Update` takes a condition but no consistency. The `put(Put)` / `put(List)`
  methods are deprecated since 3.13.0 (removal in 4.0.0), and `enableImplicitPreRead()` / `disableImplicitPreRead()` /
  insert-mode builder methods are deprecated too.
- **Correct alternative**: use `insert` / `upsert` / `update` / `delete`. For "only if …" use `Update` + `UpdateIf*`, or `Put` +
  `PutIf*` through `mutate()` when a conditional blind write is really needed. A conditional `Update` needs no explicit
  pre-read: Consensus Commit turns on implicit pre-read for `Update` and `Upsert`.
- Sources: `30 §2.2`.

### AP-10. Running aggregation / JOIN / ORDER BY / non-key WHERE on a large table (direct path to OOM)

- **Symptom**: `SELECT COUNT(*) … WHERE non_key_col …`, or JOIN / GROUP BY on a table with many rows.
- **Why it is wrong**: JOIN (index nested-loop), GROUP BY, aggregates, HAVING, and ORDER BY **together with aggregation** run in
  the SQL layer, not in the database. The scan beneath returns all matching rows as one list first. In ScalarDB Cluster this
  happens in the **node's** JVM (→ `40 §5`).
- ORDER BY **without** aggregation is pushed to the `Scan` as orderings; it is cheap inside one partition when it follows the
  clustering order, and otherwise becomes a cross-partition scan with ordering (JDBC only, AP-27).
- **Correct alternative**: narrow the rows with the key design first. Pre-aggregated tables or ScalarDB Analytics (out of scope)
  for totals. Do not aggregate whole tables casually.
- Sources: `40 §5.1`, `40 §5.3`.

### AP-11. Using LIMIT as a "safety net" for heavy queries

- **Symptom**: "I added LIMIT 10, so it will not read everything."
- **Why it is wrong**: with JOIN or aggregation, LIMIT is applied client side and the scan gets **no** limit. Without them,
  Consensus Commit still sends the storage query **without LIMIT and without projections**, although it **stops pulling rows
  once `limit` rows survive**; the database may still sort or scan a lot (for example with ORDER BY). `LIMIT 0` means
  "no limit".
- **Correct alternative**: do not rely on LIMIT; bound the physical range with the keys (partition key `=`, clustering-key range).
- Sources: `40 §5.1`, `40 §5.2`.

### AP-12. Expecting ranges / OR / non-equality to use a key or an index

- **Symptom**: expecting `WHERE pk > 100`, `WHERE pk = 1 OR pk = 2`, or a range on an index column to be fast.
- **Why it is wrong**: only equality (`=`) that appears exactly once with the same value in every OR branch is promoted to a key or
  index. Ranges, OR over different values, BETWEEN, LIKE and `!=` fall back to `SCAN_ALL` + filter. An index column plus
  ORDER BY also becomes `SCAN_ALL`.
- **Correct alternative**: ranges on the clustering key with the partition key fully fixed by `=`; index only for equality point lookups.
- Sources: `40 §6`, `30 §4.3`.

### AP-13. Writing SQL that the grammar does not have

- **Symptom**: subqueries, UNION, DISTINCT, CASE, IN lists, OFFSET, arithmetic, window functions, CTEs.
- **Why it is wrong**: these are not in the grammar; they fail as parse errors (`DB-SQL-10026`). FROM is one table plus
  explicit JOINs; a WHERE value is a literal or a bind marker only.
- **Correct alternative**: split the logic in the application, or rewrite into the supported shape (flat SELECT + explicit JOIN +
  the five aggregates COUNT / SUM / MIN / MAX / AVG).
- Sources: `40 §4`.

### AP-14. Requirements that do not fit the backend (wrong selection)

- **Symptom**: "SQLite in production", "TiDB with SERIALIZABLE", "Cassandra with a TIMESTAMP column", "range / sort-heavy
  queries on Object Storage", "snowflake BIGINT IDs on Cosmos DB", "renaming columns on Spanner", "`:` in Cosmos DB key values".
- **Why it is wrong**: each hits a backend restriction — SQLite is for development (`database-adapters.mdx:113-114`); TiDB has no
  SERIALIZABLE and ScalarDB uses REPEATABLE READ instead without telling you (`database-adapters.mdx:117-118`); Cassandra rejects
  TIMESTAMP; Object Storage has no secondary index; Cosmos DB rejects BIGINT beyond ±2^53 and `:` in keys; Spanner rejects
  renames.
- **Correct alternative**: fix the requirements first (isolation level, types, indexes, ranges, key values, operations / backup),
  compare them with the `50` matrix, then choose the backend (A1).
- Sources: `50 §1`, `50 §2`, `50 §4`, `20 §5`.

### AP-15. Writing new multi-process code on the 2PC interface or `join()` / `resume()`

- **Symptom**: a new orchestrator / BFF passes a transaction ID to other services, which call `join(txId)` / `resume(txId)`, or
  uses `TwoPhaseCommitTransactionManager` (`prepare` / `validate` / `commit`); in SQL, `SET MODE TWO_PHASE_COMMIT_TRANSACTION`,
  `PREPARE`, `VALIDATE`, `JOIN`, `SUSPEND`, `RESUME`.
- **Why it is wrong**: all of these are **deprecated and scheduled for removal in 3.20.0**. The `join` / `resume` deprecation is
  announced in the release notes (`release-notes.mdx:28`). Through the Cluster client they already fail when
  **authentication**, piggyback-begin or write-buffering is enabled (`DB-CLUSTER-10082` / `10083` / `10078` / `10079` /
  `10059` / `10060`). 2PC also cannot be used with group commit (`DB-CORE-10141`).
- **Correct alternative**: the Global Transaction API on ScalarDB Cluster (A5). Existing code: plan the migration before 3.20.0;
  treat the deprecation WARN messages in logs as "harmless now, blocking for 3.20.0" (→ `40 §8.1`).
- Sources: `30 §5.1`, `30 §5.3`, `40 §8.1`, `11 §2.1`.

### AP-16. Expecting the Global Transaction API without ScalarDB Cluster, or through SQL

- **Symptom**: a Core-only application (`scalar.db.transaction_manager=consensus-commit`) calls
  `TransactionFactory.getGlobalTransactionManager()`; or a SQL / Spring Data JDBC service is expected to join a global transaction.
- **Why it is wrong**: Core throws `UnsupportedOperationException` `DB-CORE-10296`; `jdbc` and `single-crud-operation` throw
  `DB-CORE-10297` / `10298`. Only the Cluster client provides a `GlobalTransactionManager`, and only for the CRUD interface
  (`deployment-patterns-for-microservices.mdx:141`). `TwoPhaseCommitCoordinator` / `TwoPhaseCommitParticipant` are internal
  primitives, not an application API.
- **Correct alternative**: `scalar.db.transaction_manager=cluster` with the CRUD API; or keep the transaction inside one process.
- Sources: `30 §1`, `30 §5.5`, `60 §3.2`.

### AP-17. Breaking the branch lifecycle of a global transaction

- **Symptom**: committing after a branch failed; a branch that does not call `end` on its error path; closing a scanner after
  `end`; ending a branch from a timeout thread while CRUD runs; several branches of one transaction running concurrently or
  touching the same records in the shared-cluster pattern; code calling `end()` without a status.
- **Why it is wrong**:
  - **Nothing enforces the commit precondition**: committing after a branch ended with `FAILURE`, or while a branch is still
    working, is not detected and may commit partial work (`scalardb-cluster/api-guide.mdx:2587-2589`).
  - `rollback()` does not end branches; every branch still ends with `FAILURE`.
  - Scanners must be closed **before** `end`; `end(SUCCESS)` with an open scanner throws `DB-CORE-10301`, a second `end(SUCCESS)`
    throws `DB-CORE-10299` (→ `30 §5.5`).
  - A branch handle is not thread-safe, `end` included; ending does not keep the transaction alive.
  - Shared-cluster backing: all branches share **one** underlying transaction, so branches must touch disjoint data and be
    driven one at a time; this is not enforced.
  - `end` requires a status — `end(BranchTransaction.Status)`; there is no `end()` without a status (`release-notes.mdx:25`).
- **Correct alternative**: follow the `BranchTransaction` javadoc template — every branch ends on every path (`SUCCESS` on
  success, `FAILURE` in `catch` / `finally`), scanners closed first, commit only after all branches reported `SUCCESS`.
- Sources: `30 §5.5`.

### AP-18. Giving the Transaction Coordinator and the clusters separate Coordinator tables

- **Symptom**: in the separated-cluster pattern, the TC is pointed at "its own" database for the Coordinator table, while each
  cluster keeps the `coordinator` namespace in its own database.
- **Why it is wrong**: the TC writes the commit / abort decision through its own storage; each participant reads state through
  **its own** storage and, finding an expired prepared record without a state row, writes ABORTED there. There is no RPC to
  ask the TC (→ `11 §9`). With separate tables, a transaction the TC committed can be aborted by a participant's lazy
  recovery — split outcome.
- **Correct alternative**: one physical Coordinator table (same database and `coordinator.namespace`) for the TC and every
  participating cluster. How each cluster reaches it (for example multi-storage `namespace_mapping`) is a deployment choice.
- Sources: `11 §9` Pitfalls.

### AP-19. Turning on group commit or write-set logging on an existing Coordinator table without repairing it

- **Symptom**: `scalar.db.consensus_commit.coordinator.group_commit.enabled=true` or `…write_set_logging.enabled=true` is added
  to a running deployment.
- **Why it is wrong**: the `tx_child_ids` / `tx_write_set` columns exist only if the table was created or repaired with the flag
  on. Per the docs, transactions fail when they write their status until the column is added (`consensus-commit.mdx:139-143`);
  on JDBC the INSERT names a missing column (→ `20 §6.7`). Group commit additionally cannot be used with the 2PC interface or
  on the TC.
- **Correct alternative**: run `Admin.repairCoordinatorTables()` / Schema Loader `--coordinator --repair-all` with the new settings
  **before** enabling the flag in the applications. Turning a flag off later is harmless (the column is kept and read as
  optional).
- Sources: `10 §3`, `20 §6.7`.

### AP-20. Relying on `finishTransaction()` for cleanup with the default configuration

- **Symptom**: an operations job calls `finishTransaction(txId)` to clean Coordinator rows, or treats its `false` result as an error;
  or uses `getState(txId)` afterwards to decide whether a transaction committed.
- **Why it is wrong**: `finishTransaction` needs the write set, which is stored only when
  `scalar.db.consensus_commit.coordinator.write_set_logging.enabled=true` (default `false`); **with the default configuration it
  always returns `false`**. After a successful cleanup, `getState` returns `UNKNOWN`, and `rollback(txId)` on that ID writes a
  fresh ABORTED even though the transaction committed. It is not available with `jdbc` / `single-crud-operation`
  (`DB-CORE-10289` / `10290`) nor through the Cluster client (→ `30 §5.6`). Both APIs are described in the release notes
  (`release-notes.mdx:61-62`); the javadoc says "Most applications should not call it directly".
- **Correct alternative**: ordinary applications do not call it. If you need it, enable write-set logging (and repair the
  Coordinator table, AP-19), and keep your own application-level status record instead of relying on `getState` (→ `60 §2`).
- Sources: `30 §5.6`, `60 §3.3`.

### AP-21. Treating "transaction not found" as a non-retryable user error

- **Symptom**: retry logic special-cases `DB-CORE-10048` ("do not retry"), or decides by category that `TransactionNotFoundException`
  is a programming error.
- **Why it is wrong**: `TRANSACTION_NOT_FOUND` is CONCURRENCY `DB-CORE-20031`; `DB-CORE-10048` is not emitted, so such special
  cases silently never match. The javadoc says "You can retry the transaction from the beginning". With
  `max_active_transactions` an evicted transaction also surfaces this way (AP-28).
- **Correct alternative**: on `TransactionNotFoundException` / `DB-CORE-20031`, begin a new transaction and retry the whole unit
  of work. When analyzing logs that also come from older versions, map `10048` → `20031` and `40001` → `20015`.
- Sources: `30 §5.1`, `60 §1`, `60 §3.3`.

### AP-22. Retrying `UnsatisfiedConditionException` because its code is category 2

- **Symptom**: a generic retry loop keyed on the category digit (2 = retry) or on `catch (CrudException e)` also retries failed
  `putIf` / `updateIf` / `deleteIf` / `*IfExists` conditions.
- **Why it is wrong**: condition failures carry CONCURRENCY codes (`DB-CORE-20018`–`20020`), but they mean "your condition was
  false" — a business outcome. `UnsatisfiedConditionException` is a subclass of `CrudException`, so a
  `catch (CrudException e)` placed first swallows it into the retry path. The official example handles it separately and
  does not retry (`docs/api-guide.mdx:2079-2092`).
- **Correct alternative**: classify by exception class first (`UnsatisfiedConditionException` → roll back and apply business
  logic; `UnknownTransactionStatusException` → determine the outcome), then by category digit (→ `60 §2`).
- Sources: `60 §1` Pitfalls, `60 §2`.

### AP-23. Assuming READ_COMMITTED (or a read-only transaction) avoids in-flight read failures

- **Symptom**: a reporting endpoint switched to `READ_COMMITTED` or `beginReadOnly()` "so that it never conflicts", and it reads
  through a secondary index.
- **Why it is wrong**: under SNAPSHOT / SERIALIZABLE a read that meets a record prepared by another transaction with no state row
  yet, less than 15 s old, throws `UncommittedRecordException` (`DB-CORE-20017`, a `CrudConflictException`); the whole Get/Scan
  fails, even for a row that would not be in the result (→ `30 §5.2.2`). `beginReadOnly` does not change this. READ_COMMITTED
  avoids it **only for primary-key reads**: index Get/Scan and a `ScanAll` with a condition on an indexed column run a
  before-image index check that forces recovery regardless of the isolation level. Because Coordinator rows can be removed by
  cleanup, resolution may loop and gives up with `DB-CORE-20030` after 5 passes.
- **Correct alternative**: retry `CrudConflictException` on read paths too; for read-mostly endpoints prefer primary-key access
  when choosing READ_COMMITTED; keep large write transactions short so rows are not in-flight for long.
- Sources: `30 §5.2`, `30 §5.2.2`.

### AP-24. Setting HikariCP `exceptionOverrideClassName` (for example following AWS advice)

- **Symptom**: a `-Dhikaricp.configurationFile=…` that sets `exceptionOverrideClassName`, as AWS documentation recommends for its
  JDBC wrapper, is added to a ScalarDB JDBC deployment.
- **Why it is wrong**: ScalarDB refuses to start with `DB-CORE-10303` for **every** JDBC pool, Aurora or not, even though no
  ScalarDB property mentions it. Reason: ScalarDB infers "commit outcome unknown" from HikariCP evicting a connection whose
  SQLState starts with `08`; the override would keep the connection, and an unknown outcome would be reported as a
  safe-to-retry failure — duplicated data.
- **Correct alternative**: do not set it. To diagnose, look for the `hikaricp.configurationFile` JVM system property.
- Sources: `10 §4.4`, `50 §2.2`, `60 §3.2`.

### AP-25. Using `jdbc:aws-wrapper:` outside Aurora PostgreSQL / Aurora MySQL, or treating a failover at commit as "not applied"

- **Symptom**: `jdbc:aws-wrapper:mariadb:`, a wrapper URL to TiDB or to non-Aurora RDS; or retrying blindly after a commit error during
  an Aurora failover.
- **Why it is wrong**: only `jdbc:aws-wrapper:postgresql:` and `jdbc:aws-wrapper:mysql:` are accepted; anything else (including a
  MySQL URL that turns out to be TiDB) fails at startup with `DB-CORE-10302`. Non-Aurora RDS starts but is unsupported
  (`docs/configurations.mdx:430-434`). A failover during `commit()` surfaces as `UnknownTransactionStatusException`
  (`docs/configurations.mdx:428`); the failover SQLStates `08001` / `08S02` / `08007` are deliberately not treated as retryable
  conflicts.
- **Correct alternative**: offer the wrapper only for Aurora; use `jdbc:aws-wrapper:mysql:` for Aurora MySQL. On
  `UnknownTransactionStatusException`, determine the outcome before re-executing (→ `60 §1`).
- Sources: `10 §4.4`, `50 §2.2`.

### AP-26. Assuming SQL streams large results

- **Symptom**: a SQL `SELECT` that returns many rows is iterated "like a cursor", expecting bounded memory.
- **Why it is wrong**: every SELECT result is materialized: the SQL layer gets complete lists from the transaction's get / scan
  and collects all rows into the result set before returning. JOIN / aggregation inputs are held in memory too. In ScalarDB
  Cluster this happens in the node JVM, so one large query can pressure a node shared by many clients.
- **Correct alternative**: bound result sizes by key design and explicit paging on clustering keys; for large exports use the CRUD
  `Scanner` API or the data loader instead of one SQL statement.
- Sources: `40 §5.3`.

### AP-27. Expecting non-key queries and cross-partition ordering to work with the default configuration

- **Symptom**: a SQL `SELECT … WHERE non_key_col = ?` or a CRUD `Scan.all().where(…)` fails on first use with `DB-CORE-10008`; a
  `… ORDER BY` fails with `DB-CORE-10007`; turning ordering on with DynamoDB stops the application from starting.
- **Why it is wrong**: `scalar.db.cross_partition_scan.enabled` defaults to `true`, but `filtering.enabled` and
  `ordering.enabled` default to `false`. The SQL layer passes every WHERE predicate of a `SCAN_ALL` as a scan condition, so
  any non-key WHERE needs filtering (→ `40 §5.4`). Ordering is JDBC only; non-JDBC storages refuse to start with it.
  Filtering / ordering cannot be enabled while `cross_partition_scan.enabled=false`. In ScalarDB Cluster these are
  node-side settings.
- **Correct alternative**: decide in A4 / A7 whether non-key queries are needed. If so, set `filtering.enabled=true` (and
  `ordering.enabled=true` for JDBC only), or set them per operation / statement with the attributes
  `db-cross-partition-scan-filtering-enabled` etc. (`docs/api-guide.mdx:1617-1621`). Then re-read AP-4: enabling it does not make it cheap.
- Sources: `10 §1`, `30 §4.2`, `40 §5.4`, `50 §1`.

### AP-28. Leaving transactions open (missing `commit` / `rollback` in `finally`)

- **Symptom**: transactions that are begun but not always committed or rolled back (exception paths without `finally`).
- **Why it is wrong**: active transaction management keeps at most
  `scalar.db.active_transaction_management.max_active_transactions` (default `10000`) transactions; beyond that one is evicted
  and **rolled back** with only a WARN. A later operation on the evicted transaction fails (`TransactionNotFoundException`
  on resume). This applies to Core applications (`consensus-commit` and `jdbc`); Cluster nodes disable this mechanism and
  manage transactions themselves (→ `11 §1.2`). `expiration_time_millis` is `-1` (no idle expiry) in Core by default (→ `10 §1`).
- **Correct alternative**: always end transactions in `finally` (or with a template that does so); do not raise the cap to hide
  leaks. Generated Service-layer code must include the rollback path.
- Sources: `10 §1`, `30 §5.1`.

---

## Part C. Applying the anti-patterns (checklists)

### C1. Per task

- **Schema design**: follow A4 (and the backend part of A1). Decide partition / clustering keys, indexes and types from
  the answers; check against AP-1, AP-2, AP-3, AP-6, AP-14 and the key-value rules of `20 §5.1`; never use reserved column names
  (`20 §6.1`). When the target is SQL, write INSERT statements with column lists (AP-6).
- **Configuration**: follow A1, A2, A3, A5, A6 and A7 and write the properties of `10` / `11`.
  - Always write `scalar.db.storage` and `scalar.db.transaction_manager` explicitly (the storage default is `cassandra`).
  - Set `cross_partition_scan.filtering.enabled` / `ordering.enabled` only when A4 / A7 say non-key queries are needed, and
    `ordering` only for JDBC (AP-27).
  - Separated-cluster pattern: three coordinated files (TC, each cluster node, each application) with the same cluster IDs and
    one Coordinator table (A5, A6, AP-18).
  - Aurora: use `jdbc:aws-wrapper:` only for Aurora PostgreSQL / MySQL (AP-25); never set `exceptionOverrideClassName` (AP-24).
- **Configuration review**: checks derived from the APs.
  - Errors: group commit together with 2PC usage or on a TC (A2, AP-19); `ordering.enabled=true` with a non-JDBC storage (AP-27);
    `filtering`/`ordering` on while `cross_partition_scan.enabled=false`; `jdbc:aws-wrapper:` with an engine other than
    PostgreSQL / MySQL (AP-25); `exceptionOverrideClassName` anywhere (AP-24); `transaction_participant.enabled` without
    `scalar.db.cluster.id` (`DB-CLUSTER-10074`); TC and participants resolving to different Coordinator-table storage (AP-18).
  - Warnings: `Consistency` chosen to "strengthen" transactions (AP-5); `SERIALIZABLE` with index reads on tables that may lack
    before-image indexes (A3); `SERIALIZABLE` + cross-partition scan on non-JDBC storage (A3); `write_set_logging` / `group_commit`
    newly enabled (repair first, AP-19); `async_commit.enabled=true` with `parallel_commit.enabled=false`, which silently
    commits synchronously (→ `10 §3`).
- **Spring Boot / application code**: use `insert` / `upsert` / `update` / `delete` (AP-9); end every transaction in
  `finally` (AP-28); retry on `CrudConflictException` / `CommitConflictException` / `TransactionNotFoundException` from the beginning,
  never on `UnsatisfiedConditionException` (AP-21, AP-22); no `join` / `resume` / 2PC (AP-15); Global Transaction code only for
  Cluster targets, with the `end(Status)` template (AP-16, AP-17); unwrap the `RuntimeException` thrown while iterating a
  `Scanner` with for-each (→ `30 §2.1`).
- **Error-log analysis**: link the codes of `60` to the anti-patterns (table C2) and show "error → anti-pattern → fix". Apply the
  exception class before the category digit (AP-22).

### C2. Error code → anti-pattern

| Code / exception | Likely anti-pattern |
|---|---|
| `DB-CORE-10000` / `10001` / `10003` / `10004` | AP-1, AP-3 (index misuse) |
| `DB-CORE-10006` / `10007` / `10008` | AP-4, AP-27 |
| `DB-CORE-10128` / `10129` / `10130` / `10256` (startup) | AP-27 (ordering on NoSQL) |
| `DB-CORE-10019`, `10212`–`10215`, `10108` | AP-7 |
| `UnsupportedOperationException` from `Insert` / `Upsert` condition | AP-9 |
| `DB-CORE-10227`, `10224`–`10226`, `10246`, `10228`, `10063`, `10145`, `10251`, `10284` | AP-14 (backend mismatch; → `20 §5`, `50`) |
| `DB-CORE-10260` / `10261` / `10262` | A3 (SERIALIZABLE without before-image indexes) |
| `DB-CORE-10141` | A2 / AP-19 (group commit + 2PC) |
| `DB-CORE-10296` / `10297` / `10298` | AP-16 |
| `DB-CORE-10299` / `10301` | AP-17 |
| `DB-CLUSTER-10078` / `10079` / `10082` / `10083` / `10059` / `10060` | AP-15 |
| `DB-CLUSTER-10074` / `10075` / `10080` / `10081` | A5 (separated-cluster configuration; → `11 §9`, `60 §5.1`) |
| `DB-CORE-20017` (`UncommittedRecordException`), `20030` | AP-23 |
| `DB-CORE-20031` / `TransactionNotFoundException` | AP-21, AP-28 |
| `DB-CORE-20018`–`20020` (`UnsatisfiedConditionException`) | AP-22 (do not retry) |
| `DB-CORE-10302` | AP-25 |
| `DB-CORE-10303` | AP-24 |
| `finishTransaction()` returns `false` | AP-20 (not an error) |
| `DB-SQL-10026` (syntax error) | AP-13 |
| WARN "… deprecated as of release 3.19.0 and will be removed in release 3.20.0" | AP-15 |
| WARN "The transaction is evicted because the maximum number of active transactions … was exceeded" | AP-28 |
