# ScalarDB 3.19.1 Reference — Core configuration properties

The configuration properties (`scalar.db.*`) of ScalarDB Core (OSS).
For Cluster-specific properties, see [`11-configuration-cluster.md`](./11-configuration-cluster.md).

Applies to ScalarDB 3.19.1. Official docs: `configurations.mdx`, `consensus-commit.mdx`, `multi-storage-transactions.mdx`.

- All properties use the prefix `scalar.db.`.
- Library versions that determine effective defaults: HikariCP `4.0.3`, AWS Advanced JDBC Wrapper `4.3.0`, MariaDB Connector/J `3.5.9` (used for MySQL).

## Column definitions

| Column | Meaning |
|---|---|
| Property | Fully qualified property name |
| Description | Purpose and behavior of the property |
| Default | The value that is **effectively applied** when the property is not set. `(empty)` means disabled / unspecified. When the literal default differs from the effective value, the effective value is shown and the difference is noted in Notes |
| Notes | Accepted values, related properties, preconditions, deprecation, special behavior, error codes |
| Group | Applicability condition (see the legend below) |

## Group legend

| Group | Meaning |
|---|---|
| `base` | Basic setting that applies regardless of the storage / transaction manager |
| `option` | Optional tuning or feature toggle |
| `transaction` | Related to choosing the transaction manager |
| `consensus-commit` | Meaningful only when `transaction_manager=consensus-commit` |
| `cassandra` / `cosmos` / `dynamo` | Only when that storage is selected |
| `jdbc` / `jdbc-pool` / `jdbc-admin-pool` | JDBC storage (common / connection pools / admin pool). Also read when `transaction_manager=jdbc` |
| `multi-storage` | Only when `storage=multi-storage` |
| `s3` / `blob-storage` / `cloud-storage` | Only when that Object Storage is selected |

## How every property value is parsed

Applies to all keys in this file.

- Values are trimmed, then placeholders `${env:VAR}` / `${sys:prop}` / `${sys:prop:-default}` are substituted (documented in `docs/configurations.mdx:811-822`).
- A value that is unset, empty or blank after trimming is treated as **unset** and the default applies. You cannot "disable" a property by setting it to an empty string.
- A non-numeric value for a numeric key throws `IllegalArgumentException` with `DB-CORE-10059`. A boolean key accepts only `true`/`false` (case-insensitive); anything else throws `DB-CORE-10060`.
- List values (`contact_points`, `multi_storage.*`) are split on commas; `\,` escapes a comma and **every backslash is then removed**.
- Unknown keys (typos) are never reported: each component only reads the keys it knows.

### Pitfalls

- A placeholder that cannot be resolved (for example a misspelled environment variable) is not an error: there is no "undefined variable" check, so the literal text `${env:...}` is passed on as the value.
- In a `.properties` file, `\` is itself an escape character. To get `\,` into the value (for example YugabyteDB multi-host URLs), the file must contain `\\,` (`docs/configurations.mdx:365-371`).

---

## 1. Common settings

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.storage` | Storage implementation | `cassandra` | Values: `cassandra` / `jdbc` / `dynamo` / `cosmos` / `multi-storage` / `s3` / `blob-storage` / `cloud-storage`. An unknown name fails with `DB-CORE-10065` when the storage is created | base |
| `scalar.db.transaction_manager` | Transaction manager | `consensus-commit` | Values: `consensus-commit` / `single-crud-operation` / `jdbc`. An unknown name fails with `DB-CORE-10066`. See §2 | transaction |
| `scalar.db.contact_points` | Contact points (comma-separated). Meaning differs per storage: JDBC URL, AWS region, Cosmos endpoint, `region/bucket`, and so on | `(empty)` | JDBC / Cosmos / Dynamo / S3 / Blob reject an empty value with `DB-CORE-10075` | base |
| `scalar.db.contact_port` | Port number for all contact points | `0` | Must be `>= 0` (`DB-CORE-10071`). **Only Cassandra reads it**; `0` means port `9042`. Ignored by all other storages | base |
| `scalar.db.username` | Username. Reinterpreted per storage (AWS access key, GCP project ID, Azure account name, …) | `(empty)` | Optional | base |
| `scalar.db.password` | Password. Reinterpreted per storage (AWS secret key, service account key JSON, Cosmos key, …) | `(empty)` | Optional | base |
| `scalar.db.default_namespace_name` | Namespace applied to operations that do not specify one | `(empty)` | Different from the system namespace `scalardb` | option |
| `scalar.db.metadata.cache_expiration_time_secs` | Expiration time (seconds) of the table metadata cache | `60` | **Any negative value = never expire; `0` = expire immediately (cache practically off)** | option |
| `scalar.db.active_transaction_management.enabled` | Enables active transaction management (tracking of in-progress transactions so that they can be resumed / joined by ID, with idle expiry and a size cap) | `true` | Applies to `consensus-commit` and `jdbc`. `single-crud-operation` does not use it | option |
| `scalar.db.active_transaction_management.expiration_time_millis` | Idle expiry (ms) of an in-progress transaction. An expired transaction is rolled back | `-1` (no expiry) | Non-positive disables idle expiry. The idle timer is refreshed by every operation on the transaction. Docs: `docs/configurations.mdx:763-767` | option |
| `scalar.db.active_transaction_management.max_active_transactions` | Maximum number of in-progress transactions kept by active transaction management. When exceeded, one tracked transaction is evicted and **rolled back** | `10000` | `0` or negative = unbounded. Eviction is Caffeine Window-TinyLFU (recency- and frequency-aware); an evicted transaction is rolled back. Applies even when expiry is disabled. Docs: `docs/configurations.mdx:769-773` | option |
| `scalar.db.attribute_propagation.enabled` | Enables propagation of transaction-scoped attributes | `true` | Applies to `consensus-commit` and `jdbc` | option |
| `scalar.db.cross_partition_scan.enabled` | Enables cross-partition scan (`Scan.all()`) | `true` | Must be `true` to use `filtering` / `ordering`; otherwise `IllegalArgumentException` at load time (`DB-CORE-10062`) | option |
| `scalar.db.cross_partition_scan.filtering.enabled` | Enables filtering (arbitrary `where`) in cross-partition scan | `false` | Requires `cross_partition_scan.enabled=true` | option |
| `scalar.db.cross_partition_scan.ordering.enabled` | Enables ordering in cross-partition scan | `false` | Requires `cross_partition_scan.enabled=true`. **Ordering is supported only for JDBC** (`docs/configurations.mdx:715`) | option |
| `scalar.db.scan_fetch_size` | Number of records fetched per batch in a storage scan | `10` | Object Storage ignores a non-default value with a WARN | option |

