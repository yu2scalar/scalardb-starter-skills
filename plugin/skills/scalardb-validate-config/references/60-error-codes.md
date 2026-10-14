# ScalarDB 3.19.1 Reference — Error codes / status code catalog

Reference for error-log analysis. This file covers the **structure** of error codes, **category → required
action**, how exception classes map to categories, and a curated catalog of the codes beginners hit most often
in starter projects.

Applies to ScalarDB 3.19.1 (Core, Cluster, SQL).

Full catalog (all codes): the official status-code pages under `docs/`. **Any code can be looked up there directly**:

- `scalardb-core-status-codes.mdx` (DB-CORE)
- `scalardb-sql/scalardb-sql-status-codes.mdx` (DB-SQL)
- `scalardb-cluster/scalardb-cluster-status-codes.mdx` (DB-CLUSTER)
- `scalardb-cluster/scalardb-{auth,abac,encryption,embedding-store,remote-replication}-status-codes.mdx`
- `scalardb-data-loader-status-codes.mdx` / `scalardb-schema-loader-status-codes.mdx`
- `scalardb-graphql/scalardb-graphql-status-codes.mdx` (DB-GRAPHQL), `scalardb-analytics/scalardb-analytics-status-codes.mdx` (DB-ANALYTICS)

---

## 1. Code structure (read this first)

Format: **`<component>-<categoryId><id>`**.
The logged message is `<code>: <message>`.

- `<component>`: module identifier (table below).
- `<categoryId>`: **a single-digit category number**.
- `<id>`: 4-digit sequence number.

