# ScalarDB 3.19.1 Reference — Data model and data types

Reference for schema design. Covers the structure of the data model, the ScalarDB data types, the mapping of each type
to every backend, the type restrictions on keys and indexes, and the hidden columns / tables that Consensus Commit adds.

Applies to ScalarDB 3.19.1. Official docs: `docs/data-modeling.mdx`, `docs/database-adapters.mdx`,
`docs/consensus-commit.mdx`, `docs/configurations.mdx`, `docs/scalardb-core-status-codes.mdx` (in `docs-scalardb`).

---

## 1. Structure of the data model

ScalarDB uses an extended key-value model inspired by Bigtable (`data-modeling.mdx:17`).
The hierarchy is **namespace ⊃ table ⊃ partition ⊃ record ⊃ column**. A record is identified by its
primary key = partition key + optional clustering key (`data-modeling.mdx:55-65`).

Table structure is held by `TableMetadata`:

| Concept | Field | Type | Iteration order |
|---|---|---|---|
| Column list | `columnNames` | `ImmutableLinkedHashSet<String>` | ✅ |
| Column → type | `columnDataTypes` | `ImmutableMap<String, DataType>` | — |
| Partition key | `partitionKeyNames` | `ImmutableLinkedHashSet<String>` | ✅ |
| Clustering key | `clusteringKeyNames` | `ImmutableLinkedHashSet<String>` | ✅ |
| Clustering order | `clusteringOrders` | `ImmutableMap<String, Order>` | — |
| Secondary index | `secondaryIndexNames` | `ImmutableSet<String>` | ❌ |
| Encrypted columns | `encryptedColumnNames` | `ImmutableSet<String>` | ❌ |

**Legend (iteration order):**

| Symbol | Meaning | Data structure (builder → stored) |
|---|---|---|
| ✅ | Definition order is kept (iterating returns definition order) | `LinkedHashSet` → `ImmutableLinkedHashSet` |
| ❌ | Order is not kept (iteration order is undefined) | `HashSet` → `ImmutableSet` |
| — | Not applicable (a `Map<column name, value>` used by key lookup, so iteration order does not matter) | `HashMap`/`LinkedHashMap` → `ImmutableMap` |

> `secondaryIndexNames` and `encryptedColumnNames` both come from a `HashSet`, so they keep **no order (❌)**.
> The two maps (`columnDataTypes` / `clusteringOrders`) are used by key lookup, so their order does not matter (—).
> What matters in practice: **partition key, clustering key and column names (✅) keep definition order in
> memory**; a secondary index is a set with no order (❌). Whether a backend keeps column order when it
> stores and reloads the metadata is a different question (§6.3).

- **Clustering order**: one `Order` (`ASC` / `DESC`) per column.
  `addClusteringKey(name)` defaults to ASC; `addClusteringKey(name, order)` sets it explicitly.
- **A secondary index covers a single column only** (`data-modeling.mdx:49`). It has no order.
- Consistency checks in `build()`: an exception is thrown when there are no
  columns, no partition key, or when a partition-key / clustering-key / secondary-index name is not among the
  defined columns. **No type check happens here** (type restrictions are enforced per backend, §5).
  Encrypted-column names are not checked either.

### 1.1 How the Schema Loader file maps to `TableMetadata` (format details)

The Schema Loader JSON is parsed as follows:

| JSON key | Effect |
|---|---|
| `partition-key` (array) | `addPartitionKey` in array order |
| `clustering-key` (array) | `"col"` = ASC, `"col ASC"` / `"col DESC"` (case-insensitive); any other form → parse error |
| `columns` (object) | `"col": "TYPE"`; type name is upper-cased before lookup, so `"int"` and `"INT"` both work. `"TYPE ENCRYPTED"` marks the column encrypted |
| `secondary-index` (array) | `addSecondaryIndex` per entry |
| `transaction` (boolean) | Default **true** = a transactional table (Consensus Commit metadata columns are added, §6.1) |

Accepted type names are exactly the 11 `DataType` names; anything else is rejected.

