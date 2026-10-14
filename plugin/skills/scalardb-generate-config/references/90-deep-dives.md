# ScalarDB 3.19.1 Reference — Deep dives (pitfalls, design understanding)

This file collects what **the docs alone do not tell you**: behavior that is not evident from the docs, and the "why"
behind the design. Details are in the axis files
[`10`](./10-configuration-core.md), [`11`](./11-configuration-cluster.md), [`20`](./20-data-model-and-types.md),
[`30`](./30-crud-api-and-limits.md), [`40`](./40-sql-support-and-limits.md), [`50`](./50-backend-feature-matrix.md),
[`60`](./60-error-codes.md).

Applies to ScalarDB 3.19.1 (Core, Cluster, SQL). Official docs: `configurations.mdx`, `database-adapters.mdx`, `api-guide.mdx`, `consensus-commit.mdx`,
`two-phase-commit-transactions.mdx`, `scalardb-core-status-codes.mdx`, `releases/release-notes.mdx`, `scalardb-cluster/scalardb-cluster-configurations.mdx`,
`scalardb-cluster/deployment-patterns-for-microservices.mdx`, `scalardb-sql/grammar.mdx` (cited as `page.mdx:line`).

## Legend

| Term / mark | Meaning |
|---|---|
| **ID** | Stable identifier (`B1`–`B14`, `D1`–`D11`) |
| Isolation level vs `Consistency` | **Isolation level** (`READ_COMMITTED` / `SNAPSHOT` / `SERIALIZABLE`) belongs to Consensus Commit transactions. **`Consistency`** (`SEQUENTIAL` / `EVENTUAL` / `LINEARIZABLE`) is a per-operation storage knob that only non-transactional storage calls honor (B2) |
| in-flight record | A record whose writer has prepared it (`tx_state` PREPARED or DELETED) but whose outcome is not yet applied to the record |

## Contents

- B. Behavior not evident from the docs alone
- D. Design deep dives (why it works this way)

---

## B. Behavior not evident from the docs alone

Each item gives the behavior and a pitfall. Details are in the linked axis files.

### B1. Pushdown vs work done by ScalarDB, and OOM (→ `40 §5`, `30 §4.3`)

- **SQL layer**: GROUP BY, aggregates, HAVING and JOIN run in ScalarDB (direct mode in the app; in the Cluster node otherwise). ORDER BY is passed to the
  core Scan unless the query aggregates; LIMIT is passed only without JOIN/aggregation. Every result set is collected into one list.
- **Consensus Commit**: the storage scan gets **no LIMIT and no projection**, non-key conditions become
  `(col OR before_col)`, but reading **stops once `limit` surviving rows are collected**. The database runs the query without LIMIT, but ScalarDB
  stops pulling rows early.
- **Pitfall**: "I added WHERE/LIMIT and it is still slow / OOM" — aggregation or JOIN over a large scan, or an ORDER BY that the database must sort without LIMIT.

### B2. Isolation level and `Consistency` are different things (→ `30 §3.4, §5`)

Isolation level applies to Consensus Commit transactions. `Consistency` is a per-operation storage setting: transactional CRUD forces
`LINEARIZABLE` on every storage call, so a user-set `Consistency` has no effect there.
- **Pitfall**: setting `Consistency.EVENTUAL` on a transactional Get to "make it faster" does nothing.

### B3. `Consistency` per backend (non-transactional only) (→ `30 §3.4`)

Cassandra: `SEQUENTIAL` → `QUORUM`, `EVENTUAL` → `ONE`, `LINEARIZABLE` → `SERIAL` for reads / `QUORUM` for writes.
DynamoDB: anything but `EVENTUAL` → strongly consistent read. Cosmos DB: no per-operation effect; the client
setting decides (default `STRONG`). JDBC: not used.
- **Pitfall**: expecting `EVENTUAL` to change anything on JDBC or Cosmos DB.

### B4. Physical storage of date/time values (→ `20 §4`)

MySQL stores the UTC wall-clock value in `DATETIME(3)`; SQLite encodes everything as numbers;
SQL Server passes DATE/TIMESTAMP as text; Db2 uses strings and TIME with a fixed date;
Oracle stores TIME in a TIMESTAMP with a configurable fixed date.
- **Pitfall**: raw SQL or a session in another time zone sees shifted or encoded values; round trips through ScalarDB are consistent.