> **Backward-compatibility key**: the old isolation key `scalar.db.isolation_level` is a deprecated fallback for
> `scalar.db.consensus_commit.isolation_level` (to be removed in 4.0.0; see §3).

### Pitfalls

- `cross_partition_scan.enabled` is **`true` by default**. Combined with `consensus_commit.isolation_level=SERIALIZABLE` on a non-JDBC storage (Cassandra / DynamoDB / Cosmos DB / Object Storage), a WARN is logged at startup saying transactions may effectively run at `SNAPSHOT`. This is not a startup error. For multi-storage, each sub-storage is checked.
- Setting `filtering` or `ordering` to `true` while explicitly setting `cross_partition_scan.enabled=false` fails at startup.
- `max_active_transactions`: if an application keeps more than 10,000 transactions open at once (for example, transactions begun but never committed or rolled back because of a missing `finally`), the cache evicts one and **rolls it back** with only a WARN log. A later operation on the evicted transaction then fails. Being tracked is not a retention guarantee. The fix is to always close transactions, not to raise the cap blindly.
- `expiration_time_millis` is `-1` in Core. ScalarDB Cluster nodes read the same key with their own handling; see [`11-configuration-cluster.md`](./11-configuration-cluster.md).
- `contact_port` is meaningful only for Cassandra. For JDBC, put the port in the JDBC URL.
- `metadata.cache_expiration_time_secs=0` does not mean "no expiry"; it means "always reload" (every operation re-reads table metadata from the storage).

---

## 2. Transaction managers

Selected by `scalar.db.transaction_manager` (default `consensus-commit`). The name is resolved case-insensitively.

| Value | Summary | Own properties | Notes |
|---|---|---|---|
| `consensus-commit` | Distributed transaction protocol (default) | `scalar.db.consensus_commit.*` (§3) | Supports 2PC, `TwoPhaseCommitCoordinator` / `TwoPhaseCommitParticipant`. `createGlobalTransactionManager` throws `DB-CORE-10296` in Core |
| `single-crud-operation` | Lightweight mode where each transaction consists of a single CRUD operation (no `begin` / multi-operation transactions) | none | `scalar.db.consensus_commit.*` is ignored (`docs/configurations.mdx:29`). `active_transaction_management.*` and `attribute_propagation.enabled` have no effect. 2PC / Global transaction creation throws `DB-CORE-10295` / `DB-CORE-10298` |
| `jdbc` | Each ScalarDB transaction is a native JDBC transaction on one RDB (no Consensus Commit metadata) | `scalar.db.jdbc.*` (§4.4) | The JDBC configuration is read when `storage=jdbc` **or** `transaction_manager=jdbc`. `getState` / `rollback(txId)` / `finishTransaction` / `recoverRecord` are unsupported; 2PC and Global transactions throw `DB-CORE-10294` / `DB-CORE-10297` |

### Pitfalls