---

## 2. ScalarDB data types (11 types)

| DataType | Java type | Range / precision |
|---|---|---|
| `BOOLEAN` | `Boolean` | true / false |
| `INT` | `Integer` | 32-bit signed |
| `BIGINT` | `Long` | 64-bit signed; **±2^53 only on Cosmos DB and Object Storage** (§2.2) |
| `FLOAT` | `Float` | 32-bit IEEE 754 |
| `DOUBLE` | `Double` | 64-bit IEEE 754 |
| `TEXT` | `String` | Character string; maximum size depends on the backend (`database-adapters.mdx:322`) |
| `BLOB` | `ByteBuffer` / `byte[]` | Byte string; maximum size depends on the backend (`database-adapters.mdx:323`) |
| `DATE` | `LocalDate` | `1000-01-01` to `9999-12-31` |
| `TIME` | `LocalTime` | `00:00:00` to `23:59:59.999999` (microsecond precision) |
| `TIMESTAMP` | `LocalDateTime` | `1000-01-01 00:00` to `9999-12-31 23:59:59.999` (millisecond precision, no time zone) |
| `TIMESTAMPTZ` | `Instant` | Same range as `TIMESTAMP`, as a UTC `Instant` (millisecond precision) |

Docs: "Value ranges and precision" (`database-adapters.mdx:311-329`).

### 2.1 `of()` vs `ofStrict()` for time-related types

`TIME`, `TIMESTAMP` and `TIMESTAMPTZ` have two factories:

| Factory | Sub-precision part (below µs for TIME, below ms for TIMESTAMP/TZ) | Out-of-range value |
|---|---|---|
| `of()` | **Silently truncated** | `IllegalArgumentException` |
| `ofStrict()` | `IllegalArgumentException` | `IllegalArgumentException` |

The range checks run whenever a column value is constructed. The JDBC adapter reads values back with `ofStrict()`.

### 2.2 Value checks that only some backends apply

| Check | Backends | Error code |
|---|---|---|
| `BIGINT` must be within −2^53 … 2^53 (`9007199254740992`), on every `BIGINT` value of a Put and on key columns | Cosmos DB, Object Storage | `DB-CORE-10063` (Cosmos), `DB-CORE-10282` (Object Storage) |
| `BLOB` value must not exceed `Integer.MAX_VALUE / 4 * 3` bytes (≈1.5 GiB, Base64 limit) | Object Storage | `DB-CORE-10279` |

Docs: `database-adapters.mdx:220,303-304,319`.

### Pitfalls

- **`of()` silently drops sub-millisecond digits** of a `LocalDateTime.now()` (Java gives µs or ns). Two values
  that differ only below 1 ms become equal. Use `ofStrict()` when silent truncation would be a bug.
- **`BIGINT` IDs larger than 2^53** (for example Snowflake-style IDs, or `Long.MAX_VALUE` sentinels) work on JDBC,
  Cassandra and DynamoDB but are rejected on Cosmos DB and Object Storage. A schema that is portable across
  backends must keep `BIGINT` values within ±2^53.
- `TIMESTAMP` and `TIMESTAMPTZ` look alike but are not interchangeable (`database-adapters.mdx:329`).
  `TIMESTAMP` cannot be used at all on Cassandra (§3.1).
- `FLOAT` / `DOUBLE`: ScalarDB itself has no check against `Float.MIN_NORMAL` / `Double.MIN_NORMAL`.
  The minimums listed for Db2 (`database-adapters.mdx:97`) are enforced by Db2 itself.

---

## 3. Data type → backend native type mapping

### 3.1 NoSQL (Cassandra / Cosmos DB / DynamoDB / Object Storage)

**Design difference**: Cassandra creates real column types in DDL (direct mapping to native types).
Cosmos DB, DynamoDB and Object Storage do not give the database a typed schema: values are stored in a generic
representation (Cosmos DB = JSON document, DynamoDB = item attribute, Object Storage = JSON), and the ScalarDB
types are kept in a separate metadata store. The Cosmos / DynamoDB / Object Storage columns below are
"physical representation of the value", not DDL column types.

