# ScalarDB 3.19.1 Reference — Backend Feature Matrix

Source of truth for schema design and backend selection.
Collects, on one page, how feature support differs by storage type and by JDBC engine.

Applies to ScalarDB 3.19.1. Official docs: `docs/database-adapters.mdx`, `docs/requirements.mdx`, `docs/backup-restore.mdx`, `docs/configurations.mdx`, `docs/releases/release-notes.mdx`.
Data-type mapping is in `20-data-model-and-types.md`, operation limits in `30-crud-api-and-limits.md`, SQL in `40-sql-support-and-limits.md`, error codes in `60-error-codes.md`.

> **Object Storage (S3 / Azure Blob / Cloud Storage) is still Private Preview in 3.19** (`docs/requirements.mdx:246`). Keep this in mind when choosing a backend for production.

### Legend (used throughout)

| Symbol | Meaning |
|---|---|
| ✅ | Supported (the operation runs) |
| ❌ | Not supported: ScalarDB throws an exception (usually `UnsupportedOperationException` or `IllegalArgumentException`) |
| — | Not applicable (the concept does not exist for that backend) |
| △ | Partly supported; the condition is written in the cell |
| "Delegated" | multi-storage forwards the call to the storage mapped to the namespace; the answer is that storage's answer |

**Do not confuse two meanings of "isolation" in this file.** "Isolation level" below always means the **JDBC connection isolation level** of the underlying RDBMS (`scalar.db.jdbc.isolation_level`), not the Consensus Commit isolation level (`scalar.db.consensus_commit.isolation_level`, SNAPSHOT / SERIALIZABLE / READ_COMMITTED). The Cosmos DB "consistency level" (`Consistency` in Cosmos terms) is a third, unrelated setting.

---

## 1. Storage type × feature matrix

| Feature | Cassandra | Cosmos DB | DynamoDB | JDBC | Object Storage | multi-storage |
|---|---|---|---|---|---|---|
| **Mutation atomicity unit** (`StorageInfo`) | PARTITION | PARTITION | **STORAGE** | STORAGE | PARTITION | Delegated |
| **Max mutations per atomic batch** | Unlimited | Unlimited | **100** | Unlimited | Unlimited | Delegated |
| **Consistent virtual-table read guaranteed** (`StorageInfo`) | false | false | false | **true only if connection isolation ≥ engine minimum** (§2) | false | Delegated |
| Virtual tables (needed by transaction metadata decoupling) | ❌ | ❌ | ❌ | ✅ (see §2 for SQLite) | ❌ | Delegated |
| Secondary index | ✅ | ✅ | ✅ (GSI) | ✅ | **❌** | Delegated |
| Cross-partition scan filtering | ✅ (filtered in ScalarDB) | ✅ (filtered in ScalarDB) | ✅ (filtered in ScalarDB) | ✅ | ✅ (filtered in ScalarDB) | Delegated |
| Cross-partition scan ordering | ❌ | ❌ | ❌ | **✅ (JDBC only)** | ❌ | Delegated |
| `TIMESTAMP` type (without time zone) | **❌** (use `TIMESTAMPTZ`) | ✅ | ✅ | ✅ | ✅ | Delegated |
| Import (adopt existing table) | ❌ | ❌ | ❌ | ✅ (except SQLite) | ❌ | Delegated |
| DROP COLUMN | ✅ | ❌ | ❌ | ✅ | ❌ | Delegated |
| RENAME COLUMN | △ primary-key columns only | ❌ | ❌ | ✅ (engine differences, §2) | ❌ | Delegated |
| ALTER COLUMN TYPE | ❌ | ❌ | ❌ | ✅ (engine differences, §2) | ❌ | Delegated |
| RENAME TABLE | ❌ | ❌ | ❌ | ✅ (except Spanner) | ❌ | Delegated |
| `repairTable` re-creates a missing physical table / indexes | ✅ | ✅ | ✅ | ✅ | — (no physical table) | Delegated |
| Consensus Commit (one-phase / two-phase commit) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Encryption (Cluster feature) | Storage-independent (no difference) | | | | | |