- The mode is chosen once per process (and per Cluster). `scalar.db.consensus_commit.*` keys left in a file are silently ignored when another mode is selected.
- `jdbc` mode: `transaction_manager=jdbc` with a non-JDBC `storage` is not rejected by the configuration check. Always set `storage=jdbc` together with `transaction_manager=jdbc`.
- The `finishTransaction` / `recoverRecord` APIs are Consensus Commit only; the other two modes throw `DB-CORE-10289`〜`10292`.

---

## 3. Consensus Commit settings

All properties use the prefix `scalar.db.consensus_commit.`.
Effective only when `transaction_manager=consensus-commit`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.consensus_commit.isolation_level` | Isolation level of Consensus Commit | `SNAPSHOT` | Values: `SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED` (case-insensitive). Falls back to the old key `scalar.db.isolation_level` (deprecated, removal in 4.0.0) | consensus-commit |
| `scalar.db.consensus_commit.coordinator.namespace` | Namespace of the Coordinator table | `(empty)` (effective name `coordinator`) | Literal default is `null`; the effective name is `coordinator`. A user namespace with this name is rejected (`DB-CORE-10096`) | consensus-commit |
| `scalar.db.consensus_commit.participant_id` | Stable logical ID of this process as a participant of the new two-phase commit components (`TwoPhaseCommitParticipant`) | `(empty)` | **Required only when a Consensus Commit `TwoPhaseCommitParticipant` is created** — via `TwoPhaseCommitFactory.getTwoPhaseCommitParticipant()` or a Global transaction manager built from it; otherwise `DB-CORE-10293`. Never read by the ordinary `DistributedTransactionManager` path. In ScalarDB Cluster the node overwrites it with `scalar.db.cluster.id` | consensus-commit |
| `scalar.db.consensus_commit.parallel_executor_count` | Number of executor threads for parallel execution (shared across transactions in the process) | `128` | — | consensus-commit |
| `scalar.db.consensus_commit.parallel_preparation.enabled` | Runs the prepare phase in parallel | `true` | — | consensus-commit |
| `scalar.db.consensus_commit.parallel_validation.enabled` | Runs the validation phase (`EXTRA_READ`, SERIALIZABLE) in parallel | value of `parallel_commit.enabled` (default `true`) | Inherits `parallel_commit.enabled` | consensus-commit |
| `scalar.db.consensus_commit.parallel_commit.enabled` | Runs the commit phase in parallel | `true` | — | consensus-commit |
| `scalar.db.consensus_commit.parallel_rollback.enabled` | Runs the rollback phase in parallel | value of `parallel_commit.enabled` (default `true`) | Inherits `parallel_commit.enabled` | consensus-commit |
| `scalar.db.consensus_commit.async_commit.enabled` | Runs the commit phase (record commit after the Coordinator write) asynchronously | `false` | **Effective only together with `parallel_commit.enabled=true`** (see Pitfalls) | consensus-commit |
| `scalar.db.consensus_commit.async_rollback.enabled` | Runs the rollback phase asynchronously | value of `async_commit.enabled` (default `false`) | Inherits `async_commit.enabled` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.write_omission_on_read_only.enabled` | Omits the Coordinator write for read-only transactions | `true` | — | consensus-commit |
| `scalar.db.consensus_commit.one_phase_commit.enabled` | One-phase commit optimization | `false` | Used only when no validation is required (not SERIALIZABLE validation), the transaction has writes, every deleted record was read, and all mutations fit one atomic mutation unit of the storage | consensus-commit |
| `scalar.db.consensus_commit.parallel_implicit_pre_read.enabled` | Runs implicit pre-reads in parallel | `true` | — | consensus-commit |
| `scalar.db.consensus_commit.include_metadata.enabled` | Includes transaction metadata columns in Get/Scan results | `false` | Debugging aid (`docs/configurations.mdx:775-779`) | consensus-commit |
| `scalar.db.consensus_commit.index.eventually_consistent_read.enabled` | Skips the before-image index check for index reads | `false` | Backward-compatibility option, not recommended for new workloads (`docs/configurations.mdx:781-791`) | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.enabled` | Group commit of transaction states | `false` | **Cannot be used with the two-phase commit interface** (`DB-CORE-10141`) **nor with the new `TwoPhaseCommitCoordinator`**. Adds the `tx_child_ids` column to a newly created Coordinator table | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.slot_capacity` | Maximum number of slots in a group | `20` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.group_size_fix_timeout_millis` | Timeout to fix the slot count of a group (ms) | `40` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.delayed_slot_move_timeout_millis` | Timeout to move delayed slots to an isolated group (ms) | `1200` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.old_group_abort_timeout_millis` | Timeout to abort an old in-progress group (ms) | `60000` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.timeout_check_interval_millis` | Interval of group-commit timeout checks (ms) | `20` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.metrics_monitor_log_enabled` | Periodically logs group-commit metrics | `false` | — | consensus-commit |
| `scalar.db.consensus_commit.coordinator.write_set_logging.enabled` | Records each transaction's write set in the Coordinator table (column `tx_write_set`, BLOB) on commit | `false` | The column is added only when the flag is enabled at table creation. `finishTransaction` returns `false` (not applicable) for a state row without a write set. Docs: `docs/configurations.mdx:793-803`, `docs/consensus-commit.mdx:131-147` | consensus-commit |

**Deprecated (to be removed in 4.0.0):**
- `scalar.db.isolation_level` — backward-compatible fallback for `isolation_level`; a WARN is logged when it is present.
- `scalar.db.consensus_commit.serializable_strategy` — ignored. `SERIALIZABLE` always uses the `EXTRA_READ` strategy; the WARN is logged only when the isolation level is `SERIALIZABLE`.

### Pitfalls

- **`write_set_logging.enabled` changes the Coordinator table schema.** The `tx_write_set` column exists only if the table was created (or repaired) with the flag on. Turning the flag on for an existing table requires `Admin.repairCoordinatorTables()` / Schema Loader `--coordinator --repair-all` first; `repairCoordinatorTables` adds the column with ALTER TABLE. Per the docs, transactions fail until then (`docs/consensus-commit.mdx:139-143`; → `90` B9). Recorded write sets become unusable after renaming a table / primary-key column or recreating a table (`docs/consensus-commit.mdx:327`).
- The same applies to `group_commit.enabled` and the `tx_child_ids` column (repair adds it). In 3.19.1 both columns are optional and read only when present.
- **`async_commit.enabled=true` with `parallel_commit.enabled=false` silently runs the commit synchronously**: asynchronous execution is implemented only on the parallel path. No warning is logged. Likewise for `async_rollback` / `parallel_rollback`.
- With `async_commit`, failures of the background record commit are logged as WARN only and the record is left for lazy recovery; the application is not told.
- `async_rollback` follows `async_commit` by default, so enabling async commit also makes rollback asynchronous.
- `participant_id` is not needed for ordinary Core applications. It matters only when you build the new `TwoPhaseCommitParticipant` yourself; in Cluster it is derived from `scalar.db.cluster.id` and a value you set is overwritten.
- `serializable_strategy` and `scalar.db.isolation_level` still "work" (no error), which hides that the setting is obsolete.

---

## 4. Per-storage settings

Connection information is generally given through the common properties in §1 (`contact_points` / `contact_port` / `username` / `password`).
The tables below list the storage-specific properties. The Cosmos, DynamoDB, JDBC, Multi-storage and Object Storage configurations throw `IllegalArgumentException` when `scalar.db.storage` does not match; **the Cassandra configuration has no such check**.

### 4.1 Cassandra

One specific property (connection settings are common).

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.system_namespace_name` | System namespace name. Introduced so that `admin.getNamespaceNames()` can return the system namespace configured by ScalarDB Cluster (reuses the Cluster-defined property) | `(empty)` (effective `scalardb`) | In Core only Cassandra reads it | cassandra |