| ScalarDB | Cassandra (CQL) | Cosmos DB (JSON value) | DynamoDB (attribute) | Object Storage (JSON value) |
|---|---|---|---|---|
| BOOLEAN | `boolean` | JSON boolean | `BOOL` | JSON boolean |
| INT | `int` | JSON number | `N` | JSON number |
| BIGINT | `bigint` | JSON number | `N` | JSON number |
| FLOAT | `float` | JSON number | `N` | JSON number |
| DOUBLE | `double` | JSON number | `N` | JSON number |
| TEXT | `text` | JSON string | `S` | JSON string |
| BLOB | `blob` | JSON string (**Base64**) | `B` | JSON string (Base64) |
| DATE | `date` | JSON number (int epoch day) | `N` (int epoch day) | JSON number |
| TIME | `time` | JSON number (long nano-of-day) | `N` (long nano-of-day) | JSON number |
| TIMESTAMP | **not supported (exception)** | JSON number (long packed, UTC) | `N` (long packed) | JSON number |
| TIMESTAMPTZ | `timestamp` | JSON number (long packed, UTC) | `N` (long packed, UTC) | JSON number |

Base64 for BLOB and the Object Storage mapping: `database-adapters.mdx:209,295`.

- **Cassandra does not support `TIMESTAMP` (without time zone)**: `createTable` / `addNewColumnToTable` throw
  `UnsupportedOperationException` (`DB-CORE-10227`).
  Conversely, a native Cassandra `timestamp` maps to ScalarDB `TIMESTAMPTZ` on import/read.
- Cosmos DB / DynamoDB accept all 11 types. A packed value is `<epochSecond><3-digit millis>`; for negative
  values the milliseconds are complemented so that the numeric order equals the time order.
- Docs mapping tables: `database-adapters.mdx:148-160,201-213,248-260,295`.

### 3.2 JDBC mapping for non-key columns

Inheritance: **MariaDB and TiDB are MySQL** (no type-mapping override),
**YugabyteDB is PostgreSQL**, Spanner overrides part of PostgreSQL.
AlloyDB uses the `jdbc:postgresql:` URL and therefore the PostgreSQL engine.

| ScalarDB | MySQL/MariaDB/TiDB | PostgreSQL/YugabyteDB | Oracle | SQL Server | SQLite | Db2 | Spanner (PG) |
|---|---|---|---|---|---|---|---|
| BOOLEAN | `BOOLEAN` | `BOOLEAN` | `NUMBER(1)` | `BIT` | `BOOLEAN` | `BOOLEAN` | `boolean` |
| INT | `INT` | `INT` | `NUMBER(10)` | `INT` | `INT` | `INT` | `bigint`※ |
| BIGINT | `BIGINT` | `BIGINT` | `NUMBER(19)` | `BIGINT` | `BIGINT` | `BIGINT` | `bigint` |
| FLOAT | `REAL` | `REAL` | `BINARY_FLOAT` | `FLOAT(24)` | `FLOAT` | `REAL` | `real` |
| DOUBLE | `DOUBLE` | `DOUBLE PRECISION` | `BINARY_DOUBLE` | `FLOAT` | `DOUBLE` | `DOUBLE` | `double precision` |
| TEXT | `LONGTEXT` | `TEXT` | `VARCHAR2(4000)` | `VARCHAR(8000)` | `TEXT` | `VARCHAR(32672)` | `text` |
| BLOB | `LONGBLOB` | `BYTEA` | `BLOB` | `VARBINARY(8000)` | `BLOB` | `BLOB(2G)` | `bytea` |
| DATE | `DATE` | `DATE` | `DATE` | `DATE` | `INT`※ | `DATE` | `date` |
| TIME | `TIME(6)` | `TIME` | `TIMESTAMP(6)`※ | `TIME(6)` | `BIGINT`※ | `TIMESTAMP(6)`※ | `timestamp with time zone`※ |
| TIMESTAMP | `DATETIME(3)` | `TIMESTAMP` | `TIMESTAMP(3)` | `DATETIME2(3)` | `BIGINT`※ | `TIMESTAMP(3)` | `timestamp with time zone`※ |
| TIMESTAMPTZ | `DATETIME(3)` | `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP(3) WITH TIME ZONE` | `DATETIMEOFFSET(3)` | `BIGINT`※ | `TIMESTAMP(3)` | `timestamp with time zone` |