Notes:
- Virtual tables are rejected (`DB-CORE-10265`) on any storage whose atomicity unit is not STORAGE / NAMESPACE; DynamoDB (STORAGE) is rejected explicitly as well. Transaction metadata decoupling additionally requires the consistent-virtual-table-read flag.
- Cross-partition ordering is rejected twice on non-JDBC storages: at storage construction when `scalar.db.cross_partition_scan.ordering.enabled=true`, and at every `scan()` of a `ScanAll` that has orderings. Docs: `docs/configurations.mdx:715`.
- Non-JDBC filtering is done in ScalarDB: the scan is issued with no limit (`limit(0)`) and the filter columns added to the projection, then the rows are filtered inside ScalarDB.
- `TIMESTAMP` on Cassandra is rejected both when creating a table and when adding a new column.
- Cassandra DROP COLUMN drops a secondary index on the column first.
- `repairTable`: see §1.3.
- Two-phase commit: the 2PC interfaces are deprecated; see `30-crud-api-and-limits.md`. This is not a backend difference.
- Encryption: the Cluster encryption module wraps the transaction manager and contains no storage-specific code. → `11-configuration-cluster.md`.

**What "atomicity unit" means**: `STORAGE` = one storage call can apply a batch atomically across any partitions; `PARTITION` = only mutations inside one partition are atomic in one storage call. Consensus Commit hides this difference for transactions, but it decides (a) whether virtual tables / transaction metadata decoupling are possible and (b) how Consensus Commit groups its writes (see `30-crud-api-and-limits.md`).

### Pitfalls (for beginners)

- **DynamoDB is STORAGE / 100, not PARTITION / unlimited.** A single atomic batch is capped at 100 items.
- **"Cross-partition scan filtering ✅" on NoSQL does not mean the filter runs in the database.** ScalarDB removes the limit and filters rows itself, so a selective filter over a large table still reads the whole table.
- **Any ordering on a `ScanAll` fails on non-JDBC storages**, whatever the column. Ordering by clustering key inside one partition (`Scan` with a partition key) is unaffected.
- **Turning on `cross_partition_scan.ordering.enabled` with a NoSQL backend stops the storage from starting**, so never set it for non-JDBC storages.
- **Virtual tables (and therefore transaction metadata decoupling) are JDBC only**, and on JDBC only when the connection isolation level meets the engine minimum in §2.
- Encryption is the same on every backend; do not pick a backend for it.

### 1.1 Types that can be used in keys (BLOB-centric; consistent with `20 §5`)

| | Partition key | Clustering key | Secondary index |
|---|---|---|---|
| Cassandra | No restriction (BLOB allowed) | BLOB allowed | — (no type restriction in Core) |
| Cosmos DB | Allowed | **BLOB not allowed** | Allowed |
| DynamoDB | **BLOB only as the last column of a composite partition key** | **BLOB not allowed** | **BOOLEAN not allowed** |
| JDBC | Oracle / Db2: **BLOB not allowed**. YugabyteDB: **FLOAT/DOUBLE not allowed**. Spanner: **FLOAT not allowed** | Same as left | Same as left |
| Object Storage | Text values must not contain `/` or `!`; BLOB ≤ ~1.5 GiB; BIGINT within ±2^53 | Same text restriction | — (no index at all) |

Docs: `docs/database-adapters.mdx:94-125,167-168,221,301-304`.

#### Pitfalls (for beginners)

- **Cosmos DB: a colon (`:`) is rejected in *every* primary-key text value (partition key *and* clustering key)**, together with `/ \ # ?`. Natural keys such as `"2026-10-13T10:00"` or URLs fail at write time, not at table creation.
- Object Storage: the same kind of runtime-only rejection for `/` and `!`.
- Cosmos DB and Object Storage: a BIGINT outside ±2^53 is rejected. Generators that use BIGINT for snowflake-style IDs must not target these backends.
- DynamoDB: a secondary-index column cannot be set to an empty string / empty BLOB.

### 1.2 How a namespace is realized

| Storage | Realization |
|---|---|
| Cassandra | Native keyspace (1:1) |
| Cosmos DB | namespace = database, table = container |
| DynamoDB | No native concept → table name `namespace.table` (optional prefix `scalar.db.dynamo.namespace.prefix`) |
| JDBC | MySQL / MariaDB / TiDB = database (`CREATE SCHEMA`), PostgreSQL family / SQL Server / Db2 / Spanner = schema, Oracle = **user** (= schema), **SQLite = table-name prefix `ns$table`** |
| Object Storage | No native concept → hierarchical object key `<ns>/<table>/<partition key>` in a single bucket (logical separation only) |