Example: `DB-CORE-10000` = `DB-CORE` + category `1` (USER) + id `0000` ("Only a single-column index is
supported").

| Component | Codes per category (1/2/3/4) | Docs page |
|---|---|---|
| `DB-CORE` | 223 / 31 / 63 / 5 | `scalardb-core-status-codes.mdx` |
| `DB-SQL` | 101 / 0 / 0 / 0 | `scalardb-sql-status-codes.mdx` |
| `DB-CLUSTER` | 66 / 4 / 7 / 0 | `scalardb-cluster-status-codes.mdx` |
| `DB-AUTH` | 35 / 0 / 36 / 0 | `scalardb-auth-status-codes.mdx` |
| `DB-ABAC` | 53 / 1 / 42 / 0 | `scalardb-abac-status-codes.mdx` |
| `DB-ENCRYPTION` | 17 / 0 / 21 / 0 | `scalardb-encryption-status-codes.mdx` |
| `DB-EMBEDDING` | 13 / 0 / 0 / 0 | `scalardb-embedding-store-status-codes.mdx` |
| `DB-REPL` | 4 / 0 / 0 / 0 | `scalardb-remote-replication-status-codes.mdx` |
| `DB-DATA-LOADER` | 60 / 0 / 7 / 0 | `scalardb-data-loader-status-codes.mdx` |
| `DB-SCHEMA-LOADER` | 18 / 0 / 0 / 0 | `scalardb-schema-loader-status-codes.mdx` |
| `DB-GRAPHQL`, `DB-ANALYTICS` | — | `scalardb-graphql-status-codes.mdx`, `scalardb-analytics-status-codes.mdx` |

Legend: per-category code counts in 3.19.1 (`—` = not counted).
Note the component strings: **`DB-EMBEDDING`** (not `DB-EMBEDDING-STORE`, which is only the docs page name) and
**`DB-REPL`** (not `DB-REPLICATION`). **Only `DB-CORE` has category-4 codes.**

> **Note on short ids**: other reference files sometimes cite a code by its 4-digit id alone (e.g. "`0006`").
> The code actually printed in logs always carries the category digit: `DB-CORE-10006` (USER). Read id and
> category together — the same 4-digit id exists in several categories (e.g. `DB-CORE-10031` and
> `DB-CORE-20031` are different errors).

### Category → required action (the core of error-log analysis)

| Category | Digit | Meaning | **Required action** |
|---|---|---|---|
| USER_ERROR | `1` (`x-1xxxx`) | Caller mistake (invalid operation, configuration, or schema) | **Deterministic. Retrying is pointless. Fix the code / configuration / schema** |
| CONCURRENCY_ERROR | `2` (`x-2xxxx`) | Transient contention (conflict, CAS failure, expired / unknown transaction ID, …) | **Retry the whole transaction from `begin()`** — but see the `UnsatisfiedConditionException` exception below |
| INTERNAL_ERROR | `3` (`x-3xxxx`) | ScalarDB-internal or environment failure | **Investigate / report. Check configuration and infrastructure (connectivity, privileges)** |
| UNKNOWN_TRANSACTION_STATUS_ERROR | `4` (`x-4xxxx`) | **The commit outcome is unknown** | **Do not assume it was rolled back.** Determine the outcome (application-level status record, or `getState(txId)` with the caveats in §2) before re-executing, or make the transaction idempotent |

See also "Error code classes and descriptions" in each docs page
(`docs/scalardb-core-status-codes.mdx:13-20`, `docs/scalardb-cluster/scalardb-cluster-status-codes.mdx:12-18`).

> `TRANSACTION_NOT_FOUND` is category 2 (`DB-CORE-20031`). The Cluster counterpart `DB-CLUSTER-20001` is also
> category 2, so Core and Cluster agree.

> **First-pass decision**: the category digit alone usually decides "fix / retry / check status". It is a first
> pass, not the final answer — the exception class (§2) overrides it in the cases listed under Pitfalls.

### Pitfalls

- **Condition failures carry category-2 codes.** `UnsatisfiedConditionException` messages use
  `DB-CORE-20018` / `20019` / `20020` (Consensus Commit), `DB-CORE-20012` (JDBC transactions)
  or `DB-CORE-20024` (single CRUD); ABAC uses `DB-ABAC-20000`. By the digit they look "retryable", but they
  mean "your `putIf` / `updateIf` / `deleteIf` / `*IfExists` / `*IfNotExists` condition was false". The official
  exception-handling example handles `UnsatisfiedConditionException` separately and does **not** retry it
  (`docs/api-guide.mdx:2079-2092`). Classify by exception class first.
- **Most USER codes are thrown as unchecked exceptions**, not as `TransactionException`: operation checks throw
  `IllegalArgumentException`, read-only violations throw `IllegalStateException`, unsupported features throw
  `UnsupportedOperationException`. A `catch (TransactionException e)` retry loop never sees them; they propagate
  as a stack trace.
- **There is no category 4 outside `DB-CORE`.** A commit with unknown outcome on a Cluster node reaches the
  client as gRPC `UNKNOWN_TRANSACTION_STATUS` and is rethrown as `UnknownTransactionStatusException`; with
  piggyback commit, gRPC transport errors are also turned into `UnknownTransactionStatusException`.

---

## 2. Exception class → category / handling

Main exceptions thrown by the Transaction API and what to do:

| Exception (parent) | Category of the codes it usually carries | Action |
|---|---|---|
| `CrudConflictException` (`CrudException`) | CONCURRENCY | Conflict during a CRUD operation. Retry the transaction from the beginning |
| `UncommittedRecordException` (`CrudConflictException`) | CONCURRENCY (`DB-CORE-20017`) | Read hit an in-flight record whose coordinator state is absent and not yet expired. Retry from the beginning |
| `CommitConflictException` (`CommitException`) | CONCURRENCY | Conflict at commit. Retry from the beginning |
| `PreparationConflictException` (`PreparationException`) | CONCURRENCY | Conflict in `prepare()`. Seen directly only with the two-phase commit (2PC) interface: in one-phase `commit()` it is converted to `CommitConflictException`. Retry from the beginning |
| `ValidationConflictException` (`ValidationException`) | CONCURRENCY (`DB-CORE-20022`) | SERIALIZABLE validation found an anti-dependency. In one-phase `commit()` it is converted to `CommitConflictException`. Retry from the beginning |
| `UnknownTransactionStatusException` (`TransactionException`) | UNKNOWN | **Commit outcome unknown.** Determine the outcome before re-executing (§1). Do not blindly retry (`docs/api-guide.mdx:2093-2098`) |
| `UnsatisfiedConditionException` (**`CrudException`**) | CONCURRENCY codes (see §1 Pitfalls), **semantically a business outcome** | The mutation condition was not met. Roll back and handle per application logic; do not put it in the generic retry loop (`docs/api-guide.mdx:2079-2092`) |
| `CrudException` / `CommitException` / `PreparationException` / `ValidationException` | various | Transient or non-transient; retrying may still fail. Use the code in the message to decide |
| `TransactionNotFoundException` (`TransactionException`) | **CONCURRENCY** | The transaction to resume/join was not found (expired, already finished, never begun on this coordinator), or begin/join failed transiently. **Start a new transaction and retry from the beginning** (`docs/api-guide.mdx:1993-1998,2102-2105`) |

Legend: "Category of the codes it usually carries" = the category digit found in the code inside the message;
the exception classes themselves have no category field.

- **Retry only CONCURRENCY-type failures, and never `UnsatisfiedConditionException`.** Because it is a subclass of
  `CrudException`, a `catch (CrudException e)` placed before it swallows it into the retry path.
- **`UnknownTransactionStatusException`: confirm before retrying** (avoid double application). `getState(txId)` has
  caveats:
  - it returns `UNKNOWN` once the Coordinator state row has been removed (by `finishTransaction` or another
    cleanup), so "committed and cleaned up", "never existed" and "unreadable" are indistinguishable;
  - calling `rollback(txId)` / `abort(txId)` on a cleaned-up ID writes a fresh ABORTED even if the transaction
    had committed;
  - `getState` is not supported with `transaction_manager=jdbc` (`DB-CORE-10092`) or single CRUD.
  The official guide recommends an application-level status table instead (`docs/api-guide.mdx:2023`).
- The case "coordinator state absent after losing the commit-state race" is treated as a definitive abort and
  reported as `CommitConflictException` `DB-CORE-20015` (retryable), not as an unknown outcome.

### Cluster client: gRPC error → exception

The Cluster client converts the node's gRPC error kind into Java exceptions (CRUD path; commit and the other
paths follow the same pattern):