Docs: `database-adapters.mdx:59-71`.

※ Spanner INT → `bigint` (Spanner has no 4-byte integer). SQLite stores date-time
values as numbers (§4). Oracle / Db2 TIME → TIMESTAMP with a fixed date part (§4). Spanner TIME / TIMESTAMP →
time-zone-aware timestamp (no type without time zone).

- Additional JDBC URL forms do not add new mappings: `jdbc:spanner:` is accepted in addition to `jdbc:cloudspanner:`,
  and an AWS Advanced JDBC Wrapper URL `jdbc:aws-wrapper:...` selects the PostgreSQL or MySQL engine of the wrapped URL
  (only Aurora PostgreSQL / Aurora MySQL). The type mapping is therefore the same as for those engines. Details are in axis 10 / 50.

### 3.3 JDBC type changes for key columns and secondary-index columns

For a partition-key or clustering-key column, and for a secondary-index column, the JDBC adapter may use a different type
than for a non-key column (the secondary-index type is by default the same as the key type). "unchanged" = same type as a
non-key column. `size` defaults to **128**, minimum **64**; below 64 the config is rejected with
`DB-CORE-10144`. Set it with `scalar.db.jdbc.{mysql,oracle,db2}.variable_key_column_size`.

| Engine | TEXT key | BLOB key | Notes |
|---|---|---|---|
| MySQL/MariaDB/TiDB | `VARCHAR(size)` | `VARBINARY(size)` | `LONGTEXT` / `LONGBLOB` cannot be keys, so they are shrunk |
| PostgreSQL/YugabyteDB | `VARCHAR(10485760)` | unchanged (`BYTEA`) | |
| Oracle | `VARCHAR2(size)` | **exception (no BLOB key / index)** | `DB-CORE-10246` |
| SQL Server | unchanged (`VARCHAR(8000)`) | unchanged (`VARBINARY(8000)`) | |
| SQLite | unchanged | unchanged | |
| Db2 | key: `VARCHAR(size) NOT NULL`; index: `VARCHAR(size)` | **exception (no BLOB key / index)** | `DB-CORE-10228`. Every key column gets ` NOT NULL` |
| Spanner | unchanged (`text`) | unchanged | Unlike its parent PostgreSQL, no VARCHAR conversion |

Docs: `database-adapters.mdx:73-86`.

### Pitfalls

- **MySQL / Oracle / Db2 `TEXT` keys are only 128 characters by default.** A longer value fails at the database
  (column too long), not in ScalarDB. Increase `variable_key_column_size` before creating the table; changing it later
  does not alter existing tables.
- Spanner `INT` is 8 bytes physically, but ScalarDB still reads and writes it as a Java `int`.
- Oracle / Db2: `BLOB` cannot be a key or an index column (also not a condition column in Get/Scan on Oracle, and not an
  ordering column of a cross-partition scan on either).

---

## 4. Physical storage of date-time types in JDBC

JDBC stores date-time values very differently per engine. The docs show only the final SQL type; the storage mechanism
and the `time_column.default_date_component` properties are described here.