#### Pitfalls

- All hosts in `contact_points` share one port: `contact_port` (`0` → `9042`) is applied to every contact point; per-host `host:port` is not handled separately.
- Credentials are sent only when **both** `username` and `password` are set; setting just one silently connects without authentication.
- The Cassandra configuration does not verify `scalar.db.storage`, and `storage` defaults to `cassandra`: a file that forgets `scalar.db.storage` is treated as a Cassandra configuration.
- `scalar.db.system_namespace_name` affects only Cassandra in Core; setting it for other storages has no effect (they use their own `*.table_metadata.*` keys).

### 4.2 Cosmos DB

Prefix `scalar.db.cosmos.`.
The endpoint is the first element of `contact_points`; the key comes from `password`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cosmos.table_metadata.database` | Cosmos DB database that stores table metadata | `scalardb` | Effective default is the system namespace name `scalardb` | cosmos |
| `scalar.db.cosmos.consistency_level` | Consistency level of Cosmos DB operations | `STRONG` | Only `STRONG` / `BOUNDED_STALENESS` (another valid Cosmos level fails with `DB-CORE-10077`). Docs: `docs/configurations.mdx:497-501`. Not the same thing as the ScalarDB `Consistency` enum of operations, nor as the isolation level | cosmos |

#### Pitfalls

- Only the **first** element of `contact_points` is used as the endpoint; `contact_port` and `username` are not read for Cosmos DB. Put the full endpoint URL in `contact_points` and the key in `password`.
- `consistency_level` accepts any Cosmos `ConsistencyLevel` name syntactically, but anything other than `STRONG` / `BOUNDED_STALENESS` fails at client creation, and an unknown name fails as well. It is the Cosmos client consistency, not ScalarDB's isolation level.
- Table metadata lives in the database `scalardb` unless `cosmos.table_metadata.database` is set; changing it later means ScalarDB reads metadata from the new database, so existing tables are most likely not found.

### 4.3 DynamoDB

Prefix `scalar.db.dynamo.`.
Region = first element of `contact_points`, access key = `username`, secret key = `password`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.dynamo.endpoint_override` | DynamoDB endpoint to use. Mainly for local testing | `(empty)` | Falls back to the deprecated hyphenated form `scalar.db.dynamo.endpoint-override` (removal in 4.0.0, WARN). Docs: `docs/configurations.mdx:465-469` | dynamo |
| `scalar.db.dynamo.table_metadata.namespace` | Namespace that stores table metadata | `scalardb` | Subject to `dynamo.namespace.prefix` | dynamo |
| `scalar.db.dynamo.namespace.prefix` | Prefix of user and metadata namespace names. Useful for several ScalarDB environments in one AWS region | `(empty)` = `""` | Effective `""`. Docs: `docs/configurations.mdx:471-475` | dynamo |