Docs: `docs/database-adapters.mdx:33-47,134-136,181,236,281`.

#### Pitfalls (for beginners)

- **Oracle: creating a namespace creates a database user with a fixed, hard-coded password** (`"Oracle1234!@#$"`) and unlimited quota on `USERS`. The DBA should lock or change that account.
- SQLite: `$` is not allowed in namespace or table names.

### 1.3 `repairTable`

`repairTable` **creates whatever is missing — physical table, secondary indexes, metadata — and skips the metadata write when it is already up to date** (release note `docs/releases/release-notes.mdx:67`).

| Storage | Behavior |
|---|---|
| JDBC | `CREATE TABLE IF NOT EXISTS` + indexes, then metadata only if it differs (a corrupt/unreadable row counts as "differs"). Rejected for virtual tables and their source tables |
| Cassandra | `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` (Cassandra keeps its own schema; there is no ScalarDB metadata table) |
| Cosmos DB | `createContainerIfNotExists`, stored procedure installed if missing, metadata written only if different, **indexing policy compared with the container's actual policy and replaced if different** |
| DynamoDB | Table created if missing; **auto-scaling and continuous backup (PITR) are (re-)enabled unless `no-scaling` / `no-backup` are passed**; missing GSIs created; metadata written only if different |
| Object Storage | Metadata written only if different |

#### Pitfalls (for beginners)

- **DynamoDB: running `--repair-all` without `--no-backup` / `--no-scaling` turns PITR and auto-scaling back on** for the repaired table.
- **`repairTable` on a table whose physical table was dropped silently re-creates an empty table** instead of failing.
- Cosmos DB: repair is the way to re-install stored procedures after a PITR restore (`docs/backup-restore.mdx:161`); it also fixes a drifted indexing policy.

---

## 2. JDBC engine × capability matrix

Values of the per-engine capabilities. Abbreviations: My = MySQL, Ma = MariaDB, Ti = TiDB, Pg = PostgreSQL, Yb = YugabyteDB, Or = Oracle, Ss = SQL Server, Li = SQLite, Db2, Sp = Spanner (PostgreSQL dialect).
Inheritance: **MariaDB / TiDB extend MySQL; YugabyteDB / Spanner extend PostgreSQL**. **Aurora MySQL, Aurora PostgreSQL and AlloyDB have no engine of their own**: they use the MySQL / PostgreSQL engines (docs `docs/database-adapters.mdx:27`).

Engine selection is by URL prefix: `jdbc:mysql:` (MySQL or TiDB, decided by connecting and reading the product version), `jdbc:postgresql:`, `jdbc:oracle:`, `jdbc:sqlserver:`, `jdbc:sqlite:`, `jdbc:yugabytedb:`, `jdbc:mariadb:`, `jdbc:db2:`, `jdbc:cloudspanner:` **or `jdbc:spanner:`**, and `jdbc:aws-wrapper:` (§2.2). MySQL-family URLs are handled by MariaDB Connector/J; `permitMysqlScheme=true` is appended automatically.

| Capability | My | Ma | Ti | Pg | Yb | Or | Ss | Li | Db2 | Sp |
|---|---|---|---|---|---|---|---|---|---|---|
| Highest isolation level the engine supports (not enforced at runtime, see note) | SER | SER | **REP.READ** | SER | SER | SER | SER | SER | SER | SER |
| Minimum isolation for consistent virtual-table read | REP.READ | REP.READ | REP.READ | REP.READ | REP.READ | **SERIALIZABLE** | REP.READ | **READ COMM.** | REP.READ | REP.READ |
| ALTER COLUMN TYPE | Unrestricted | Unrestricted | **BLOB→TEXT not allowed** | Unrestricted | Unrestricted | **INT→BIGINT only** | Unrestricted | **None allowed** | **BLOB→TEXT not allowed** | **BLOB→TEXT only** |
| RENAME TABLE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** |
| RENAME COLUMN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **❌ for PK/CK/indexed columns** | **❌** |
| Import | ✅ | ✅ | ✅ | ✅ | ✅※ | ✅ | ✅ | **❌** | ✅ | ✅ |
| BLOB as key / index | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | **❌** | ✅ |
| BLOB column in cross-partition ordering | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | **❌** | ✅ |
| BLOB column in Get/Scan conditions | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | ✅ | ✅ |
| Index must be dropped explicitly before DROP COLUMN | No | No | No | No | No | No | **Yes** | **Yes** | No | **Yes** |
| Explicit commit for single statements | No | No | No | No | No | **Yes at SERIALIZABLE** | No | No | No | No |

