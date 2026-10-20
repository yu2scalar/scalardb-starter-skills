# ScalarDB 3.19.1 Reference — CRUD / Transaction API and Limits

Reference for implementing CRUD REST applications on Spring Boot.
Covers operation types, conditions, scan restrictions, transaction isolation levels, the multi-process transaction APIs, and the constraints that fail at run time.

Applies to ScalarDB 3.19.1. Official docs: `api-guide.mdx`, `consensus-commit.mdx`, `two-phase-commit-transactions.mdx`, `scalardb-cluster/api-guide.mdx` (Microservice Transaction API), `scalardb-cluster/scalardb-cluster-configurations.mdx`, `releases/release-notes.mdx`, `scalardb-core-status-codes.mdx`.

Error codes are `DB-CORE-<category digit><4-digit id>` (category digit 1 = USER, 2 = CONCURRENCY, 3 = INTERNAL, 4 = UNKNOWN_TRANSACTION_STATUS). Example: `OPERATION_CHECK_ERROR_PARTITION_KEY` with id `0020` is `DB-CORE-10020` (`scalardb-core-status-codes.mdx:186`). This file uses the full form.

**Legend used throughout**

| Symbol / tag | Meaning |
|---|---|
| ✅ | Supported / the API exists and works |
| ❌ | Not supported / throws |

---

## 1. Choosing the abstraction layer

Selected by `scalar.db.transaction_manager` (→ [`10-configuration-core.md`](./10-configuration-core.md)).