#### Pitfalls

- `contact_points` is the **AWS region** (first element only), not an endpoint URL. To point at DynamoDB Local or another endpoint, use `dynamo.endpoint_override`; `contact_port` is not read.
- Credentials are always built as static credentials from `username` / `password`; the AWS default credential provider chain (for example an IAM role) is not used by this code path.
- `dynamo.namespace.prefix` is prepended to the metadata namespace as well as to user namespaces. Changing the prefix after tables exist most likely makes them invisible to ScalarDB, because both metadata and table names are looked up with the new prefix; use different prefixes to keep several environments apart in one region.
- The hyphenated `scalar.db.dynamo.endpoint-override` still works with a deprecation WARN; prefer `endpoint_override`.

### 4.4 JDBC

Prefix `scalar.db.jdbc.`.
URL / username / password come from the common properties (empty `contact_points` → `DB-CORE-10075`). Only the first contact point is used.
Connection pools are HikariCP 4.0.3; three independent pools: transactional/storage, table metadata, admin.

#### Connection pool (main)

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.connection_pool.min_idle` | Minimum idle connections | `20` | — | jdbc-pool |
| `scalar.db.jdbc.connection_pool.max_total` | Maximum idle + active connections | `200` | — | jdbc-pool |
| `scalar.db.jdbc.connection_pool.connection_timeout_millis` | Maximum wait for a connection (ms) | `30000` (effective) | Literal default `null`; applied only when set, otherwise HikariCP's default | jdbc-pool |
| `scalar.db.jdbc.connection_pool.idle_timeout_millis` | Maximum idle time of a connection (ms). `0` = never removed | `600000` (effective) | Effective value from HikariCP | jdbc-pool |
| `scalar.db.jdbc.connection_pool.max_lifetime_millis` | Maximum lifetime of a connection (ms). `0` = unlimited | `1800000` (effective) | Set a few seconds shorter than DB / infrastructure timeouts (`docs/configurations.mdx:225-229`) | jdbc-pool |
| `scalar.db.jdbc.connection_pool.keepalive_time_millis` | Keepalive interval (ms). Must be less than `max_lifetime_millis`; `0` disables | `0` (effective) | — | jdbc-pool |
| `scalar.db.jdbc.isolation_level` | JDBC isolation level of pooled connections | `(empty)` → database default | Values: `READ_UNCOMMITTED` / `READ_COMMITTED` / `REPEATABLE_READ` / `SERIALIZABLE`. This is the RDB's isolation, not `consensus_commit.isolation_level` | jdbc |
| `scalar.db.jdbc.table_metadata.schema` | Schema (namespace) that stores table metadata | `scalardb` (effective) | Literal default `null`; effective `scalardb` | jdbc |

#### Table metadata pool

| Property | Description | Default | Group |
|---|---|---|---|
| `scalar.db.jdbc.table_metadata.connection_pool.min_idle` | Minimum idle connections | `5` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.max_total` | Maximum total connections | `25` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.connection_timeout_millis` | Maximum wait for a connection (ms) | `30000` (effective) | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.idle_timeout_millis` | Maximum idle time (ms) | `600000` (effective) | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.max_lifetime_millis` | Maximum lifetime (ms) | `1800000` (effective) | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.keepalive_time_millis` | Keepalive interval (ms) | `0` (effective) | jdbc-pool |

#### Admin pool