Legend: SER = SERIALIZABLE, REP.READ = REPEATABLE READ, READ COMM. = READ COMMITTED. "Yes" in the last two rows is handled automatically by ScalarDB.

- **"Highest isolation" is not enforced anywhere at runtime.** TiDB's lack of SERIALIZABLE is a property of TiDB (docs `docs/database-adapters.mdx:118`), not a ScalarDB check.
- ※ YugabyteDB import is unsupported according to `docs/database-adapters.mdx:109`; ScalarDB Core does not block it. Treat it as unsupported.
- **SQLite virtual tables** are unsupported (`docs/database-adapters.mdx:114`), although ScalarDB has no SQLite-specific guard (the SQLite minimum isolation is READ COMMITTED). Treat them as unsupported.

### Pitfalls (for beginners)

- **Oracle + transaction metadata decoupling needs the connection pool at SERIALIZABLE**, which also makes every catalog statement run with explicit commit at SERIALIZABLE. This is where ORA-08177 comes from (§4.1).
- `scalar.db.jdbc.isolation_level` applies to the whole pool; if it is left unset, the database default decides whether `isConsistentVirtualTableReadGuaranteed()` is true.
- SQLite: no import, no type changes; development use only (`docs/database-adapters.mdx:113-114`).
- TiDB: no SERIALIZABLE; BLOB→TEXT not allowed. Spanner: no renames; only BLOB→TEXT.

### 2.1 Conflict (retryable) error codes

| Engine | Codes treated as conflict (`isConflict`) |
|---|---|
| My / Ma / Ti | 1213 (deadlock), 1205 (lock wait timeout) |
| Pg / Yb | SQLState 40001, 40P01 |
| Or | 8177, 60, 8176 |
| Ss | 1205 |
| Li | 5, 6 |
| Db2 | -911 |
| Sp | gRPC `ABORTED` |

Notes:
- These codes are also used by the **schema-operation (catalog) path**: the JDBC admin retries a single catalog statement up to 10 times without backoff when the code is a conflict. See §4.1.
- The AWS Advanced JDBC Wrapper failover SQLStates (`08001`, `08S02`, `08007`) are **deliberately not** conflicts, because a conflict is reported to the caller as "definitely not applied, safe to retry".

### 2.2 AWS Advanced JDBC Wrapper

Writing `jdbc:aws-wrapper:` in front of an Aurora URL routes connections through the bundled AWS Advanced JDBC Wrapper (dependency `aws-advanced-jdbc-wrapper` 4.3.0), which follows the new writer after an Aurora failover without waiting for DNS. No new property is added; the URL prefix is the switch (`docs/configurations.mdx:396-436`).

| Point | Behavior |
|---|---|
| Accepted engines | **Only `jdbc:aws-wrapper:postgresql:` and `jdbc:aws-wrapper:mysql:`** (Aurora PostgreSQL / Aurora MySQL). Anything else — including MariaDB URLs and a MySQL URL that turns out to be TiDB — fails at startup with `DB-CORE-10302` |
| Engine used | The plain PostgreSQL / MySQL engine; the prefix is stripped only to pick the engine, the pool keeps the full URL |
| Driver class | `software.amazon.jdbc.Driver` instead of the engine's driver |
| MySQL URL parameters added automatically | `permitMysqlScheme=true` (always) and `wrapperTargetDriverDialect=mariadb-connector-j-3` (wrapper URLs only), each only if the user has not set it |
| Non-Aurora endpoints | Not checked: a non-Aurora RDS endpoint starts without error but is unsupported (`docs/configurations.mdx:434`) |
| Failover during `commit()` (jdbc transaction manager) | Reported as `UnknownTransactionStatusException` when the rollback after the failed commit also fails; the original commit error is kept as a suppressed exception |