### B5. Effective JDBC pool timeouts (→ `10`)

`connection_timeout_millis` / `idle_timeout_millis` / `max_lifetime_millis` / `keepalive_time_millis` have no ScalarDB default
and are applied to HikariCP only when set, so the HikariCP defaults apply (30000 / 600000 / 1800000 / 0).
- **Pitfall**: reading "empty" as "no timeout".

### B6. Secondary indexes: single column, equality only, before-image interference (→ `30 §4.3`, `20`)

The index key is one column with equality only. Through a transaction, other
non-key conditions become `(col OR before_col)`; only `ScanAll` leaves indexed-column conditions unrewritten.
Consensus Commit creates an index on `before_<indexed column>` automatically unless `index.eventually_consistent_read.enabled=true`.
- **Pitfall**: expecting an index range scan, or expecting a second indexed column in `where()` to be used as an index.

### B7. Reading in-flight records (→ `30 §5.2`)

In SNAPSHOT / SERIALIZABLE an in-flight record is resolved through the Coordinator table: COMMITTED → after-image, ABORTED → before-image; with
**no state row and a writer younger than 15 s** the whole Get/Scan fails with `UncommittedRecordException` (`DB-CORE-20017`, a retryable CONCURRENCY error).
In READ_COMMITTED the before-image is returned without consulting the Coordinator table — except for index-based reads (B8).
- **Pitfall**: a read-only endpoint fails under write load although it "only reads".

### B8. READ_COMMITTED index reads still throw; legacy tables miss rows (→ `30 §5.2.2`)

For index Get / index Scan / ScanAll with a condition on an indexed column, a second scan over the `before_` index forces
`RETURN_LATEST_RESULT_AND_RECOVER` "regardless of isolation level"; a rolled-back record triggers up to 3 retries of the read.
The check runs only if the table has the before-image index. Tables created by older ScalarDB versions
lack it (`consensus-commit.mdx:155-160` lists the versions); SERIALIZABLE then rejects index reads (`DB-CORE-10260` / `10261`), other levels silently skip the check.
- **Pitfall**: the same row read by primary key returns a value, read through an index throws.

### B9. Coordinator-table columns follow the feature flags (→ `20`, `10`)

`tx_child_ids` exists only if group commit was enabled, `tx_write_set` only if write-set logging was enabled, when the Coordinator table was created;
the reader treats both as optional. `repairCoordinatorTables()` adds a missing column
with ALTER TABLE and keeps columns that already exist.
**Failure mode when a flag is turned on without repair**: the commit's state Put names a column that the table
metadata does not have, so the storage operation checker rejects it with `DB-CORE-10017` before it reaches the database; this happens on
every backend. The Put is retried 5 times with backoff and then surfaces as `UnknownTransactionStatusException`
`DB-CORE-40003`.
- **Pitfall**: every commit ends in "unknown transaction status" right after enabling `write_set_logging` or `group_commit` on an existing deployment.

### B10. `TRANSACTION_NOT_FOUND` is retryable (→ `60`)

`TRANSACTION_NOT_FOUND` is `DB-CORE-20031`, category CONCURRENCY: retry the transaction from the beginning.
- **Pitfall**: classifying it as a non-retryable user error.

### B11. Oracle schema operations and ORA-08177 (→ `50 §4.1`, `20 §6.5`)

Every catalog statement on JDBC (also the metadata read on a cache miss) is retried on a conflict error up to 10 times without backoff.
On Oracle, conflict means ORA-08177, ORA-00060 or ORA-08176. The typical trigger is an index
leaf-block split, and two retries were the worst case measured. The retry is per statement; a multi-statement operation is still not atomic.
- **Pitfall**: repeated `Retrying a conflicting statement` warnings are
  normal in small numbers; many of them point to a leaked transaction.

### B12. `scalar.db.cluster.hop_limit` (→ `11`)

`scalar.db.cluster.hop_limit` is read by the clients (primitive and SQL), embedding, replication and the
Transaction Coordinator — not by Cluster nodes. Nodes forward with `scalar.db.cluster.internal.hop_limit`.
- **Pitfall**: setting `cluster.hop_limit` in a node's properties to change node-to-node forwarding has no effect.

### B13. Cluster `join()` / `resume()` restrictions (→ `11`, `30`, `60`)