| Property | Description | Default | Group |
|---|---|---|---|
| `scalar.db.jdbc.admin.connection_pool.min_idle` | Minimum idle connections | `5` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.max_total` | Maximum total connections | `25` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.connection_timeout_millis` | Maximum wait for a connection (ms) | `30000` (effective) | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.idle_timeout_millis` | Maximum idle time (ms) | `600000` (effective) | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.max_lifetime_millis` | Maximum lifetime (ms) | `1800000` (effective) | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.keepalive_time_millis` | Keepalive interval (ms) | `0` (effective) | jdbc-admin-pool |

#### Dialect-specific (MySQL / Oracle / Db2 / Spanner)

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.mysql.variable_key_column_size` | Column size for TEXT/BLOB used as a key in MySQL (including MariaDB / TiDB); minimum 64 | `128` | Below 64 → `DB-CORE-10144` | jdbc |
| `scalar.db.jdbc.oracle.variable_key_column_size` | Same for Oracle; minimum 64 | `128` | — | jdbc |
| `scalar.db.jdbc.db2.variable_key_column_size` | Same for IBM Db2; minimum 64 | `128` | — | jdbc |
| `scalar.db.jdbc.oracle.time_column.default_date_component` | Fixed date used to store `TIME` in Oracle (no date-less time type) | `1970-01-01` | Parsed with `ISO_LOCAL_DATE` | jdbc |
| `scalar.db.jdbc.db2.time_column.default_date_component` | Fixed date used to store `TIME` in Db2 (stored as TIMESTAMP) | `1970-01-01` | — | jdbc |
| `scalar.db.jdbc.spanner.time_column.default_date_component` | Fixed date used to store `TIME` in Spanner (stored as TIMESTAMPTZ) | `1970-01-01` | — | jdbc |

**Removed (a WARN is logged and the value ignored; checked until 4.0.0):**
`scalar.db.jdbc.connection_pool.max_idle` / `scalar.db.jdbc.prepared_statements_pool.enabled` /
`scalar.db.jdbc.prepared_statements_pool.max_open` / `scalar.db.jdbc.table_metadata.connection_pool.max_idle` /
`scalar.db.jdbc.admin.connection_pool.max_idle` (also listed in `docs/configurations.mdx:189-199`).
> There are no `scalar.db.jdbc.*` properties for the import feature.
> The retry of schema operations on conflict errors (for example `ORA-08177`) is fixed at 10 attempts without backoff and has no property; see `50-backend-feature-matrix.md`.

#### JDBC URL → RDB engine (how `contact_points` is interpreted)

Driver-specific options go into the URL query string; there is no separate property for them.

| URL prefix | Engine | Notes |
|---|---|---|
| `jdbc:mysql:` | MySQL, or TiDB (detected by connecting and reading the product version) | ScalarDB connects through **MariaDB Connector/J** and appends `permitMysqlScheme=true` to the URL unless already present |
| `jdbc:postgresql:` | PostgreSQL (also AlloyDB, `docs/configurations.mdx:375-381`) | — |
| `jdbc:oracle:` | Oracle | — |
| `jdbc:sqlserver:` | SQL Server | — |
| `jdbc:sqlite:` | SQLite | Use `busy_timeout` and `journal_mode=WAL` (`docs/configurations.mdx:353-361`) |
| `jdbc:yugabytedb:` | YugabyteDB | — |
| `jdbc:mariadb:` | MariaDB | — |
| `jdbc:db2:` | IBM Db2 | — |
| `jdbc:cloudspanner:` / `jdbc:spanner:` | Spanner (PostgreSQL dialect) | Both prefixes are accepted (emulator / Omni / cloud). Credentials = service-account JSON in `password`; `username` unused; ScalarDB sets the JVM system property `ENABLE_CREDENTIALS_PROVIDER=true`. Docs example: `docs/configurations.mdx:383-394` |
| `jdbc:aws-wrapper:postgresql:` / `jdbc:aws-wrapper:mysql:` | Aurora PostgreSQL / Aurora MySQL through the AWS Advanced JDBC Wrapper | See below |
| anything else | — | `DB-CORE-10089` |

#### AWS Advanced JDBC Wrapper (`jdbc:aws-wrapper:`)

No dedicated property: you opt in by writing the prefix in `scalar.db.contact_points`. The wrapper (`4.3.0`, bundled) is used as the driver. Docs: `docs/configurations.mdx:396-434`.

- Accepted underlying URLs: `jdbc:aws-wrapper:postgresql:` and `jdbc:aws-wrapper:mysql:` only. Anything else, including MariaDB URLs and a MySQL URL that turns out to be TiDB, fails at startup with `DB-CORE-10302`.
- For MySQL, ScalarDB appends `wrapperTargetDriverDialect=mariadb-connector-j-3` unless you set it.
- Wrapper parameters (for example `failoverTimeoutMs`) go into the URL query string (`docs/configurations.mdx:420-424`).
- ScalarDB does not check that the endpoint is Aurora; non-Aurora RDS starts without error but is unsupported (`docs/configurations.mdx:430-434`).
- A failover during `commit()` surfaces as `UnknownTransactionStatusException` (outcome unknown), during CRUD as `CrudException` (`docs/configurations.mdx:428`). Failover SQLStates (`08001`, `08S02`, `08007`) are deliberately **not** treated as retryable conflicts.