| Engine | DATE | TIME | TIMESTAMP | TIMESTAMPTZ | Notes |
|---|---|---|---|---|---|
| MySQL/MariaDB/TiDB | `LocalDate` | `LocalTime` | `LocalDateTime` | **`OffsetDateTime.toLocalDateTime()`** | The UTC wall-clock value is stored in a time-zone-less `DATETIME(3)`; read back as `LocalDateTime` → `toInstant(UTC)`. Raw SQL or a session in another time zone may see a different value |
| PostgreSQL/YugabyteDB | `LocalDate` | `LocalTime` | `LocalDateTime` | `OffsetDateTime` | Native time-zone support |
| Oracle | `LocalDate` | **`LocalDateTime` with a fixed date** | `LocalDateTime` | `OffsetDateTime` | TIME is stored in a `TIMESTAMP` with a fixed date. The date is `scalar.db.jdbc.oracle.time_column.default_date_component` (default `1970-01-01`) |
| SQL Server | **`String` (BASIC_ISO_DATE)** | `LocalTime` | **`String` (ISO_DATE_TIME)** | **`microsoft.sql.DateTimeOffset`** | DATE / TIMESTAMP are passed as text to avoid a 10-day shift before the Julian → Gregorian switch (1582-10-15) |
| SQLite | **`Integer` (epoch day)** | **`Long` (nano-of-day)** | **`Long`** | **`Long`** | Everything is numerically encoded into INT/BIGINT; not human-readable in raw SQL |
| Db2 | **`String`** | **`LocalDateTime` with a fixed date** | **`String`** | **`String`** | DATE/TIMESTAMP/TIMESTAMPTZ as string literals, TIME as TIMESTAMP with a fixed date (`scalar.db.jdbc.db2.time_column.default_date_component`). Read with `getString` and parsed manually to avoid the 10-day shift |
| Spanner | `LocalDate` | **TZ timestamp with a fixed date** | **`LocalDateTime.atOffset(UTC)`** | `OffsetDateTime` | Fixed date `scalar.db.jdbc.spanner.time_column.default_date_component` |

- The default `TIMESTAMPTZ` encoding is `atOffset(ZoneOffset.UTC)`. Reads use
  `ofStrict()` (sub-precision is an error).

### Pitfalls

- **MySQL**: the UTC wall-clock value is stored in `DATETIME(3)`. If someone changes the MySQL column to `TIMESTAMP`
  by hand, or reads it with raw SQL from a session in another time zone, the value looks shifted. Round trips through
  ScalarDB are consistent.
- **SQLite / Db2 / SQL Server**: reporting tools that read the tables directly see numbers (SQLite) or must not assume
  the JDBC driver's default conversion (Db2, SQL Server).
- Oracle / Db2 / Spanner: keep `time_column.default_date_component` at its default; it sets the fixed date part of stored
  TIME values.

---

## 5. Type restrictions on keys and secondary indexes (per backend)

The core layer (`TableMetadata.build()`) does not restrict types. Restrictions are enforced per backend when a table is
created or altered.

| Backend | Partition key | Clustering key | Secondary index | Notes |
|---|---|---|---|---|
| Cassandra | No restriction (BLOB allowed) | No restriction (BLOB allowed) | No restriction | But `TIMESTAMP` is rejected for every use |
| Cosmos DB | No check (BLOB allowed) | **BLOB not allowed** (`DB-CORE-10073`) | No check | |
| DynamoDB | **BLOB only as the last column of the partition key** (`DB-CORE-10224`) | **BLOB not allowed** (`DB-CORE-10225`) | **BOOLEAN not allowed** (`DB-CORE-10226`) | Allowed index types = the 10 types other than BOOLEAN |
| JDBC: Oracle | BLOB not allowed | BLOB not allowed | BLOB not allowed | `DB-CORE-10246` |
| JDBC: Db2 | BLOB not allowed | BLOB not allowed | BLOB not allowed | `DB-CORE-10228` |
| JDBC: others (MySQL/PG/SQL Server/SQLite/Spanner) | No restriction in ScalarDB | No restriction in ScalarDB | No restriction in ScalarDB | FLOAT/DOUBLE restrictions in the docs (YugabyteDB `database-adapters.mdx:108`, Spanner FLOAT `:123`) come from **the database itself** |
| Object Storage (S3 / Blob / GCS) | — | — | **Not supported at all, for any type** (`DB-CORE-10251`) | |

> **Note**: restrictions such as "FLOAT/DOUBLE cannot be used as keys or indexes" are database restrictions,
> not ScalarDB checks. The table creation fails inside the database.