| Client setting | `join()` | `resume()` | `getId()` before the real begin |
|---|---|---|---|
| piggyback-begin | `DB-CLUSTER-10078` | `DB-CLUSTER-10059` | `DB-CLUSTER-10061` |
| write-buffering | `DB-CLUSTER-10079` | `DB-CLUSTER-10060` | — |
| authentication | `DB-CLUSTER-10082` | `DB-CLUSTER-10083` | — |

All are `IllegalStateException`s of category USER (fix, not retry). `join()`/`resume()` are deprecated and scheduled for removal in 3.20.0.
- **Pitfall**: code that worked with `join()` breaks when authentication is turned on.

### B14. AWS Advanced JDBC Wrapper (→ `10`, `50`)

`jdbc:aws-wrapper:` URLs are accepted only for Aurora PostgreSQL / Aurora MySQL (`DB-CORE-10302`), and a HikariCP
`exceptionOverrideClassName` is rejected for every JDBC deployment (`DB-CORE-10303`).
- **Pitfall**: an `exceptionOverrideClassName` in the JDBC URL/pool options makes startup fail.

---

## D. Design deep dives (why it works this way)

### D1. Why secondary indexes are equality-only

A DynamoDB GSI is created with a `HASH` key only; Cassandra uses a native secondary index. Neither is a
sorted structure. JDBC B-tree indexes could do ranges, but the API is unified on the lowest common denominator.

### D2. Transaction metadata lives in the user table

Each Consensus Commit table carries `tx_id`, `tx_state`, `tx_version`, `tx_prepared_at`, `tx_committed_at` and their `before_` counterparts, plus `before_<col>` for
every non-key column. Only ScalarDB interprets them; user columns with these names are rejected (see `20 §6.1`).

### D3. NoSQL key encoding and forbidden characters in key values

DynamoDB encodes keys as bytes with a `0x00` terminator, so a TEXT key value must not contain `\u0000` (`DB-CORE-10079`).
Cosmos DB joins primary-key values into the document id, so `:` and the id-forbidden `/ \ # ?` are rejected in partition **and**
clustering keys (`DB-CORE-10145`). Object Storage rejects its own delimiters (`DB-CORE-10257`).

### D4. Consensus Commit needs only single-record conditional writes from the storage

Consensus Commit runs on any `DistributedStorage`: it writes prepared records with conditional Puts
and the commit decision with `putIfNotExists` on the Coordinator table. ACID is
built above the storage.

### D5. Oracle: SERIALIZABLE gate and catalog conflicts

Oracle needs SERIALIZABLE for consistent virtual-table reads, and at SERIALIZABLE every catalog statement runs with explicit commit,
which makes catalog statements prone to ORA-08177. ScalarDB retries each catalog statement on such a conflict (B11).

### D6. Why index reads force recovery even in READ_COMMITTED

The before-index check must actually run recovery; otherwise the PREPARED/DELETED record stays in storage and the retry would be useless. The code therefore bypasses the
isolation-dependent recovery choice.

### D7. Why the storage scan has no LIMIT under Consensus Commit

Recovery and the before-image filter can drop rows, and whether a row survives is known only after per-row recovery; a storage-side LIMIT could drop a surviving row.
The limit is applied in ScalarDB after filtering.

### D8. Why the Transaction Coordinator and all clusters must share one Coordinator table

Lazy recovery on a participant force-aborts an expired record whose writer has no state row in the Coordinator table it reads.
If that table is not the one the Transaction Coordinator committed to, the participant cannot see the COMMITTED state and writes ABORTED.

### D9. Why `finishTransaction` returns `false` by default

Only transactions that persisted a write set are eligible; write-set logging is opt-in (default `false`), so by default nothing carries one.
The column is opt-in because it changes the Coordinator table schema (B9).

### D10. Why `join()` / `resume()` are rejected with piggyback-begin, write-buffering or authentication

With piggyback-begin there may be no server-side transaction yet; with write-buffering the buffered mutations live in the original client object; with authentication the
begin-time credential binding is local to the beginning process.

### D11. Why the in-process Global Transaction requires disjoint data

All branches share one underlying transaction and one snapshot; with disjoint data this is equivalent to per-branch validation, but branches touching the same records see
each other's uncommitted writes, and the shared transaction is not thread-safe.