#### HikariCP `exceptionOverrideClassName` is rejected

If HikariCP's `exceptionOverrideClassName` is set, startup fails with `DB-CORE-10303`. This check runs for **every** JDBC pool (all RDBs, not only Aurora). The setting can arrive without any ScalarDB property, through the `hikaricp.configurationFile` system property. Reason: ScalarDB infers "outcome unknown" from HikariCP evicting a connection whose SQLState starts with `08`; an override that keeps such connections alive would turn an unknown outcome into a definite, "safe to retry" failure.

#### Pitfalls

- The three pools are independent. With default settings one process can open up to 200 + 25 + 25 connections per storage (the admin pool is created only when an Admin is used). Size the database's `max_connections` accordingly.
- Timeout-type pool properties have **no ScalarDB default**: when unset, HikariCP 4.0.3 defaults apply (30 s / 10 min / 30 min / 0). `max_lifetime_millis` is the knob that makes connections reconnect (and re-resolve DNS) after a database failover; it does not help if the JVM caches DNS forever.
- `scalar.db.jdbc.isolation_level` is easily confused with `scalar.db.consensus_commit.isolation_level`. With `consensus-commit`, ScalarDB's isolation is the latter; the former only sets the RDB connection's level.
- MySQL driver options (for example `allowPublicKeyRetrieval=true` for `caching_sha2_password`) can only be passed in the URL query string, because there is no driver-property key. Note that the URL is handled by MariaDB Connector/J, not MySQL Connector/J.
- `jdbc:aws-wrapper:` with an unsupported engine fails at startup (`DB-CORE-10302`). A `hikaricp.configurationFile` that sets `exceptionOverrideClassName` (recommended in AWS documentation for the wrapper) makes **every** ScalarDB JDBC startup fail with `DB-CORE-10303`.
- When the wrapper reports a failover during commit, do not treat it as "not applied": `UnknownTransactionStatusException` means you must check whether the transaction committed before retrying.
- Old DBCP2-era keys (`max_idle`, `prepared_statements_pool.*`) are silently ignored apart from a WARN.

### 4.5 Multi-storage

Prefix `scalar.db.multi_storage.`.
Only when `scalar.db.storage=multi-storage`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.multi_storage.storages` | Comma-separated list of storage names defined under multi-storage | `(empty)` | — | multi-storage |
| `scalar.db.multi_storage.storages.<name>.<property>` | Sub-setting for storage `<name>`. `<property>` is a normal key below `scalar.db.` (for example `storage`, `contact_points`, `username`, `password`, `jdbc.connection_pool.min_idle`). The prefix is removed and the key is resolved as `scalar.db.<property>` for that storage | `(empty)` | **All top-level keys not starting with `scalar.db.multi_storage.` are first copied into every sub-storage**, then overridden. Nested `multi-storage` is rejected (`DB-CORE-10083`) | multi-storage |
| `scalar.db.multi_storage.namespace_mapping` | Namespace → storage name mapping, `namespace:storage` comma-separated | `(empty)` | Unknown storage name → `DB-CORE-10084` | multi-storage |
| `scalar.db.multi_storage.default_storage` | Storage used for namespaces without a mapping | `(empty)` — **effectively required** | Unset or unknown → `DB-CORE-10084` | multi-storage |
| `scalar.db.multi_storage.table_mapping` | Table → storage name mapping, `table:storage` comma-separated | `(empty)` | **Deprecated (since 3.6.0, removal in 4.0.0, WARN)**. Use `namespace_mapping` | multi-storage |

#### Pitfalls

- **Each sub-storage must set its own `storage`.** Because the top-level `scalar.db.storage=multi-storage` is inherited by every sub-storage, forgetting `scalar.db.multi_storage.storages.<name>.storage` produces the misleading "nested multi-storage" error `DB-CORE-10083`.
- Top-level keys such as `username`, `password`, `jdbc.connection_pool.*`, `cross_partition_scan.*` apply to **all** sub-storages unless overridden.
- The Coordinator table has no dedicated storage key. To place it on a specific storage, map its namespace: `namespace_mapping=coordinator:<storage>` (or the name set by `consensus_commit.coordinator.namespace`). Without a mapping it silently goes to `default_storage`.
- A mapping entry without `:` is not validated; it fails with an index error rather than a ScalarDB error message.

### 4.6 Object Storage (S3 / Azure Blob Storage / Google Cloud Storage)

Each Object Storage implementation defines its own properties.
The connection and tuning properties are documented in `docs/configurations.mdx:535-666`.
Note that the property prefixes use `_` (underscore) while the value of `scalar.db.storage` uses `-` (hyphen).

#### S3 (`storage=s3`)

Prefix `scalar.db.s3.`.
`contact_points` = `S3_REGION/BUCKET_NAME` (invalid format → exception), `username` = AWS access key, `password` = AWS secret key.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.s3.table_metadata.namespace` | Namespace for table metadata | `scalardb` | — | s3 |
| `scalar.db.s3.multipart_upload_part_size_bytes` | Part size of multipart upload (bytes) | `(empty)` (SDK default) | `Long` | s3 |
| `scalar.db.s3.multipart_upload_max_concurrency` | Maximum concurrency of multipart upload | `(empty)` (SDK default) | `Integer` | s3 |
| `scalar.db.s3.multipart_upload_threshold_size_bytes` | Size threshold to switch to multipart upload (bytes) | `(empty)` (SDK default) | `Long` | s3 |
| `scalar.db.s3.request_timeout_secs` | Request timeout (seconds) | `(empty)` (no timeout) | `Integer`. Docs: `docs/configurations.mdx:579-583` | s3 |