| Layer | ACID / isolation | Use | Main classes | Status in 3.19.1 |
|---|---|---|---|---|
| `DistributedTransaction` (consensus-commit) | ✅ Transactions across multiple records / multiple databases | Normal applications | `DistributedTransactionManager` / `DistributedTransaction` | Current |
| Global transaction (Microservice Transaction API) | ✅ One transaction whose branches run in several processes | Microservices | `GlobalTransactionManager` / `GlobalTransaction` / `BranchTransaction` | Current. Works only with the ScalarDB Cluster client (§5.5) |
| `TwoPhaseCommitTransaction` (2PC) | ✅ Coordinator / participants cooperate | Microservices (legacy) | `TwoPhaseCommitTransactionManager` | **Deprecated; scheduled for removal in 3.20.0** |
| single-crud-operation | Each single operation is atomic | Lightweight single-operation path | `SingleCrudOperationTransactionManager` | Current |
| `DistributedStorage` (non-transactional) | ❌ No ACID (atomic only within the storage's atomicity unit) | Fastest low-level access | `DistributedStorage` | Current |

`TwoPhaseCommitCoordinator` / `TwoPhaseCommitParticipant` are **not** an application layer: their javadoc says they are internal primitives that application code should not call and that "Breaking changes can and will be introduced".

`AbstractDistributedTransactionProvider` is in the `com.scalar.db.common` package; its `createDistributedTransactionManager` etc. are `final`, and it has factory methods for the 2PC primitives and global transactions. This matters only to code that implements its own transaction provider; ordinary applications are not affected.

---

## 2. CRUD operations

### 2.1 Transaction API (`CrudOperable` / `TransactionCrudOperable`)

`TransactionCrudOperable extends CrudOperable<CrudException>`.

| Method | Meaning | Returns |
|---|---|---|
| `get(Get)` | Fetch one record (`Get` / `GetWithIndex`) | `Optional<Result>` |
| `scan(Scan)` | Fetch multiple records (whole list) | `List<Result>` |
| `getScanner(Scan)` | Lazily iterating `Scanner` | `Scanner` |
| `put(Put)` / `put(List)` | Insert or update (**@Deprecated 3.13.0**, removed in 4.0.0; the javadoc says "Use the insert, upsert, or update operations instead") | void |
| `insert(Insert)` | Insert only (conflict error if the record exists) | void |
| `upsert(Upsert)` | Update if present, insert otherwise | void |
| `update(Update)` | Update only (**no-op if the record does not exist**) | void |
| `delete(Delete)` / `delete(List)` | Delete (the List version is @Deprecated) | void |
| `mutate(List<? extends Mutation>)` | Several mutations at once | void |
| `batch(List<? extends Operation>)` | Batch mixing Get / Scan / mutations | `List<BatchResult>` |

- `Scanner`: `one()` / `all()` / `close()` (`AutoCloseable`, `Iterable<Result>`).
- `BatchResult`: `getType()` (GET / SCAN / PUT / INSERT / UPSERT / UPDATE / DELETE), `getGetResult()` / `getScanResult()`.
- Manager-level CRUD: `DistributedTransactionManager` also extends `TransactionManagerCrudOperable`, so a CRUD call on the manager runs in its own implicit transaction that is begun and committed internally.

#### Pitfalls

1. **Iterating a `Scanner` with for-each wraps the checked exception.** `Scanner.iterator()` calls `one()` and rethrows a `TransactionException` as a plain `RuntimeException`. A `CrudConflictException` raised during iteration therefore does not reach a `catch (CrudConflictException e)` block; unwrap `getCause()` or use `one()` / `all()` directly.
2. **All scanners must be closed before `commit()`.** Otherwise `commit()` throws `IllegalStateException` (`DB-CORE-10205`). For a global-transaction branch the same rule applies before `end(SUCCESS)` (§5.5).

### 2.2 `Put` vs `Insert` / `Update` / `Upsert` (existence assumption)

| Operation | Record exists | Record does not exist | condition | consistency | Docs |
|---|---|---|---|---|---|
| `Put` (no condition) | Update (blind write) | Insert | ✅ (`MutationCondition`) | ✅ | |
| `Insert` | **Conflict error** | Insert | ❌ (`UnsupportedOperationException`) | ❌ | `api-guide.mdx:1207` |
| `Upsert` | Update | Insert | ❌ | ❌ | |
| `Update` | Update | **no-op** | ✅ | ❌ | `api-guide.mdx:1257` |

**Legend for the `condition` / `consistency` columns**: whether the operation class **has the getter/setter as an API** (not whether the setting has an effect).

| Symbol | Meaning |
|---|---|
| ✅ | `withCondition` / `withConsistency` (and the getter) work and hold the value |
| ❌ | Calling them throws `UnsupportedOperationException` (the operation cannot carry the setting) |

> **Important**: even where `consistency` is ✅, **transactional CRUD ignores it and always uses `LINEARIZABLE`** (§3.4). The `Put` consistency ✅ only has an effect through the non-transactional `DistributedStorage`. `Insert` / `Upsert` / `Update` are the transaction-oriented APIs added in 3.13 and deliberately do not expose the legacy Consistency knob (their consistency setters throw `UnsupportedOperationException`).

> **What exactly is deprecated around `Put` (easy to misread)**: deprecated are the `CrudOperable` methods
> **`put(Put)` / `put(List<Put>)` (@Deprecated 3.13.0, removed in 4.0.0)**, the old `Put` constructors (`new Put(Key)` etc., @Deprecated 3.6.0), the old `PutIf` constructors (@Deprecated 3.6.0, use `ConditionBuilder`), and `Mutation.withCondition` (@Deprecated 3.6.0) / `Mutation.getCondition` (@Deprecated 3.13.0).
> **The `Put` class itself, the `PutIf` / `PutIfExists` / `PutIfNotExists` condition classes and `ConditionBuilder.putIf*` are not deprecated.**
> After the `put()` methods are removed (4.0.0), a `Put` can still be executed through `mutate(List)` (`Put extends Mutation`).
> New code should use `insert` / `upsert` / `update`; use `Put` via `mutate()` only when a conditional blind write is needed.
> The `PutBuilder` methods `enableImplicitPreRead()` / `disableImplicitPreRead()` / `implicitPreReadEnabled(boolean)` / `enableInsertMode()` / `disableInsertMode()` / `insertModeEnabled(boolean)` and the matching `ConsensusCommitOperationAttributes` helpers are **@Deprecated 3.19.0, removed in 4.0.0** ("Consensus Commit internal details; use the insert, upsert, or update operations instead").

- `Insert` / `Upsert` carry neither condition nor consistency (getter/setter throw `UnsupportedOperationException`).
- **Pre-read requirements**:
  - A conditional `Put` needs the record to be read first (explicit `Get` / `Scan`) or implicit pre-read (docs `api-guide.mdx:1141,1317`).
  - `Delete` always uses implicit pre-read (docs `api-guide.mdx:1286`).
  - **`Update` and `Upsert` turn on implicit pre-read automatically**: Consensus Commit converts them to a `Put` with implicit pre-read enabled; `Insert` becomes a `Put` in insert mode. No explicit read is needed for a conditional `Update`.
- An `Update` without a condition is executed as `putIfExists`; when the record is missing the resulting `UnsatisfiedConditionException` is swallowed, which is how "no-op" is implemented.

### 2.3 Non-transactional API (`DistributedStorage`)

Unlike the transactional API, **there is no `insert` / `upsert` / `update` / `getScanner` / `batch`**.

| Method | Notes |
|---|---|
| `get(Get)` | `Optional<Result>` |
| `scan(Scan)` | Returns a `Scanner` directly (not a List) |
| `put(Put)` / `put(List)` | `put(List)` just delegates to `mutate`, so it follows the mutation-atomicity-unit check: Puts spanning several partitions fail with `IllegalArgumentException` `DB-CORE-10019` on PARTITION-unit storages (Cassandra, Cosmos DB, Object Storage) and are accepted on STORAGE-unit storages (JDBC, DynamoDB). The `MultiPartitionException` mentioned in the javadoc is never thrown (the class does not exist) |
| `delete(Delete)` / `delete(List)` | |
| `mutate(List)` | Atomic only within `StorageInfo.getMutationAtomicityUnit()`, at most `getMaxAtomicMutationsCount()`, execution order not guaranteed |

---

## 3. Conditions

### 3.1 Mutation conditions (`MutationCondition`)

Built with `ConditionBuilder`.

| Condition | Applies to | Meaning |
|---|---|---|
| `PutIf` | Put | Only when all conditions hold |
| `PutIfExists` | Put | Only when the record exists |
| `PutIfNotExists` | Put | Only when the record does not exist |
| `DeleteIf` | Delete | Only when the conditions hold |
| `DeleteIfExists` | Delete | Only when the record exists |
| `UpdateIf` | Update | Only when the conditions hold |
| `UpdateIfExists` | Update | Only when the record exists |

- The expressions of `PutIf` / `DeleteIf` / `UpdateIf` are **AND-combined only** (no OR).
- **`LIKE` / `NOT_LIKE` cannot be used in mutation conditions** (the builder throws `IllegalArgumentException`, for Put, Delete and Update alike).
- When the condition does not hold or the record does not exist: `UnsatisfiedConditionException` (docs `api-guide.mdx:1313`).
- A condition on a `Put` / `Delete` is evaluated against the value **first read in the transaction** (the read set keeps the first value and is never overwritten). If the application re-read the record with a different `Get` and saw a newer value, the condition is still evaluated on the old one.

### 3.2 Operators (`ConditionalExpression.Operator`)

`EQ, NE, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL, LIKE, NOT_LIKE`. Comparison methods exist for every ScalarDB type, null checks for every type, and LIKE for TEXT only.

- `LikeExpression` (TEXT only): the escape character defaults to `\` (single character or empty string; empty disables escaping). Wildcards are `_` (one character) and `%` (any length). The pattern is validated.

### 3.3 Filter conditions for Selections (Get / Scan)

`AndConditionSet` / `OrConditionSet` are **for `where()` on Selections** (not for mutation conditions).

- Normal form: a `where` chain is either "AND of `ConditionalExpression` / `OrConditionSet`" (CNF) **or** "OR of `ConditionalExpression` / `AndConditionSet`" (DNF). Arbitrary nesting of AND/OR is not possible. Internally stored as DNF (a set of `Selection.Conjunction`) (docs `api-guide.mdx:832,994`).
- A `Get` whose `where` does not match returns `Optional.empty()`.

### 3.4 Consistency (non-transactional only)

enum: `SEQUENTIAL` (default) / `EVENTUAL` / `LINEARIZABLE`.

- **Ignored in transactional CRUD, always LINEARIZABLE** (docs `api-guide.mdx:732`). Consistency only has an effect through `DistributedStorage` (non-transactional).

**Concrete effect (per backend)**: Consistency selects the storage-level read/write consistency. The actual behavior depends on the backend.

| ScalarDB Consistency | Cassandra | DynamoDB | Cosmos DB | JDBC |
|---|---|---|---|---|
| `SEQUENTIAL` (default) | CQL `QUORUM` | Strongly consistent read (`consistentRead=true`) | No per-operation effect; follows the client setting `scalar.db.cosmos.consistency_level` (default `STRONG`) | No effect |
| `EVENTUAL` | CQL `ONE` (stale values possible) | Eventually consistent read (`consistentRead=false`) | Same as above | No effect |
| `LINEARIZABLE` | Reads: `SERIAL` / conditional writes: QUORUM + Paxos (`SERIAL`) | Strongly consistent read (`consistentRead=true`) | Same as above | No effect |

Practical guidance: `EVENTUAL` is fast but can return stale values, `SEQUENTIAL` (default) is a strongly consistent read, `LINEARIZABLE` is the strongest (Cassandra uses Paxos/SERIAL). On Cosmos the per-operation value has no effect (client setting decides); on JDBC it has no effect.

---

## 4. Scan types and restrictions

| Type | Class | Target | Use |
|---|---|---|---|
| Normal Scan | `Scan` | Within one partition, clustering-key range | Range fetch with the partition key specified |
| ScanWithIndex | `ScanWithIndex` | Secondary index (equality) | Search by an index column |
| ScanAll (cross-partition) | `ScanAll` | Whole table | Full / conditional search without a partition key |

### 4.1 Normal Scan

| Item | Specification |
|---|---|
| Partition key | **Must be fully specified** (no partial key) |
| Clustering-key range | start/end optional (either side may be omitted), inclusive/exclusive |
| Partial clustering key | Allowed (`allowPartial=true`) |
| start/end boundary consistency | start and end must have the same size and all but the last column must be equal (same prefix + range on the last column). Violation throws (`DB-CORE-10010`) |
| ordering | Must match a prefix of the clustering-key definition order; all forward or all reversed (no mixing) (`DB-CORE-10013`) |
| limit | `<0` throws, `0` means unlimited |
| projections | Only columns that exist in the metadata |

### 4.2 Cross-partition scan (ScanAll)

Tied to `scalar.db.cross_partition_scan.{enabled,filtering.enabled,ordering.enabled}` (defaults `true` / `false` / `false`; → [`10-configuration-core.md`](./10-configuration-core.md)).

- ScanAll with `enabled=false` → rejected (`DB-CORE-10006`). Ordering specified without ordering enabled → rejected (`DB-CORE-10007`). Conditions specified without filtering enabled → rejected (`DB-CORE-10008`).
- The configuration can be overridden per operation with the attributes `db-cross-partition-scan-enabled`, `db-cross-partition-scan-filtering-enabled`, `db-cross-partition-scan-ordering-enabled` (docs `api-guide.mdx:1617-1621`).
- **Ordering is JDBC-only**: non-JDBC storages reject `ordering.enabled=true` when the storage is created (docs `api-guide.mdx:1065,1081`), **and also reject any ScanAll with orderings at scan time**, so turning ordering on through the per-operation attribute does not help. ScanAll ordering may use any column (not only clustering keys).
- **On non-JDBC databases a cross-partition scan may run at SNAPSHOT even when SERIALIZABLE is configured** (docs `api-guide.mdx:1042`, `consensus-commit.mdx:188`).

### 4.3 ScanWithIndex (secondary-index search)

#### Specification

| Item | Specification |
|---|---|
| Index column | **Equality on a single index key only**, no range |
| Single-column index only | Throws if the index key size is not 1 (`INDEX_ONLY_SINGLE_COLUMN_INDEX_SUPPORTED`, `DB-CORE-10000`) |
| Must be an indexed column | Throws if the index-key column is not a secondary index (`DB-CORE-10001`) |
| Clustering-key range | **Not allowed** (`DB-CORE-10003`) |
| ordering | **Not allowed** (`DB-CORE-10004`) |
| Additional `where()` conditions | Allowed (but as a filter, not as an index search key; see below) |
| limit | Allowed |

#### Pitfalls (secondary indexes are especially misunderstood)

1. **Only one column can be used as the index in one search.** Even with several secondary indexes on a table,
   the index key of a ScanWithIndex is a single `Key` (one column) (`ScanBuilder.indexKey(Key)`).
   Using several index columns as "index search keys" at once is impossible through the API.
2. **The index key is equality-only; no range.** `>` `<` etc. cannot be given as the index key.
   `withStart()` / `withEnd()` on a ScanWithIndex always throw `UnsupportedOperationException`.
   A range can only go into `where()`, and there it is a **filter, not an index search key** (next item).
   - **Contrast (often confused)**: range scans are available only on the **clustering key** (normal `Scan` `withStart()` / `withEnd()`, with the partition key fully specified). **Secondary indexes are equality-only.** A "fast range lookup" requirement must be met by clustering-key design, not by a secondary index (→ [`20-data-model-and-types.md`](./20-data-model-and-types.md)).
   - **Why no range (physical reason)**: clustering keys are **stored sorted** (DynamoDB uses `KeyType.RANGE`; Cassandra sorts clustering columns on disk; JDBC follows PK order), so ranges work. Secondary indexes are **not sorted structures**: a DynamoDB GSI is **created with `KeyType.HASH` only** (no RANGE key); Cassandra uses a native secondary index (not SASI); both are equality-only. JDBC B-tree indexes could do ranges, but ScalarDB unifies the API to equality-only as the **lowest common denominator across all backends**.
3. **Additional `where()` conditions are filters, not index search keys.** Putting a non-index-key column (even another index column) into `where()` makes it a `conjunction`, applied as a storage-side WHERE after the index lookup. **"Adding the second index to the condition makes it faster" is a misconception.**
4. **Through a transaction, conditions on non-primary-key columns become an OR with the before-image column** (most important).
   Before sending a scan to storage, Consensus Commit rewrites each non-primary-key condition to `(col op x) OR (before_col op x)`.
   A condition turned into an OR is less likely to be used as a physical index access path and tends to fall back to filtering / scanning.
   - **The index key column (equality, in the partition-key slot) is not converted** and stays `col=?`, because it is not a conjunction; primary-key conditions are also left as-is.
   - **Only `ScanAll` (cross-partition) leaves index-column conditions unconverted.** The same condition yields a different storage query depending on the Scan type. For ScanAll the in-flight rows that only match by their before-image are found by a separate before-image index scan instead (see §5.2.2).
   - The scan is also sent **without projections and without the limit**; the limit is applied in the application layer after recovery and filtering. A `limit(10)` therefore does not cap what the database reads.
5. **Automatic before-image indexes**: Consensus Commit creates a secondary index on each `before_<indexed column>` automatically (unless `scalar.db.consensus_commit.index.eventually_consistent_read.enabled=true`). With both indexes present the database may choose a bitmap OR, but **whether an index is actually used is the underlying DB planner's decision**; ScalarDB Core only guarantees that it never passes two index columns as index search keys at once. SQL-layer behavior (`scalardb-sql`) is covered in [`40-sql-support-and-limits.md`](./40-sql-support-and-limits.md).
6. **Old tables**: tables missing the `before_*` index (created by older ScalarDB versions; see `consensus-commit.mdx:159`) get a startup warning (skipped when the isolation level is SERIALIZABLE or eventually-consistent index reads are on).
   With SERIALIZABLE, index get/scan on such tables is rejected (§6.2, `DB-CORE-10260/10261`); with SNAPSHOT / READ_COMMITTED reads may silently miss in-flight rows. `repairTable()` adds the index (docs `consensus-commit.mdx:159`).

> **Design guideline**: treat a secondary index as a tool that speeds up "one equality lookup on one column" only. If several conditions must be fast, design the partition key / clustering key for the access pattern first (→ [`20-data-model-and-types.md`](./20-data-model-and-types.md)).

---

## 5. Transaction semantics

### 5.1 Lifecycle

- **Manager** (`DistributedTransactionManager`): `begin()` / `begin(txId)` / `beginReadOnly(...)` / `start(...)` (alias of begin), `getState(txId)`, `rollback(txId)` / `abort(txId)`, `close`. `start(Isolation/Strategy)` is @Deprecated.
  - `join(txId)` (= `resume`) and `resume(txId)` are **deprecated; scheduled for removal in 3.20.0** (docs `api-guide.mdx:674-676,701-703`).
  - `resume` / `join` only work while the transaction is held by active transaction management (`scalar.db.active_transaction_management.enabled`, default `true`); otherwise they throw `UnsupportedOperationException`. A missing ID throws `TransactionNotFoundException`.
  - The registry is capped by `scalar.db.active_transaction_management.max_active_transactions` (default 10000); beyond that a transaction is evicted and disposed, so a later `resume` gets `TransactionNotFoundException`. (Config details → [`10-configuration-core.md`](./10-configuration-core.md).)
  - `finishTransaction(txId)` and `recoverRecord(...)` (§5.6).
- Manager-level CRUD without `begin` runs in an implicit transaction (§2.1).
- **Transaction** (`DistributedTransaction`): CRUD (§2) + `getId()` / `commit()` / `rollback()` / `abort()`. `commit()` may throw `CommitConflictException` / `CommitException` / `UnknownTransactionStatusException`.
- **`TransactionState`**: `PREPARED(1)` / `DELETED(2)` / `COMMITTED(3)` / `ABORTED(4)` / `UNKNOWN(5)`. The Coordinator table is the single source of truth.
  - `getState(txId)` returns `UNKNOWN` when the Coordinator state row is absent — never existed, already cleaned up (for example by `finishTransaction`), or **could not be read** (the read error is swallowed). `rollback(txId)` on a finished and cleaned-up ID writes a fresh ABORTED row, so `getState` then reports ABORTED even if the transaction had committed.
- **Category of `TRANSACTION_NOT_FOUND`**: it is **`DB-CORE-20031` (CONCURRENCY)** (`scalardb-core-status-codes.mdx:2038`). The exception class is `TransactionNotFoundException`; the javadoc says "You can retry the transaction from the beginning". Error-handling logic keyed on the code or category must treat it as retryable (→ [`60-error-codes.md`](./60-error-codes.md)).

### 5.2 Isolation levels

> **Isolation and Consistency are different things (the most common beginner confusion)**: the isolation level (this section) is the **transaction** isolation level and controls visibility **between transactions** (Consensus Commit protocol layer). Consistency (§3.4) is the storage-level read/write consistency of a **single operation** (replica consistency of the underlying DB) and is **only effective through the non-transactional `DistributedStorage`**.
> Consensus Commit always uses `LINEARIZABLE` storage operations as its building blocks and builds the isolation level on top, so **inside a transaction Consistency is ignored and always LINEARIZABLE**. With transactions, only the isolation level matters.

| Aspect | Isolation level (§5.2) | Consistency (§3.4) |
|---|---|---|
| Scope | Whole transaction | Single operation |
| enum | `SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED` | `SEQUENTIAL` / `EVENTUAL` / `LINEARIZABLE` |
| Layer | Consensus Commit protocol | Replica consistency of the underlying DB |
| Controls | Visibility between transactions (read/write skew etc.) | Whether one operation reads the latest value from replicas |
| Effective when | Using `DistributedTransaction` | Only `DistributedStorage` (non-transactional); ignored in transactions |

> **Note**: `api.Isolation` (`SNAPSHOT` / `SERIALIZABLE`) is deprecated as a whole enum (3.5.0). The effective one is
> `consensuscommit.Isolation` (`READ_COMMITTED` / `SNAPSHOT` / `SERIALIZABLE`); `scalar.db.consensus_commit.isolation_level` defaults to `SNAPSHOT`.
> A transaction can choose its own level with the attribute `cc-transaction-isolation`, read **only at begin time** (begin with attributes, or set on the operation of a manager-level implicit transaction) (docs `api-guide.mdx:1629-1633`).

| Level | Read visibility | Validation | Notes |
|---|---|---|---|
| **SNAPSHOT** (default) | Snapshot reads: results are cached per **Get / Scan operation object** in the get set / scan set. In-flight records are resolved to the latest committed value (`RETURN_LATEST_RESULT_AND_RECOVER`) | None | Read skew / write skew / read-only anomaly possible (no global snapshot, docs `consensus-commit.mdx:168-169`). See §5.2.1 for what the cache does *not* guarantee |
| **SERIALIZABLE** | Same as SNAPSHOT + | **Yes**: at commit, the read / scan / scanner sets are re-read and the transaction aborts on an anti-dependency | Strongest. `CommitConflictException` (wrapping a validation conflict). Validation runs even for read-only transactions when the scan set or scanner set is not empty |
| **READ_COMMITTED** | No snapshot cache. Storage is read every time; for an in-flight record the committed before-image is returned **without consulting the Coordinator table**, so a **non-latest committed value can be returned** | None | Fastest (docs `consensus-commit.mdx:174-176`). Read-only: `RETURN_COMMITTED_RESULT_AND_NOT_RECOVER` (no recovery). **Exception: index-based reads still fail on in-flight rows** (§5.2.2) |

Docs: `consensus-commit.mdx:81,166-176`.

- SERIALIZABLE rejects secondary-index get/scan on tables without before-image indexes (§6.2).
- The old `serializable_strategy` property is deprecated (warning only); SERIALIZABLE always uses the extra-read validation.

#### 5.2.1 What "snapshot read" caches (and does not)

- The get set is keyed by the **`Get` object** and the scan set by the **`Scan` object**. Re-executing the *same* Get/Scan in a transaction returns the cached result. Reading the same record through a *different* Get or Scan goes to storage again and can return a value committed in the meantime (non-repeatable read across operations).
- The read set (what prepare compares against) keeps the **first** value read for a key, in every isolation level, except in read-only transactions. A lost update is therefore prevented even in READ_COMMITTED, but the value the application last saw and the value used for conflict detection / conditions may differ (§3.1).
- ScalarDB's SNAPSHOT is single-version and has no begin timestamp (docs call it "read-committed snapshot isolation", `consensus-commit.mdx:168`); a record is read at "the latest committed value at the time it was first read", not "as of transaction begin".

#### 5.2.2 Reading in-flight (PREPARED / DELETED) records

When a read meets a record that another transaction has prepared but not finished, the reader does lazy recovery, **row by row and synchronously**:

| Coordinator state of the writer | SNAPSHOT / SERIALIZABLE return | READ_COMMITTED return |
|---|---|---|
| COMMITTED | After-image (state forced to COMMITTED; DELETED → no row) | Before-image (stale but committed) |
| ABORTED | Before-image (no before-image = an in-progress insert → no row) | Before-image |
| No state row, writer prepared **< 15 s ago** | **`UncommittedRecordException`** (`DB-CORE-20017`, a `CrudConflictException`): the whole Get/Scan fails, it does not wait | Before-image; recovery is attempted in the background |
| No state row, writer expired | Re-read the record; if still the same writer, write ABORTED (putIfNotExists), re-read once more, then return the before-image | Same as left (done in the background) |

- 15 s is a fixed transaction lifetime (15000 ms).
- Because Coordinator state rows can be removed by cleanup (`finishTransaction`), an absent state does not imply "never committed", so the reader re-reads the record and loops; after 5 resolution attempts it throws `CrudConflictException` **`DB-CORE-20030`** (release notes `release-notes.mdx:68,77`).
- After recovery the `where` conditions are re-evaluated on the returned value (the storage query also matched before-images). A Scan can therefore **fail because of a row that would not even be in its result**: recovery (and its exception) happens before the re-evaluation.
- **Index-based reads in READ_COMMITTED also throw.** For index Get / index Scan / ScanAll with a condition on an indexed column, a second scan on the before-image index forces `RETURN_LATEST_RESULT_AND_RECOVER` "regardless of isolation level". So in READ_COMMITTED a lookup through a secondary index that hits an in-flight row within 15 s fails with `UncommittedRecordException` (`consensus-commit.mdx:151`), while the same row read by primary key returns the before-image. If that scan rolled something back, the whole read is retried up to 3 times.
- Physical roll-forward / rollback runs on a background thread; at commit, SNAPSHOT waits only for the recoveries of keys it writes or deletes, SERIALIZABLE waits for all.

#### Pitfalls (isolation and in-flight records)

1. Read-only reporting queries under the default SNAPSHOT fail with `UncommittedRecordException` whenever they touch a row of a just-prepared transaction (and for up to 15 s after a writer crash). `beginReadOnly` does not change this; only READ_COMMITTED avoids it for primary-key reads. Retry on `CrudConflictException`.
2. Large single-transaction updates keep many rows in-flight for the whole prepare → commit-state window, which makes concurrent scans over the same range fail. (Inferred from the read path above, not measured.)

### 5.3 2PC (`TwoPhaseCommitTransaction`) — deprecated

- **Deprecated; scheduled for removal in 3.20.0**: `TwoPhaseCommitTransactionManager`, `TwoPhaseCommitTransaction`, and `DistributedTransactionProvider.createTwoPhaseCommitTransactionManager` (docs `two-phase-commit-transactions.mdx:11-13`). The replacement for applications is the Global Transaction API (§5.5).
- Flow: `prepare()` → `validate()` (required with SERIALIZABLE) → `commit()`. The phases that a one-phase `commit()` hides are exposed explicitly.
- The coordinator calls `start(txId)`; participants use the same txId with `join(txId)` / `resume(txId)`.
- **Cannot be combined with group commit**: 2PC + Coordinator group commit throws `IllegalArgumentException` (`DB-CORE-10141`, docs `consensus-commit.mdx:321-323`).
- Through ScalarDB Cluster, the client-side features restrict `join()` / `resume()` (`IllegalStateException`):

| Client setting | `join()` | `resume()` |
|---|---|---|
| `scalar.db.cluster.client.piggyback_begin.enabled=true` | ❌ `DB-CLUSTER-10078` | ❌ `DB-CLUSTER-10059` |
| `scalar.db.cluster.client.write_buffering.enabled=true` | ❌ `DB-CLUSTER-10079` | ❌ `DB-CLUSTER-10060` |
| Authentication enabled | ❌ `DB-CLUSTER-10082` | ❌ `DB-CLUSTER-10083` |

  The authentication guard exists because the begin-time credential cannot be recovered by another process. (→ [`11-configuration-cluster.md`](./11-configuration-cluster.md))

### 5.4 single-crud-operation restrictions

`begin` / `beginReadOnly` / `start` / `resume` / `getState` / `rollback(txId)` all throw `UnsupportedOperationException`, as do `finishTransaction` (`DB-CORE-10290`) and `recoverRecord` (`DB-CORE-10292`); the provider refuses the 2PC primitives (`DB-CORE-10295`) and global transactions (`DB-CORE-10298`).
`mutate` / `batch` accept **exactly one** operation (more → `DB-CORE-10138` / `DB-CORE-10242`, empty → `DB-CORE-10018`).
Each CRUD is issued directly to `DistributedStorage` with `LINEARIZABLE` (insert = `putIfNotExists`, update = `putIf` / `putIfExists`; no commit phase).

The JDBC transaction manager (`transaction_manager=jdbc`) likewise rejects `getState` / `rollback(txId)` / `finishTransaction` (`DB-CORE-10289`) / `recoverRecord` (`DB-CORE-10291`), the 2PC primitives (`DB-CORE-10294`) and global transactions (`DB-CORE-10297`).

### 5.5 Global transactions (Microservice Transaction API)

| Interface | Role | Key methods |
|---|---|---|
| `GlobalTransactionManager` | Begins global transactions and branches | `begin()` / `beginReadOnly()` / `start*()` (aliases), `beginBranch(txId[, attributes])` / `startBranch`, `close()` |
| `GlobalTransaction` | Drives the outcome; **no CRUD** | `getId()`, `commit()`, `rollback()` / `abort()` |
| `BranchTransaction` | CRUD handle of one branch (extends `TransactionCrudOperable`) | CRUD, `getId()`, **`end(Status)`** with `Status.SUCCESS` / `FAILURE` |

Typical flow: the initiator calls `begin()` and shares `getId()`; each process calls `beginBranch(id)`, does CRUD, then `end(SUCCESS)` (or `end(FAILURE)` on any failure path); finally the initiator calls `commit()` (or `rollback()`). Every branch is ended, including one begun by the initiator, on both paths (docs `scalardb-cluster/api-guide.mdx:2374-2383,2566-2570`).

**Where it works (availability)**:

| `scalar.db.transaction_manager` | `TransactionFactory.getGlobalTransactionManager()` |
|---|---|
| `consensus-commit` (Core) | ❌ `UnsupportedOperationException` `DB-CORE-10296` |
| `jdbc` | ❌ `DB-CORE-10297` |
| `single-crud-operation` | ❌ `DB-CORE-10298` |
| `cluster` (Cluster client) | ✅ with `scalar.db.cluster.client.transaction_coordinator.enabled=true`: two-phase commit driven by the Transaction Coordinator node; otherwise ("Fully Shared"): single-phase, backed by one cluster |

(Transaction Coordinator configuration → [`11-configuration-cluster.md`](./11-configuration-cluster.md).)

- A branch is ended with `end(BranchTransaction.Status)`: `end(SUCCESS)` on success, `end(FAILURE)` on failure paths (`release-notes.mdx:25`).

#### Pitfalls (global transactions)

1. **`end(SUCCESS)` is strict, `end(FAILURE)` is lenient.** `SUCCESS` on an already-ended branch throws `IllegalStateException` `DB-CORE-10299`; with an open scanner it throws `DB-CORE-10301`. `FAILURE` on an ended branch is a no-op and accepts open scanners. CRUD on an ended branch throws `IllegalStateException`.
2. **Close scanners before `end`**, never after: closing participates in read-set bookkeeping and can affect the outcome (docs `scalardb-cluster/api-guide.mdx:2569`).
3. **Nothing enforces the commit precondition.** Committing after a branch ended with `FAILURE`, or while a branch is still working, is not detected; partial work of a running branch may be committed (docs `scalardb-cluster/api-guide.mdx:2587-2589`).
4. **`rollback()` does not end branches**; each branch still has to be ended with `FAILURE` (docs `scalardb-cluster/api-guide.mdx:2595`).
5. **Ending a branch does not keep the transaction alive**; a branch that ended early can still be reclaimed by expiry before the owner commits.
6. **A branch handle is not thread-safe**, `end` included; ending from a timeout handler on another thread while CRUD runs is a data race.
7. **Fully Shared backing: all branches share one underlying transaction.** It assumes the branches touch disjoint data (not enforced; branches touching the same records see each other's uncommitted writes), the branches must be driven one at a time, and all of them through the same manager instance — "in practice, within one process" per the javadoc.
8. The public `join()` / `resume()` guards of §5.3 do not apply to the Global Transaction API: per-branch credentials are passed as auth attributes to `beginBranch`.

### 5.6 Low-level operational APIs

Both are Consensus Commit only; the javadoc says "Most applications should not call it directly". Described in the release notes (`release-notes.mdx:62-63`).

| API | What it does | Return value | Not supported |
|---|---|---|---|
| `finishTransaction(txId)` | For a COMMITTED/ABORTED transaction, finishes the per-record work left to lazy recovery using the write set stored in the Coordinator row, then deletes the row | `true` = finished or row already absent (idempotent); **`false` = no write set** (e.g. ended via `rollback(txId)`, aborted by lazy recovery, older binaries). Failure → `TransactionException` `DB-CORE-30068` | jdbc `DB-CORE-10289`, single-crud `DB-CORE-10290`, Cluster client |
| `recoverRecord(ns, table, pk, ck)` | Rolls one PREPARED/DELETED record forward or back; needs no write set | `true` = resolved or nothing to do; `false` = writer has no state row and has not expired (retry later). Failure `DB-CORE-30069` | jdbc `DB-CORE-10291`, single-crud `DB-CORE-10292`, Cluster client |

- **`finishTransaction` returns `false` for every transaction with the default configuration**: the write set is stored only when `scalar.db.consensus_commit.coordinator.write_set_logging.enabled=true` (default `false`). The column is added to the Coordinator table only when the feature is on (→ [`20-data-model-and-types.md`](./20-data-model-and-types.md)).
- After `finishTransaction`, `getState(txId)` returns `UNKNOWN` (§5.1).

---

## 6. Operations that fail at run time

### 6.1 Common (all `IllegalArgumentException`)

| Constraint | Error code |
|---|---|
| Partition key missing / invalid (must match exactly) | `DB-CORE-10020` |
| Clustering key invalid | `DB-CORE-10021` |
| Projection column not in the table | `DB-CORE-10009` |
| Empty mutation list | `DB-CORE-10018` |
| Mutation type other than Put/Delete | `DB-CORE-10126` |
| **`DistributedStorage.mutate` spanning the storage's atomicity unit** | `10212` (RECORD) / `10019` (PARTITION) / `10213` (TABLE) / `10214` (NAMESPACE) / `10215` (STORAGE) |
| Condition type / value mismatch | `DB-CORE-10015` |
| Invalid column value in a Put | `DB-CORE-10017` |
| Negative limit | `DB-CORE-10005` |
| Cross-partition scan violations | `DB-CORE-10006/10007/10008` |

> **Scope of the atomicity-unit check and the mutation-count limit**: the list check above is applied only by the storage adapters' `mutate`, i.e. the non-transactional path. **Transactions are not limited by it**: Consensus Commit groups the write set by atomicity unit and splits each group into batches of at most `getMaxAtomicMutationsCount()` before writing. There is no per-transaction mutation-count limit in Core.

### 6.2 Consensus Commit layer

- Transaction metadata columns (`tx_id`, `tx_state`, ...) cannot be used in projections / conditions / orderings / mutations (when `include_metadata.enabled=false`) (`DB-CORE-10097`, `10100`, `10258`, `10259`).
- Put conditions are limited to PutIf / PutIfExists / PutIfNotExists, Delete conditions to DeleteIf / DeleteIfExists.
- **With SERIALIZABLE, secondary-index get/scan on a table without before-image indexes is rejected** (`DB-CORE-10260` / `10261`; create the before-image index with `repairTable()`).
- With SERIALIZABLE, conditions on index columns in a cross-partition scan are rejected (`DB-CORE-10262`).
- A mutation in a read-only transaction: `DB-CORE-10211`.
- Scanning records already written or deleted by the same transaction is rejected with `IllegalArgumentException` `DB-CORE-10106` (except in read-only or one-operation mode; docs `consensus-commit.mdx:292`). Read first, then write.

### 6.3 Backend-specific

| Backend | Constraint | Error |
|---|---|---|
| Cosmos | Primary-key TEXT cannot contain `: / \ # ?` / BIGINT within ±2^53 / BLOB conditions only EQ, NE, IS_NULL, IS_NOT_NULL | `DB-CORE-10145`, `10063`, `10076` |
| DynamoDB | BOOLEAN conditions only EQ, NE, IS_NULL, IS_NOT_NULL / index column cannot be set to empty / **100 mutations per `DistributedStorage.mutate` call** (transactions are split automatically, §6.1) | `DB-CORE-10082` / `10081` / `10108` |
| JDBC | Delegates to the RDB engine: cross-partition-scan ordering on a BLOB column (Db2 `DB-CORE-10229`, Oracle `10247`) and BLOB columns in selection conditions (Oracle `10248`) are rejected | (see Constraint) |
| Object Storage | Primary-key TEXT cannot contain the delimiter / BIGINT within ±2^53 / BLOB above the size limit is rejected | `DB-CORE-10257`, `10282`, `10279` |

---

## 7. Pitfalls (details in 90-deep-dives)

1. **Atomicity-unit checks apply to `DistributedStorage.mutate` only**: the range a single `mutate` can span depends on the storage (RECORD / PARTITION / TABLE / NAMESPACE / STORAGE). Transactions split mutations automatically.
2. **SERIALIZABLE forbids secondary-index get/scan without before-image indexes** (`repairTable()` needed). Related to the before_* index performance topic in [`20-data-model-and-types.md`](./20-data-model-and-types.md).
3. **Consistency is ignored in transactional CRUD** and always LINEARIZABLE. It only matters through the non-transactional `DistributedStorage`.
4. **READ_COMMITTED can return non-latest committed values** (it returns the before-image without consulting the Coordinator table), **but index-based reads in READ_COMMITTED still fail on in-flight rows** (§5.2.2).
5. **Recovery lifetime 15000 ms.** `before_tx_state` is never PREPARED: recovered results are built with state COMMITTED or from the before-image, and before prepare the transaction waits for the recovery of every key it writes.
6. **piggyback-begin / write-buffering / authentication** (Cluster client) block `resume()` / `join()` (§5.3). A BFF/orchestrator design that hands a transaction ID between processes should move to the Global Transaction API, which is not subject to these guards (§5.5, pitfall 8) and replaces the deprecated `join` / `resume` / 2PC APIs that are scheduled for removal in 3.20.0.
7. **`TRANSACTION_NOT_FOUND` is a CONCURRENCY error (`DB-CORE-20031`)**; treat it as retryable (§5.1).
8. **`finishTransaction` silently returns `false`** unless write-set logging is enabled (§5.6).