### 5.1 Value restrictions on key columns

Keys of NoSQL backends are encoded into a single attribute or string (§6.2). That puts restrictions on **key values**,
not on types:

| Backend | Forbidden in TEXT values of… | Why | Error |
|---|---|---|---|
| DynamoDB | partition-key and clustering-key columns: `\u0000` | `0x00` terminates each TEXT component in the concatenated key bytes | `DB-CORE-10079` |
| Cosmos DB | partition-key **and clustering-key** columns: `:` `/` `\` `#` `?` | `:` joins key values; the document `id` is partition key + clustering key joined with `:`; `/ \ # ?` are not allowed in a Cosmos `id` | `DB-CORE-10145` |
| Object Storage | partition-key and clustering-key columns: `/` `!` | `/` separates the object key; `!` joins composite partition-key values | `DB-CORE-10257` |
| DynamoDB | secondary-index columns: empty `TEXT` / empty `BLOB` | GSIs do not allow empty keys | `DB-CORE-10081` |

Docs: `database-adapters.mdx:173,193,222-223,301`.

### 5.2 Type-dependent operation limits (related)

| Backend | Limit | Error |
|---|---|---|
| DynamoDB | Conditions on `BOOLEAN` columns in a conditional mutation: only `EQ`, `NE`, `IS NULL`, `IS NOT NULL` | `DB-CORE-10082` |
| Cosmos DB | Conditions on `BLOB` columns in a conditional mutation: only `EQ`, `NE`, `IS NULL`, `IS NOT NULL` | `DB-CORE-10076` |
| Oracle | `BLOB` as a Get/Scan condition column or as a cross-partition-scan ordering column | `DB-CORE-10248`, `DB-CORE-10247` |
| Db2 | `BLOB` as a cross-partition-scan ordering column | `DB-CORE-10229` |

### 5.3 Schema changes (alter) — summary

| Backend | Not supported / restricted |
|---|---|
| Cassandra | Rename column only for partition-key / clustering-key columns (`DB-CORE-10221`); alter column type, rename table, import not supported |
| Cosmos DB, DynamoDB, Object Storage | Drop column, rename column, alter column type, rename table, import not supported |
| Oracle | Alter column type only `INT` → `BIGINT` (`DB-CORE-10239`) |
| Spanner | Rename column and rename table not supported; alter column type only `BLOB` → `TEXT` (`DB-CORE-10284`) |
| TiDB, Db2 | Alter column type `BLOB` → `TEXT` not supported (`DB-CORE-10245`, `DB-CORE-10240`) |
| Db2 | Rename of a partition-key / clustering-key / secondary-index column not supported (`DB-CORE-10222`) |
| SQLite | Import (`DB-CORE-10087`) and alter column type (`DB-CORE-10238`) not supported; namespace / table names must not contain `$` (namespace separator) |

### 5.4 Repairing a table (`repairTable` / `repairCoordinatorTables`)

`repairTable` re-applies the desired `TableMetadata` to an existing table. Through the transaction admin it applies the
transaction table metadata (with the `before_*` columns and their indexes, §6.1).

- On Cassandra, `repairTable` runs `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`. DynamoDB and
  Cosmos DB `repairTable` also create a missing table / index and skip the metadata write when it is already up to date.
  Backend details: axis 50.
- `repairCoordinatorTables` is covered in §6.7.

### Pitfalls

- A Cassandra schema with a `TIMESTAMP` column fails at `createTable` with `DB-CORE-10227`. Through `repairTable` the same
  schema has no such guard and fails with an `AssertionError` instead of the user error.
- DynamoDB: a single-column partition key of type `BLOB` is allowed (it is the "last" column), but a BLOB clustering
  key is not.
- Cosmos DB: `:` in a **clustering-key** TEXT value is rejected, just like in a partition-key value.

---

## 6. Data-model pitfalls (hidden columns, encodings, column order)

### 6.1 Transaction metadata columns live in the user table