**HikariCP `exceptionOverrideClassName` is rejected for every JDBC deployment (`DB-CORE-10303`)**, including values injected through the `hikaricp.configurationFile` system property, because it would make an unknown commit outcome look like a definite failure.

#### Pitfalls (for beginners)

- **The `exceptionOverrideClassName` rejection applies outside Aurora too**: AWS documentation recommends that setting, and a deployment that has it (even only via `-Dhikaricp.configurationFile=...`) will not start.
- `jdbc:aws-wrapper:mariadb:` is rejected even though the wrapper itself accepts MariaDB. Use `jdbc:aws-wrapper:mysql:` for Aurora MySQL.
- A failover during `commit()` can leave the outcome unknown. Do not count "commit failed" as "not applied" (`docs/configurations.mdx:428`).
- IAM authentication / Secrets Manager plugins and read/write splitting are not supported (`docs/configurations.mdx:430-434`).

### 2.3 Spanner connection prefix

`jdbc:spanner:` is accepted in addition to `jdbc:cloudspanner:` (release note `docs/releases/release-notes.mdx:73`, which mentions emulator, Omni and cloud instances). ScalarDB supports Spanner only with the PostgreSQL dialect (`docs/requirements.mdx:175`).

### 2.4 Supported versions (`docs/requirements.mdx`)

Oracle 23ai / 21c / 19c, Db2 12.1 / 11.5 (Linux, UNIX, Windows only), MySQL 8.4 / 8.0, PostgreSQL 17–13, Aurora MySQL 3 / 2, Aurora PostgreSQL 17–13, MariaDB 11.4 / 10.11, TiDB 8.5 / 7.5 / 6.5, AlloyDB 16 / 15, SQL Server 2022 / 2019 / 2017, SQLite 3, **YugabyteDB 2025.2**, Spanner (PostgreSQL dialect) (`docs/requirements.mdx:42-54,59-116,128-193`).

---

## 3. Backup / restore (`docs/backup-restore.mdx`)

Decision: "single database **and** that database supports transactions (= JDBC)" → **no pause needed**; otherwise (NoSQL, or more than one database) → **explicit pause required** (Scalar Admin interface or ScalarDB Cluster drain) (`docs/backup-restore.mdx:20-33,87-91`).

| Storage | Method | Pause | Notes |
|---|---|---|---|
| JDBC (RDS / Aurora / Azure Database) | Automated backup / PITR | Not needed | `docs/backup-restore.mdx:53-55` |
| JDBC (MySQL / PostgreSQL / Db2 / SQLite) | `mysqldump --single-transaction` / `pg_dump` / `backup` / `.backup` with `.timeout` | Not needed | All ScalarDB tables including the Coordinator table must be dumped in one transaction (`docs/backup-restore.mdx:41,56-70`) |
| DynamoDB | PITR (Schema Loader enables it by default) → restore into another table | **Required** | One table at a time (restore → drop original → recreate from backup); PITR / auto-scaling settings are reset and must be configured again (`docs/backup-restore.mdx:103-105,162-180`) |
| Cosmos DB | Continuous backup (PITR) | **Required** | After restore, **set the consistency level back to STRONG** and **re-install stored procedures with `--repair-all`** (`docs/backup-restore.mdx:108-110,159-161`) |
| Cassandra | Snapshot | **Required** (for a consistent restore) | With RF ≥ 3, losing one node is recovered by repair; a cluster-wide consistent snapshot needs a pause or a full stop (`docs/backup-restore.mdx:113-117,154-156`) |
| Object Storage | AWS Backup / Azure Backup / Storage Transfer Service | **Required** | When restoring into another bucket/container, update `scalar.db.contact_points` (`docs/backup-restore.mdx:123-135,200-206`) |

Common to PITR: minimize clock drift (for example NTP), pause long enough (for example five seconds) and use the mid-time of the pause as the restore point (`docs/backup-restore.mdx:79-85`).

### Pitfalls (for beginners)

- The pause is required for DynamoDB even though its atomicity unit is STORAGE: the decision is "transactional single database", not "atomicity unit".
- DynamoDB: Schema Loader enables PITR by default. `--repair-all` also re-enables it unless `--no-backup` is passed (§1.3).
- Cosmos DB: the client side refuses any consistency level other than STRONG or BOUNDED_STALENESS (default STRONG), but the restored account's own default consistency must still be set back to STRONG by hand.