| gRPC error kind (node → client) | Exception on the client |
|---|---|
| `ILLEGAL_ARGUMENT` / `ILLEGAL_STATE` / `UNSUPPORTED_OPERATION` | `IllegalArgumentException` / `IllegalStateException` / `UnsupportedOperationException` (USER-type) |
| `TRANSACTION_NOT_FOUND`, `HOP_LIMIT_EXCEEDED` | `TransactionNotFoundException` |
| `TRANSACTION_CONFLICT` | `CrudConflictException` |
| `UNSATISFIED_CONDITION` | `UnsatisfiedConditionException` |
| `AUTHENTICATION_ERROR` / `AUTHORIZATION_ERROR` | `CrudException` with `isAuthenticationError()` / `isAuthorizationError()` set |
| `UNKNOWN_TRANSACTION_STATUS` | `UnknownTransactionStatusException` |
| gRPC transport errors (`GRPC_*`, `INVALID_GRPC_PROTOBUF`) | `CrudException`, or `UnknownTransactionStatusException` during piggyback commit |

### Pitfalls

- A Cluster log often shows a **`DB-CORE-` code inside a client-side exception**: the node's message is passed
  through (`e.getMessage()`), so the component tells you where the error was raised, not where it was caught.

---

## 3. Frequent starter codes (DB-CORE)

### 3.1 USER codes (`DB-CORE-1xxxx`)

Codes typically hit by schema creation / CRUD implementations. Cause, fix, and related anti-pattern / axis
file (→ `70`, `30`, `50`, …).