For a transactional table, Consensus Commit adds to the user-defined columns:

- 5 after-image metadata columns: `tx_id` (TEXT), `tx_state` (INT), `tx_version` (INT), `tx_prepared_at` (BIGINT),
  `tx_committed_at` (BIGINT);
- 5 before-image metadata columns: `before_tx_id`, `before_tx_state`, `before_tx_version`, `before_tx_prepared_at`,
  `before_tx_committed_at`;
- one `before_<column>` column with the same type for **every non-primary-key column**;
- a secondary index on `before_<column>` for every user secondary index on a non-primary-key column, unless
  `scalar.db.consensus_commit.index.eventually_consistent_read.enabled=true` (default `false`).

They are added physically to the same table. With transaction-metadata
decoupling (JDBC only) they go to a separate table instead (axis 50).
Reading them correctly requires ScalarDB. Docs: `consensus-commit.mdx:151`.

Reserved names: a user column named like one of the 10 metadata columns is rejected (`DB-CORE-10101`), and so is a
user column named `before_<x>` when `<x>` is another non-primary-key column (`DB-CORE-10102`).

`tx_created_at`, `tx_child_ids` and `tx_write_set` are **not** in user tables; they belong to the Coordinator table (§6.7).

### 6.2 NoSQL keys are encoded into a single attribute

DynamoDB encodes composite keys into the binary attributes `concatenatedPartitionKey` / `concatenatedClusteringKey`;
Cosmos DB joins the partition-key values with `:`; Object Storage joins composite partition-key values with `!`.
The database's native composite-key features are not used. Docs: `database-adapters.mdx:140,185,285`.

- The resulting **TEXT value restrictions** are in §5.1. Users perceive them as "ScalarDB rules".
- DynamoDB flips the sign bit of INT/BIGINT (and masks bytes for DESC order) so that byte order equals value order,
  which makes range scans on the sort key work.
- The concatenated bytes are for lookup and comparison only. Key columns are also stored as individual attributes,
  and values are decoded from those.

### 6.3 Column order is backend-dependent

- **Partition-key and clustering-key order is kept on every backend** (`TableMetadata` uses `LinkedHashSet`; DynamoDB
  stores keys as lists; Cassandra uses its own key order; JDBC uses `ordinal_position`).
- **Order of ordinary (non-key) columns is kept only on JDBC**: the metadata table stores `ordinal_position`
  (partition key, then clustering key, then the other columns in definition order) and reads with
  `ORDER BY ordinal_position`.
  DynamoDB, Cosmos DB and Object Storage store the column map in a `HashMap` (DynamoDB map attribute), so the order is
  undefined. Cassandra takes the order that the driver returns.
- **Operational rule**: on NoSQL, `SELECT *` / `Result.getColumns()` order may differ from the definition. Always name the
  columns in an `INSERT` (a values-only INSERT is risky).

### 6.4 Secondary indexes and before-image columns

Conditions on non-primary-key columns are rewritten by Consensus Commit into `(col op x) OR (before_col op x)`.
Conditions on partition-key and clustering-key columns are kept as they are. The index key of a Get/Scan with index is
not part of the conditions, so it is never rewritten. For `ScanAll`, conditions on indexed columns are not rewritten —
an intentional asymmetry. The Scan limit is not passed to the storage when Consensus Commit reads.

- The `before_*` columns of indexed columns get **their own secondary index automatically** (§6.1). Index-based
  reads use them for a "before-image index check". Docs: `consensus-commit.mdx:149-161`.
- Tables created before the check existed (before 3.16.5 / 3.17.3 / 3.18.0) lack those indexes; run `repairTable()`.
  At startup ScalarDB logs a warning for such tables under SNAPSHOT / READ_COMMITTED; under SERIALIZABLE the affected
  operations fail instead (`consensus-commit.mdx:157-160`).
- A **Get with index** is executed as an equivalent Scan with index, so that several physical rows
  matching the index value (possible while uncommitted records exist) can be recovered one by one; an exception is thrown
  only when more than one committed record matches. Behavior of reads is axis 30.