> A non-default `scalar.db.scan_fetch_size` logs "not applicable to S3" and is ignored.

#### Azure Blob Storage (`storage=blob-storage`)

Prefix `scalar.db.blob_storage.`.
`contact_points` = `BLOB_URI/CONTAINER_NAME` (invalid format → exception); `username` = storage account name, `password` = account key (`docs/configurations.mdx:599-609`).

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.blob_storage.table_metadata.namespace` | Namespace for table metadata | `scalardb` | — | blob-storage |
| `scalar.db.blob_storage.parallel_upload_block_size_bytes` | Block size of parallel upload (bytes) | `(empty)` (SDK default) | `Long` | blob-storage |
| `scalar.db.blob_storage.parallel_upload_max_concurrency` | Maximum concurrency of parallel upload | `(empty)` (SDK default) | `Integer` | blob-storage |
| `scalar.db.blob_storage.parallel_upload_threshold_size_bytes` | Size threshold to switch to parallel upload (bytes) | `(empty)` (SDK default) | `Long` | blob-storage |
| `scalar.db.blob_storage.request_timeout_secs` | Request timeout (seconds) | `(empty)` (no timeout) | `Integer` | blob-storage |

> A non-default `scan_fetch_size` logs a WARN and is ignored.

#### Google Cloud Storage (`storage=cloud-storage`)

Prefix `scalar.db.cloud_storage.`.
First element of `contact_points` = bucket name, `username` = GCP project ID, `password` = service account key JSON.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cloud_storage.table_metadata.namespace` | Namespace for table metadata | `scalardb` | — | cloud-storage |
| `scalar.db.cloud_storage.upload_chunk_size_bytes` | Upload chunk size (bytes) | `(empty)` (SDK default) | `Integer` | cloud-storage |

> Without `password` (service account key JSON), authentication fails with `OBJECT_STORAGE_CLOUD_STORAGE_SERVICE_ACCOUNT_KEY_NOT_FOUND`.
> A non-default `scan_fetch_size` logs a WARN and is ignored.

#### Pitfalls

- `storage=blob_storage` (underscore) is not a valid storage name and fails with `DB-CORE-10065`; the storage value is `blob-storage` / `cloud-storage`, while property prefixes are `blob_storage.` / `cloud_storage.`.
- Blob Storage splits `contact_points` at the **last** `/`, so the container name must be the last path segment.
- An empty Cloud Storage `contact_points` fails with a plain message, not a ScalarDB error code.

---

## Appendix: Items whose effective value differs from the literal default

Items easily misread from the docs or from the literal value (all shown with effective values in the tables above):

| Kind | Item | Literal default | Effective default |
|---|---|---|---|
| JDBC pool timeouts | `connection_timeout_millis` / `idle_timeout_millis` / `max_lifetime_millis` / `keepalive_time_millis` (main / table metadata / admin) | `null` | `30000` / `600000` / `1800000` / `0` (HikariCP 4.0.3 defaults; applied only when set) |
| Metadata namespace | `cosmos.table_metadata.database` / `dynamo.table_metadata.namespace` / `jdbc.table_metadata.schema` / `system_namespace_name` (Cassandra) | `null` | `scalardb` |
| Metadata namespace | `{s3,blob_storage,cloud_storage}.table_metadata.namespace` | `scalardb` (literal) | `scalardb` |
| Cosmos consistency | `cosmos.consistency_level` | `null` | `STRONG` |
| Dynamo prefix | `dynamo.namespace.prefix` | `null` | `""` |
| Consensus inheritance | `parallel_validation` / `parallel_rollback` / `async_rollback` | — | inherit `parallel_commit` / `parallel_commit` / `async_commit` |
| Coordinator namespace | `consensus_commit.coordinator.namespace` | `null` | `coordinator` |
| Cassandra port | `contact_port` | `0` | `9042` (Cassandra only) |
| Metadata cache | `metadata.cache_expiration_time_secs` | `60` | any negative = never expire; `0` = always reload |