| Code | Message (summary) | Cause / fix | Related |
|---|---|---|---|
| `DB-CORE-10000` | Only a single-column index is supported | Multi-column secondary index lookup. Indexes are single-column equality only | `30`, AP-3 |
| `DB-CORE-10001` | The column of the specified index key is not indexed | Index lookup on a non-indexed column. Create the index or change the condition | `30` |
| `DB-CORE-10005` | The limit cannot be negative | Negative LIMIT | — |
| `DB-CORE-10006` | Cross-partition scan is not enabled | `ScanAll` issued while `scalar.db.cross_partition_scan.enabled=false` | `10`, `30`, AP-4 |
| `DB-CORE-10007` | Cross-partition scan ordering is not enabled | `ScanAll` with orderings while `cross_partition_scan.ordering.enabled=false`. On Cassandra / Cosmos DB / DynamoDB / Object Storage, enabling ordering fails at startup instead with `DB-CORE-10128` / `10129` / `10130` / `10256` — ordering works only on JDBC | `30`, `50` |
| `DB-CORE-10008` | Cross-partition scan filtering is not enabled | Conditions on `ScanAll` while `filtering.enabled=false` | `10`, `30` |
| `DB-CORE-10062` | `scalar.db.cross_partition_scan.enabled` must be true to use filtering or ordering | Startup failure: filtering/ordering enabled without cross-partition scan | `10` |
| `DB-CORE-10009` | The specified projection is not found | Projection names a column that does not exist | `30` |
| `DB-CORE-10015` | The condition is not properly specified | Type/value mismatch or unsupported operator in a condition | `30` |
| `DB-CORE-10016` | The table does not exist | Schema not created or wrong name. Create it with Schema Loader | `20` |
| `DB-CORE-10017` | The column value is not properly specified | Type mismatch etc. in a Put | `20` |
| `DB-CORE-10018` | The mutations are empty | Empty `mutate()` | `30` |
| `DB-CORE-10019` | The storage does not support mutations across multiple partitions | Storage-API `mutate()` across partitions on a PARTITION-atomicity storage (Cassandra, Cosmos DB, Object Storage). Raised by the storage-level check | `30`, `50`, AP-7 |
| `DB-CORE-10020` / `10021` | Partition key / clustering key not properly specified | Key missing or partial (a scan needs the full partition key) | `30` |
| `DB-CORE-10076` | Cosmos DB: only EQ/NE/IS_NULL/IS_NOT_NULL for BLOB in conditions | Cosmos DB BLOB condition limitation | `30`, `50` |
| `DB-CORE-10082` | DynamoDB: only EQ/NE/IS_NULL/IS_NOT_NULL for BOOLEAN in conditions | DynamoDB BOOLEAN limitation | `30`, `50` |
| `DB-CORE-10108` | DynamoDB cannot batch more than 100 mutations at once | **More than 100 mutations in one batch**. Split it | `30`, `50`, AP-7 |
| `DB-CORE-10126` | The mutation type is not supported | A mutation other than Put/Delete | `30` |
| `DB-CORE-10211` | Mutations are not allowed in read-only transactions | Write in a transaction begun with `beginReadOnly()` (thrown as `IllegalStateException`) | `30` |
| `DB-CORE-10212`–`10215` | Mutation beyond the atomicity unit (RECORD / TABLE / NAMESPACE / STORAGE) | Storage-API `mutate()` crossing the storage's atomicity unit; JDBC and DynamoDB are STORAGE | `30`, `50` |
| `DB-CORE-10260` / `10261` | Index Get / Scan not allowed in SERIALIZABLE without before-image indexes | Run `repairTable()` to create the before-image indexes, or change the isolation level (the message says so) | `30`, `20` |
| `DB-CORE-10262` | Conditions on indexed columns in cross-partition scan not allowed in SERIALIZABLE without before-image indexes | Same remedy as above | `30` |
| `DB-CORE-10279` | Object Storage: BLOB value exceeds the maximum size | Limit = `Integer.MAX_VALUE / 4 * 3` bytes ≈ 1.5 GiB (Base64 overhead) | `50` |

> Cassandra's lack of TIMESTAMP (`DB-CORE-10227`) and BLOB keys / indexes on Oracle / Db2
> (`DB-CORE-10246`, `10228`) are also USER-category codes (see `50` for each limitation).

#### Pitfalls

- `10019` and `10212`–`10215` come from the **storage** API (`DistributedStorage.mutate()`); in transaction code
  they do not normally appear — if they do, the app is using the storage API directly.
- The ordering startup errors (`10128`–`10130`, `10256`) fire in the storage constructor: the app fails at
  `TransactionFactory` creation, not at the first query.

### 3.2 USER codes for Global Transaction / 2PC / admin APIs and the AWS JDBC Wrapper