- Index selection in the SQL planner: axis 40.

### 6.5 Oracle catalog tables and ORA-08177

User tables on Oracle get `ROWDEPENDENCIES` and `INITRANS 3 MAXTRANS 255`. The ScalarDB
catalog tables (`metadata`, `virtual_tables`) are created by their own `CREATE TABLE` statements without those clauses.
With the whole pool at SERIALIZABLE, catalog writes by Schema Loader can therefore hit serialization conflicts.

- Every catalog statement (also the metadata read done by the metadata cache) is retried up to
  10 times, without backoff, when the engine reports a conflict; on Oracle that is
  ORA-08177, ORA-00060 and ORA-08176. Background: axis 90 (B11); backend detail: axis 50.

### 6.6 Record size includes the hidden columns

Each record carries the metadata columns of §6.1 and a before-image copy of every non-key column, so a record can be
roughly twice the size of the user data. Backend record limits apply to the whole record: DynamoDB 400 KB, Cosmos DB 2 MB
(`database-adapters.mdx:166,219`); Cassandra caps a mutation at 16 MB by default (`database-adapters.mdx:267`).
The "roughly twice" figure is an estimate derived from §6.1.

### 6.7 The Coordinator table (`coordinator.state`)

Consensus Commit keeps each transaction's final state in the Coordinator table, namespace `coordinator` (or
`scalar.db.consensus_commit.coordinator.namespace`), table `state`. Its columns depend on configuration in ScalarDB 3.19.1:

| Column | Type | Present when |
|---|---|---|
| `tx_id` (partition key) | TEXT | always |
| `tx_child_ids` | TEXT | `scalar.db.consensus_commit.coordinator.group_commit.enabled=true` |
| `tx_state` | INT | always |
| `tx_created_at` | BIGINT | always |
| `tx_write_set` | BLOB | `scalar.db.consensus_commit.coordinator.write_set_logging.enabled=true` |

- **Producer side**: `createCoordinatorTables` builds the metadata from the two flags at the time it runs.
  Both flags default to `false`.
- **Consumer side**: the reader treats both optional columns as optional: `tx_child_ids` only if present,
  `tx_write_set` only if present and not null. Required columns are `tx_id`, `tx_state`, `tx_created_at`. A table without
  the optional columns is therefore readable.
- **Writer side**: the write set is encoded only when the flag is on; `tx_child_ids` / `tx_write_set` are written only
  when there is a value. The write set records only the identity of each written record
  (namespace, table, partition key, clustering key), not values.
- **Turning a flag on for an existing table**: the column is missing, and "until you add the column, transactions fail
  when they write their status to the Coordinator table" (`consensus-commit.mdx:141,266`). Run Schema Loader
  `--coordinator --repair-all` (`schema-loader.mdx:345`) or `Admin.repairCoordinatorTables()`.
  `repairCoordinatorTables` adds the missing optional columns with `ALTER TABLE ADD COLUMN`
  (it keeps a column that already exists even if the flag is now off).
  There is no `Admin.upgrade()` in 3.19.1.
- Write sets identify records by namespace, table and primary-key names and values; renaming a table or a primary-key
  column, or dropping and re-creating a table with the same name, makes earlier write sets useless
  (`consensus-commit.mdx:325-327`).

### Pitfalls

- A user column named `tx_state` or `before_amount` (when `amount` exists) is rejected at table creation (§6.1).
- Raw SQL against a ScalarDB table sees `tx_*` / `before_*` columns and PREPARED rows; it must not modify them.
- **Enabling group commit or write-set logging on an existing deployment without repairing the Coordinator table**
  makes every commit fail on every backend: the commit's state Put names a column that the ScalarDB table metadata does not have, so the
  storage operation checker rejects it with `DB-CORE-10017` before it reaches the database; after the
  retries it surfaces as `UnknownTransactionStatusException` `DB-CORE-40003` (see `90` B9).
- Disabling a flag later does not drop the column; that is harmless (the reader treats the column as optional).