---

## 4. Typical pitfalls per storage

- **Cassandra**: no `TIMESTAMP` (use `TIMESTAMPTZ`). BLOB per mutation is capped at 16 MB by default (`max_mutation_size`). **`PutIf` + `IS NULL` on a non-existing record does not throw `NoMutationException`; it silently succeeds** (different from every other adapter, `docs/database-adapters.mdx:267-268`). LWT has a performance cost; commit log must be batch / group; do not read from asynchronous replicas (`docs/database-adapters.mdx:269-271`). Dropping an indexed column drops the index automatically.
- **Cosmos DB**: 2 MB per record including Consensus Commit metadata; BIGINT within ±2^53; **`: / \ # ?` not allowed in any primary-key text value** (§1.1); BLOB conditions only `EQ / NE / IS NULL / IS NOT NULL`; consistency Strong or Bounded Staleness; single-region write (`docs/database-adapters.mdx:219-228`). After a PITR restore: STRONG + `--repair-all`. Cosmos error messages contain only `statusCode`, `subStatusCode` and the short message; the full diagnostics stay on the attached `CosmosException` cause. Exceptions raised while a scanner fetches the next page are wrapped in `ExecutionException` rather than escaping as raw `CosmosException`.
- **DynamoDB**: **atomicity unit STORAGE / 100 mutations per batch**. Item 400 KB; BLOB only as the last column of a composite partition key; no BOOLEAN index; BOOLEAN conditions only 4 operators; single primary region; no schema-evolution DDL (`docs/database-adapters.mdx:166-173`).
- **JDBC**: large engine differences (BLOB key: Oracle/Db2 not allowed; FLOAT/DOUBLE key: YugabyteDB not allowed; FLOAT key: Spanner not allowed). ALTER type depends on the engine. **SQLite: no import, development only**; **TiDB: no SERIALIZABLE**; **Spanner: no renames**. Admin operations retry conflicts (§2.1). The table-existence check queries the system catalog (`information_schema.tables`, `ALL_TABLES`, `SYSCAT.TABLES`, `sqlite_master`) instead of `SELECT`ing from the table, so a missing table does not produce an error in the database server log.
- **Object Storage (Private Preview)**: **no secondary index at all**. One partition = one object, so many clustering-key rows per partition make the object large. Primary-key text must not contain `/` or `!`; BLOB ≤ ~1.5 GiB; BIGINT within ±2^53; namespace separation is logical only; no import / drop column / rename / alter type / rename table (add column only) (`docs/database-adapters.mdx:285-309`).
- **multi-storage**: inherits every constraint of the mapped storage (atomicity unit included). Routing is per namespace (`scalar.db.multi_storage.namespace_mapping`); `table_mapping` is deprecated and logs a warning. A multi-storage definition cannot nest another multi-storage.

### 4.1 Oracle schema operations and ORA-08177

- User tables get Oracle-specific options: `ROWDEPENDENCIES` and `INITRANS 3 MAXTRANS 255`.
- The catalog tables (`metadata`, `virtual_tables`) are created with plain, engine-independent DDL.
- When the pool runs at SERIALIZABLE (required for transaction metadata decoupling on Oracle), catalog writes can hit ORA-08177. ScalarDB retries every catalog statement (execute / update / query, including the metadata-cache-miss path on the data side) on a conflict up to 10 times, without backoff, rolling back between attempts when explicit commit is used (release note `docs/releases/release-notes.mdx:32`). The Oracle failure is triggered by an index **leaf block split**; two consecutive retries were the worst case measured.
- The retry also applies to every other engine (MySQL 1213/1205, PostgreSQL 40001/40P01, …).

#### Pitfalls (for beginners)

- The retry is **per statement**. A multi-statement operation such as `createTable` (DDL + several catalog inserts) is still not atomic; a non-conflict failure in the middle still leaves a partial state (only one statement is retried at a time). Use `repairTable` to recover (§1.3).
- After 10 failed retries the error is thrown and a `Giving up on a conflicting statement` warning is logged; each retry logs `Retrying a conflicting statement` at WARN. Repeated warnings point to a leaked or external transaction holding catalog locks.