| Code | Message (summary) | Cause / fix | Related |
|---|---|---|---|
| `DB-CORE-10289` / `10290` | Finishing a transaction is not supported in JDBC / single CRUD transactions | `finishTransaction()` with `transaction_manager=jdbc` / `single-crud-operation` | `30` |
| `DB-CORE-10291` / `10292` | Recovering a record is not supported in JDBC / single CRUD transactions | `recoverRecord()` on those managers | `30` |
| `DB-CORE-10293` | A participant ID is required … Set `scalar.db.consensus_commit.participant_id` | Creating a `TwoPhaseCommitParticipant` without the property | `10`, `30` |
| `DB-CORE-10294` / `10295` | Two-phase commit is not supported in JDBC / single CRUD transactions | `TwoPhaseCommitCoordinator` / `Participant` requested from those managers | `10`, `30` |
| `DB-CORE-10296` | Global transactions are not supported in Consensus Commit transactions | **`TransactionFactory.getGlobalTransactionManager()` with Core `consensus-commit`.** Core does not hand out a `GlobalTransactionManager`; use the Cluster client | `11`, `30` |
| `DB-CORE-10297` / `10298` | Global transactions are not supported in JDBC / single CRUD transactions | Same, with `jdbc` / `single-crud-operation` | `30` |
| `DB-CORE-10299` | The branch has already been ended | `BranchTransaction.end(SUCCESS)` called twice (`IllegalStateException`) | `30` |
| `DB-CORE-10300` | Branches are not supported by this coordinator-only global transaction manager | `beginBranch()` on a manager built without a participant | `30` |
| `DB-CORE-10301` | Some scanners were not closed … before ending the branch | `end(SUCCESS)` with an open `Scanner` (`IllegalStateException`). `end(FAILURE)` is lenient | `30` |
| `DB-CORE-10302` | The RDB engine is not supported with the AWS Advanced JDBC Wrapper. Only Aurora PostgreSQL and Aurora MySQL | `jdbc:aws-wrapper:` URL whose underlying URL is not `jdbc:postgresql:` / `jdbc:mysql:` (or is TiDB). Startup failure | `10`, `50` |
| `DB-CORE-10303` | The HikariCP `exceptionOverrideClassName` setting is not supported | Any HikariCP config that sets it — **including one picked up from the `hikaricp.configurationFile` system property**, which AWS documentation recommends for its wrapper. Startup failure for **every** JDBC deployment, not only AWS Wrapper ones | `10`, `50` |

#### Pitfalls

- `DB-CORE-10296` surprises users following Global Transaction examples with a Core-only config: the Global
  Transaction API needs `scalar.db.transaction_manager=cluster`.
- `DB-CORE-10303` can appear even though the ScalarDB properties file contains nothing about HikariCP — look for a
  `hikaricp.configurationFile` JVM system property.

### 3.3 CONCURRENCY (`DB-CORE-2xxxx`) / UNKNOWN (`DB-CORE-4xxxx`) / INTERNAL (`DB-CORE-3xxxx`)

- `DB-CORE-2xxxx`: conflicts, CAS failures, rollbacks (e.g. `NO_MUTATION_APPLIED` = `20000`, raised by storage
  adapters as `NoMutationException`). → **Retry the transaction.**
  The standard pattern is to wrap `begin()`…`commit()` in a function and call it from a retry loop
  (`docs/api-guide.mdx:2040-2120`).
- `DB-CORE-4xxxx`: commit outcome unknown. → **Determine the outcome, or re-execute idempotently.** Corresponds to
  `UnknownTransactionStatusException`.
- `DB-CORE-3xxxx`: internal / environment (connection loss, privileges, unexpected). → Check configuration and
  infrastructure.

Category-2, category-3 and category-4 Core codes that matter for analysis:

| Code | Name | Meaning / action |
|---|---|---|
| `DB-CORE-20015` | `CONSENSUS_COMMIT_CONFLICT_OCCURRED_WHEN_COMMITTING_STATE` | Commit-state write lost a race; transaction aborted. Also covers the case where the coordinator state is absent after losing the race. Retry |
| `DB-CORE-20017` | `CONSENSUS_COMMIT_READ_UNCOMMITTED_RECORD` | Read an in-flight record (`UncommittedRecordException`). Retry |
| `DB-CORE-20018`–`20020` | condition not satisfied | `UnsatisfiedConditionException` — **not a retry case** (§1) |
| `DB-CORE-20022` | `CONSENSUS_COMMIT_ANTI_DEPENDENCY_FOUND` | SERIALIZABLE validation failure. Retry |
| `DB-CORE-20030` | `CONSENSUS_COMMIT_RESOLVING_UNCOMMITTED_RECORD_RETRY_LIMIT_EXCEEDED` | Read-time resolution of an uncommitted record gave up after 5 passes; thrown as `CrudConflictException`. Retry |
| `DB-CORE-20031` | `TRANSACTION_NOT_FOUND` | Transaction ID unknown/expired/finished: `resume()` on an unregistered ID, or a 2PC coordinator/participant call after the context is gone. Begin a new transaction and retry |
| `DB-CORE-40000` | `JDBC_TRANSACTION_UNKNOWN_TRANSACTION_STATUS` | JDBC transaction rollback failed; outcome unknown |
| `DB-CORE-40002` / `40003` | cannot get / unknown coordinator status | Coordinator table unreachable during commit. Determine outcome |
| `DB-CORE-40004` | aborting state failed with `NoMutationException` but coordinator status does not exist | Determine outcome |
| `DB-CORE-40005` | `CONSENSUS_COMMIT_ONE_PHASE_COMMITTING_RECORDS_FAILED` | One-phase commit write failed; outcome unknown |
| `DB-CORE-30068` | `CONSENSUS_COMMIT_FINISHING_TRANSACTION_FAILED` | `finishTransaction()` could not read/clean the Coordinator state (thrown as `TransactionException`). It is a retryable cleanup |
| `DB-CORE-30069` | `CONSENSUS_COMMIT_RECOVERING_RECORD_FAILED` | `recoverRecord()` storage read/write failed |

#### Pitfalls

- `finishTransaction()` returning `false` is **not** an error: with the default configuration
  (`scalar.db.consensus_commit.coordinator.write_set_logging.enabled=false`) it always returns `false`.
  Do not report it as a failure in log analysis.

---

## 4. SQL errors (`DB-SQL-1xxxx`, USER)

Frequent JOIN / GROUP BY codes (→ `40`).

| Code | Summary | Related |
|---|---|---|
| `DB-SQL-10026` | Syntax error. Line %d:%d %s — every unsupported construct (subquery, UNION, DISTINCT, IN, CASE, arithmetic, …) surfaces as this parser error | `40` |
| `DB-SQL-10041` | Ambiguous column name (qualify with the table in JOINs) | `40` |
| `DB-SQL-10042` | Column of a joined table not allowed in the clause | `40` |
| `DB-SQL-10063` | RIGHT OUTER JOIN can only be the first join | `40` |
| `DB-SQL-10064`–`10067` | JOIN predicate invalid: not specified properly / type mismatch / duplicate column / neither full PK nor indexed column | `40` |
| `DB-SQL-10071` | Encrypted column in WHERE / ORDER BY / JOIN | `40` |
| `DB-SQL-10081`–`10088` | Aggregates / GROUP BY: argument count (`10081`), column not in GROUP BY (`10082`), unknown function (`10083` — only the registered aggregates), ordering not in projections (`10084`), unsupported data / argument type (`10085`, `10087`), numeric overflow (`10086`), HAVING item not in projections (`10088`) | `40` |
| `DB-SQL-10096` | Functions not allowed in WHERE | `40` |
| `DB-SQL-10097` | Functions not allowed in ORDER BY without GROUP BY | `40` |

- The ScalarDB JDBC driver maps "transaction not found" to `SQLTransientException`, SQLState `SDB09`, i.e. it is
  "retryable" on the SQL/JDBC side.

---

## 5. Cluster / auth / ABAC / encryption / embedding / replication errors

Each component has its own docs page:

| Component | Docs |
|---|---|
| `DB-CLUSTER` | `scalardb-cluster-status-codes.mdx` |
| `DB-AUTH` | `scalardb-auth-status-codes.mdx` |
| `DB-ABAC` | `scalardb-abac-status-codes.mdx` |
| `DB-ENCRYPTION` | `scalardb-encryption-status-codes.mdx` |
| `DB-EMBEDDING` | `scalardb-embedding-store-status-codes.mdx` |
| `DB-REPL` | `scalardb-remote-replication-status-codes.mdx` |

The gRPC error kinds exchanged between client and node (§2) are **not** a code catalog.

- Existing examples (also in `30` / `11`): `DB-CLUSTER-10059` / `10060` (resume not allowed with piggyback-begin /
  write-buffering), `DB-CLUSTER-10061` (transaction not begun yet with piggyback-begin).
- The category digit (§1) reads the same for every component.

### 5.1 Transaction Coordinator, attribute-based auth and join/resume codes

| Code | Message (summary) | Cause / fix | Related |
|---|---|---|---|
| `DB-CLUSTER-10078` / `10079` | Joining a transaction is not allowed when piggyback-begin / write-buffering is enabled | `join()` fails with `IllegalStateException` when piggyback-begin / write-buffering is enabled | `11`, `30` |
| `DB-CLUSTER-10082` / `10083` | Joining / resuming is not allowed when authentication is enabled | `join()` / `resume()` fail whenever auth is enabled. Use the Global Transaction API, which passes credentials per branch | `11`, `30` |
| `DB-CLUSTER-20003` | Transaction aborted because its authentication token was rejected during commit … retry from the beginning | Raised by the Transaction Coordinator when a participant rejected the token during commit; converted to a retryable `CommitConflictException`. Retry the transaction (a new `begin` takes a fresh token) | `11` |
| `DB-CLUSTER-10074` | `scalar.db.cluster.id` must be configured when `scalar.db.cluster.node.transaction_participant.enabled` is enabled | Node startup | `11` |
| `DB-CLUSTER-10080` | `scalar.db.cluster.id` must be configured for the cluster connection when `scalar.db.cluster.client.transaction_coordinator.enabled` is enabled | Client-side Global Transaction manager creation | `11` |
| `DB-CLUSTER-10081` | The cluster connection is not configured … can only begin, commit, and roll back | TC-only client (no `contact_points` for its own cluster) used for an operation that routes to a cluster | `11` |
| `DB-CLUSTER-10075` | The cluster is not configured. Cluster ID: %s; Configured clusters: %s | The enlisting client's `cluster.id` is not one of the TC's `transaction_coordinator.clusters`; not validated at startup, so it surfaces at first use | `11` |
| `DB-CLUSTER-10076` / `10077` | `transaction_coordinator.clusters` empty / duplicate cluster ID | TC startup | `11` |
| `DB-CLUSTER-10067` / `10068` | Invalid auth type in attributes / required auth attribute missing | Attribute-based authentication: wrong or incomplete per-operation credential attributes | `11` |
| `DB-CLUSTER-10069`–`10073` | Write type / write-set detail level unspecified or unrecognized; transaction ID required | Validation of internal protocol messages | `90` |

### 5.2 Contact-point codes

- `DB-CLUSTER-10028`–`10030` take the property key as an argument: "The contact points must not be empty.
  Property: %s", "… must be prefixed with 'indirect:' or 'direct-kubernetes:'. Property: %s", and the
  direct-kubernetes format message. One parser raises them for every client (Cluster client, SQL client,
  embedding client, Transaction Coordinator).

#### Pitfalls

- A misconfigured SQL or embedding client reports `DB-CLUSTER-10028`–`10030`; read the `Property:` part of the
  message to know **which** key is wrong.
- `join()` / `resume()` fail when authentication is enabled (`10082` / `10083`). Both methods are deprecated and
  scheduled for removal in 3.20.0.

---

## 6. Analysis procedure

1. **Split the code**: `<component>-<categoryId><id>`.
2. **Check the exception class first** (§2): `UnsatisfiedConditionException` → business outcome;
   `UnknownTransactionStatusException` → determine outcome; unchecked `IllegalArgumentException` /
   `IllegalStateException` / `UnsupportedOperationException` → fix.
3. **Use the category digit for the first action** (§1): 1 = fix / 2 = retry from `begin()` / 3 = investigate /
   4 = determine outcome.
4. **Look up component + id**: §3–§5 here first, then the docs status-code page.
5. **Point to the related anti-pattern** via the "Related" column (→ `70`): why it happened and the right alternative.
6. Logs that show only an exception class name (no code) are classified with §2.
