# ScalarDB 3.19.1 Reference — SQL support and limits

This file covers the DDL/DML scope of ScalarDB SQL (`scalardb-sql`), the syntax the grammar accepts, and
**which work is pushed down to the backend database and which work ScalarDB does itself** (that is, how much data
is read and where out-of-memory (OOM) risk comes from).

Applies to ScalarDB 3.19.1. Official docs (`docs-scalardb`): **the canonical SQL syntax reference is `docs/scalardb-sql/grammar.mdx`**
(`sql-api-guide.mdx` explains only the Java SQL API, not the syntax).

Related: transaction behavior underneath SQL is in `30-crud-api-and-limits.md`; data types in `20-data-model-and-types.md`;
backend capabilities in `50-backend-feature-matrix.md`; Cluster auth in `11-configuration-cluster.md`.

> **Ground rule for design**: ScalarDB SQL is "a subset of standard SQL plus ScalarDB-specific operational syntax". SELECT is limited to
> **a flat query over one table plus explicit JOINs**. There are **no** subqueries, UNION, DISTINCT, CTEs, window functions, CASE,
> arithmetic expressions, or IN lists. Values are reduced to "a literal or a bind marker" (§4).

### Legend (used in all tables of this file)

| Symbol / term | Meaning |
|---|---|
| ✅ | Supported / happens |
| ❌ | Not supported (the grammar has no rule, or the planner rejects it) |
| — | Not applicable |
| `DB-SQL-1nnnn` / `0nnn` | ScalarDB SQL error code. `SqlError` codes are written as the 4-digit ID (for example, `0042`); the full code is `DB-SQL-10042` for the USER_ERROR category |
| GET / SCAN / SCAN_WITH_INDEX / SCAN_ALL | The four scan types the planner chooses. SCAN = partition scan; SCAN_ALL = cross-partition scan |
| pushdown | The work is done by the storage layer (ScalarDB Core `Get`/`Scan`, and for JDBC by the database's SQL) instead of by the SQL layer in memory |
| client side | Inside the process that runs the SQL planner: the application JVM (direct mode) or **the Cluster node** (ScalarDB Cluster runs the same direct-mode planner) |

Do not confuse the SQL `WITH` clause (operation attributes, ABAC tags) with a CTE (`WITH x AS (...)`), which does not exist.

---

## 1. Supported statements (grammar top level)

| Category | Statements |
|---|---|
| DDL | CREATE/DROP NAMESPACE, CREATE/DROP TABLE, TRUNCATE TABLE, CREATE/DROP INDEX, CREATE/DROP/TRUNCATE COORDINATOR TABLES, ALTER TABLE (ADD/DROP/RENAME COLUMN, ALTER COLUMN TYPE, RENAME TO) |
| DML | INSERT, UPSERT, UPDATE, DELETE, SELECT |
| DCL | CREATE/ALTER/DROP USER, CREATE/DROP ROLE, GRANT/REVOKE (privileges, roles, admin option) |
| ABAC | CREATE/DROP/ENABLE/DISABLE of policies, levels, compartments, groups, namespace/table policies; user tag assignment (Enterprise) |
| Transaction control | BEGIN / START TRANSACTION [READ ONLY\|READ WRITE], COMMIT, ROLLBACK, ABORT; **JOIN, SUSPEND, RESUME, PREPARE, VALIDATE are deprecated (see §8.1)** |
| Session / metadata | USE, SET MODE (TRANSACTION \| TWO_PHASE_COMMIT_TRANSACTION — the latter deprecated), SHOW NAMESPACES/TABLES/USERS/GRANTS/ROLES/ROLE USERS/ROLE GRANTS/ABAC…, DESCRIBE |

---

## 2. DDL

Docs: `docs/scalardb-sql/grammar.mdx`.

### 2.1 CREATE TABLE / CREATE INDEX

```
CREATE TABLE [IF NOT EXISTS] [ns.]table ( col type [PRIMARY KEY] [ENCRYPTED], ... [, PRIMARY KEY (...)] )
   [WITH CLUSTERING ORDER BY (col ASC|DESC, ...) [AND options] | WITH options]
```

- Three primary-key forms: a single column `PRIMARY KEY (c1)`; `PRIMARY KEY (pk, ck1, ck2…)` (the first column is the
  partition key); `PRIMARY KEY ((pk1, pk2…), ck1…)` (the columns in the inner parentheses form a composite partition key).
- **A secondary index cannot be declared in CREATE TABLE.** Use a separate `CREATE INDEX [IF NOT EXISTS] ON t (col)` statement, which takes **one column only**.
- `ENCRYPTED` on a column makes it an encrypted column (requires Cluster encryption).
- Only 11 type keywords exist: `BOOLEAN/INT/BIGINT/FLOAT/DOUBLE/TEXT/BLOB/DATE/TIME/TIMESTAMP/TIMESTAMPTZ`.
  There is no `VARCHAR`, `DECIMAL`, `CHAR`, `NUMERIC`, `UUID`, or `JSON`.

### 2.2 ALTER TABLE restrictions (important)

The SQL layer accepts the syntax and delegates to the core admin; whether it works is decided below the SQL layer.

| ALTER | Restrictions |
|---|---|
| ADD COLUMN | No functional restriction; docs warn that it can be slow (table rebuild on some RDBs) (`docs/scalardb-sql/grammar.mdx:623-631`) |
| ALTER COLUMN TYPE | **The most restricted.** Partition key, clustering key and secondary-index columns are rejected. Allowed conversions: **INT→BIGINT, FLOAT→DOUBLE, any type→TEXT** (TEXT→anything is rejected). Rejected on Cassandra, Cosmos DB, DynamoDB, Object Storage and SQLite. Oracle: INT→BIGINT only. Db2 and TiDB: BLOB→TEXT rejected. **Spanner: BLOB→TEXT only** |
| DROP COLUMN | Partition-key and clustering-key columns rejected. Rejected on Cosmos DB, DynamoDB, Object Storage; allowed on Cassandra and JDBC |
| RENAME COLUMN | Rejected on Cosmos DB, DynamoDB, Object Storage. **Cassandra: only partition-key/clustering-key columns can be renamed**. Db2: PK/CK/index columns cannot be renamed. **Spanner: not supported at all** |
| RENAME TO (table) | Rejected on all non-JDBC backends. **Spanner (a JDBC backend) also rejects it** |

→ Backend details are also in `20-data-model-and-types.md` and `50-backend-feature-matrix.md`.

#### Pitfalls

- "JDBC means everything works" is wrong for Spanner (`jdbc:cloudspanner:` and `jdbc:spanner:` URLs): RENAME COLUMN and RENAME TO fail, and ALTER COLUMN TYPE allows only BLOB→TEXT.
- There is no way to "undo" a widening: TEXT can never be converted to anything else.
- Cassandra's rename rule is the opposite of what people expect: only key columns can be renamed.

---

## 3. DML

### 3.1 INSERT / UPSERT / UPDATE / DELETE

| Statement | Syntax | Restrictions |
|---|---|---|
| INSERT | `INSERT INTO t [(cols…)] VALUES (expr…)[, (expr…)]… [WITH attributes]` | No WHERE. **Every primary-key column must be given and must not be NULL** (`0050`). Values are **literals or bind markers only**. No `INSERT ... SELECT`. Uses core `Insert` for all rows in one `mutate` call |
| UPSERT | Same shape with `UPSERT INTO` | Same primary-key rule. Inserts or updates |
| UPDATE | `UPDATE t [AS a] SET col = expr, … [WHERE …] [WITH …]` | WHERE optional. SET values are literals or bind markers only. **No ORDER BY / LIMIT** |
| DELETE | `DELETE FROM t [AS a] [WHERE …] [WITH …]` | WHERE optional. **No ORDER BY / LIMIT** |

- UPDATE/DELETE find target rows with the same scan-type selection as SELECT (§6), projecting only the primary-key columns.
  A full primary key is not required; without it the plan can fall back to a cross-partition scan.
- **When the WHERE clause is a single AND-list that contains only primary-key equality predicates**, UPDATE/DELETE do **not read** the row:
  they issue one `Update`/`Delete` with `updateIfExists`/`deleteIfExists` in one-operation mode. If the row does not exist, the condition failure is swallowed and the
  statement reports 0 affected rows — no error.
- Otherwise the matching rows are read first (the whole result list, §5.3), then updated/deleted **one operation per row** in the same transaction.

### 3.2 SELECT clauses

```
SELECT projections FROM t [AS a] [JOIN …]… [WHERE …] [GROUP BY …] [HAVING …] [ORDER BY …] [LIMIT …] [WITH …]
```

| Clause | What can be written |
|---|---|
| projection | `*` / `column [AS alias]` / `function(...) [AS alias]` |
| WHERE | Operators `= <> != < > <= >=`, `BETWEEN..AND` (parsed into `>= AND <=`), `[NOT] LIKE [ESCAPE]`, `IS [NOT] NULL`. **Only DNF or CNF** (no free nesting; parentheses are required when mixing). The left side must be a column — a function is rejected with `0096`. Encrypted columns are rejected (`0071`). A NULL value is only allowed with IS [NOT] NULL (`0045`) |
| JOIN | **Only INNER / LEFT [OUTER] / RIGHT [OUTER]** (no FULL, CROSS, NATURAL). See §3.3 |
| GROUP BY | **A list of column references only** (no expressions, no ordinals). **An unqualified name that matches a projection column alias is accepted and resolves to that column**. A table-qualified name (`t.x`) does not match a projection alias |
| HAVING | DNF/CNF. The key is a column, an aggregate function, or an alias, and it **must appear in the projections** (`0088`) |
| ORDER BY | Column or function plus `ASC`/`DESC` (default ASC). **Without aggregation**: only columns of the FROM table (no alias, no function: `0097`). **With aggregation**: column, alias or aggregate function that appears in the projections (`0084`). No arbitrary expressions, no OFFSET |
| LIMIT | Integer literal or bind marker; the value must be an INT (`0047`). No OFFSET/FETCH. **`LIMIT 0` (and a negative value) means "no limit"**: the limit is applied only when `> 0` |
| WITH | Operation attributes (`'key' = 'value' AND …`) and ABAC read tags. They are attached to the underlying core operations |

### 3.3 JOIN restrictions

- **Equality joins only**: `ON col = col` (no inequality, OR, or literal comparison; several predicates may only be combined with AND).
- The join predicates must cover **all primary-key columns of the joined table, or exactly one secondary-index column** of it
  (`0067`). Other join errors: improperly specified predicate `0064`, type mismatch `0065`, same column twice `0066`.
- **RIGHT OUTER JOIN can only be the first join** (`0063`). Internally the planner swaps the tables and runs it as a LEFT OUTER JOIN
  from the right-hand table.
- The only algorithm is **index nested-loop join** (primary-key lookup or secondary-index lookup).
- **With JOIN, WHERE and ORDER BY may use only columns of the scanned table** (the FROM table; a joined-table column gives `0042`).
  **When the first join is RIGHT OUTER JOIN, the scanned table is the table named in the RIGHT OUTER JOIN clause, so WHERE/ORDER BY must use that table's columns instead**
  (`docs/scalardb-sql/grammar.mdx:863`). Projections and GROUP BY may use joined-table columns.

### 3.4 Aggregate functions

- Only **`COUNT` / `SUM` / `MIN` / `MAX` / `AVG`** are available. The grammar accepts any name `func(...)`,
  but an unregistered name fails at planning time with `UNKNOWN_FUNCTION` (`0083`).
- **`DISTINCT` is not in the grammar**, so `COUNT(DISTINCT x)` is a parse error.
- `*` is accepted only by `COUNT`. Result types: COUNT→BIGINT;
  SUM (numeric only)→BIGINT or DOUBLE; AVG (numeric only)→DOUBLE; MIN/MAX→same type, BLOB rejected.
  Bind markers are not allowed as function arguments (`0093`).
- Aggregate functions without GROUP BY mean an implicit single group. Non-aggregated projected columns must appear in GROUP BY (`0082`).

#### Pitfalls (§3)

- `UPDATE t SET v = 1 WHERE pk = 1` on a non-existent row succeeds with 0 rows; it does not insert and does not fail. Use UPSERT when "insert or update" is intended.
- `LIMIT 0` returns **all** rows, not zero rows.
- `SELECT ... ORDER BY total` where `total` is a projection alias works only with GROUP BY/aggregation; without aggregation it fails with "column not found".
- With RIGHT OUTER JOIN first, writing a WHERE condition on the FROM table is rejected (`0042`), which looks backwards to most SQL users.

---

## 4. What the grammar does not have (compared with standard SQL)

Each item below is a parse error.

| Feature | Status |
|---|---|
| Subquery / CTE (`WITH x AS`) | ❌ (FROM is a single table reference plus JOINs) |
| UNION / INTERSECT / EXCEPT | ❌ |
| DISTINCT | ❌ |
| Window functions (OVER / PARTITION BY) | ❌ |
| CASE expression | ❌ |
| Arithmetic (`+ - * /`), expressions, column-to-column comparison | ❌ (the right side of a WHERE predicate is a literal or bind marker only; column = column exists only in JOIN ON) |
| IN (...) predicate | ❌ (`IN` is a keyword only in ABAC statements) |
| EXISTS predicate | ❌ (`EXISTS` is used only in `IF [NOT] EXISTS`) |
| OFFSET / FETCH | ❌ (paging is LIMIT only) |
| FULL OUTER / CROSS JOIN / USING clause | ❌ (`USING` is used only in ABAC policy statements) |
| VIEW / SEQUENCE / TRIGGER / PROCEDURE | ❌ |
| INSERT ... SELECT | ❌ (VALUES only) |
| CAST / conversion functions / NULLS FIRST / COLLATE | ❌ |

**Literals**: string `'...'` (escape a quote by doubling it; double quotes are not strings), integer and decimal
numbers (no exponent notation), `TRUE`/`FALSE`, **BLOB `X'hex'`**, `NULL`, and bind markers `?` / `:name`. There are no typed
date/time literals (`DATE '...'`); pass dates and times as strings or bind markers, which are parsed according to the column type:

| Column type | String format |
|---|---|
| DATE | `YYYY-MM-DD` |
| TIME | `HH:MM:SS[.fffffffff]` |
| TIMESTAMP | `YYYY-MM-DD HH:MM:SS[.fffffffff]` — **a space, not `T`** |
| TIMESTAMPTZ | `YYYY-MM-DD HH:MM:SS[.fffffffff][ ]Z` — **UTC `Z` only, no offsets**; the space before `Z` is optional |

#### Pitfalls (§4)

- Numeric literal typing is strict: an integer literal (`5`) is accepted for INT, BIGINT and DOUBLE columns but **not for FLOAT**; write `5.0`
  (error `0053`).
- An ISO-8601 timestamp such as `'2026-01-01T10:00:00'` or `'...+09:00'` fails to parse; use a bind marker with a Java time value when in doubt.

---

## 5. Pushdown vs work done by ScalarDB (data volume and OOM risk) ★important

If you do not know **which work the backend database does and which work ScalarDB does in memory**, a query that looks cheap can pull a whole table
into memory, causing OOM or heavy database load.

### 5.1 Where each clause runs

| Clause / processing | Where it runs | Data-volume risk |
|---|---|---|
| WHERE, **key equality** (full partition key, full primary key, or one indexed column with `=`) | **Pushdown** as `Get`/partition `Scan`/index `Scan` (§6) | Low |
| WHERE, other predicates **together with a key equality** | Pushdown as a filter (`where`) on the same `Get`/`Scan` | Low–medium |
| WHERE **without any usable key equality** (range only, non-key, LIKE, `!=`, IS NULL, OR over different key values) | **SCAN_ALL** (cross-partition scan) with the predicates as a filter. For JDBC the filter becomes SQL `WHERE`; for other backends the core filters in memory (see `30-crud-api-and-limits.md`). **Needs `scalar.db.cross_partition_scan.filtering.enabled=true`** (§5.4) | **High** |
| JOIN | **Client side** (index nested-loop join). All left-side rows of the scan are looked up in **one `batch` call** | Medium–high (one lookup per left row) |
| GROUP BY / aggregates (COUNT, …) | **Client side**. The aggregation state is bounded by the number of groups, **but the scan below it has already returned all matching rows as one list** (§5.3) | **High** |
| HAVING | **Client side** — only together with aggregation | Same as aggregation |
| ORDER BY **without aggregation** | **Pushdown**: passed to the `Scan` as orderings. For a partition scan it must follow the clustering order (§6); otherwise the query becomes SCAN_ALL with ordering, which needs `cross_partition_scan.ordering.enabled=true` and a **JDBC** backend (§5.4) | Low (partition) / high (SCAN_ALL) |
| ORDER BY **with aggregation** | **Client side** (sorts the aggregated rows); not passed to the scan | Same as aggregation |
| LIMIT, **no JOIN and no aggregation** | Passed to the core `Scan`. See §5.2 for what Consensus Commit does with it | Low |
| LIMIT **with JOIN or aggregation** | **Client side**. The scan gets **no limit**, so all matching rows are read first and cut in memory | **Very high** |
| Database-specific functions / expressions | Not available (only the 5 aggregates) | — |

### 5.2 What Consensus Commit adds (linked with `30`)

- Non-key predicates are rewritten into an OR with the matching `before_` columns, so the storage returns "rows that match now" **plus** prepared rows whose
  before-image matches; the final filter runs in ScalarDB. For `ScanAll`, indexed columns are not rewritten.
  → This is the root cause of "I added a WHERE but it is not faster / it reads everything".
- **The storage query carries no LIMIT**: Consensus Commit sets the storage limit to 0 because recovery and the before-image filter may drop rows.
  It also **clears projections** (all columns are read).
  However, ScalarDB **stops iterating the storage scanner once it has `limit` surviving rows**.
  - So "all rows are fetched" is too strong. The database executes the query without LIMIT (so it may still sort or
    scan a lot, especially with ORDER BY), but rows are pulled through the JDBC cursor with `scalar.db.scan_fetch_size` (default 10)
    and reading stops early. Whether the driver really streams depends on the JDBC driver.

### 5.3 The result is always materialized in memory

- The SQL layer reads through `transaction.get`/`transaction.scan`, which return a complete `List`, and hands all rows up at once.
- The SQL result set then collects every output row before it is returned.
- So **every** SELECT holds its full result (and, for JOIN/aggregation, its full intermediate input) in memory — in the application JVM in direct mode, or
  **in the Cluster node's JVM** for ScalarDB Cluster. There is no streaming cursor in the SQL layer.

### 5.4 Cross-partition scan settings that SQL depends on

| Setting (core) | Default | Needed when | Error if missing |
|---|---|---|---|
| `scalar.db.cross_partition_scan.enabled` | `true` | Any SCAN_ALL (including UPDATE/DELETE without a usable key) | `DB-CORE-10006` |
| `scalar.db.cross_partition_scan.filtering.enabled` | `false` | SCAN_ALL **with any WHERE predicate** (the SQL layer passes every WHERE predicate as a `Scan` condition) | `DB-CORE-10008` |
| `scalar.db.cross_partition_scan.ordering.enabled` | `false` | SCAN_ALL **with ORDER BY** (including a partition-key query whose ORDER BY does not match the clustering order, §6) | `DB-CORE-10007`. Non-JDBC backends refuse to start when it is enabled |

- Filtering or ordering cannot be enabled while `cross_partition_scan.enabled=false`.
- The same three switches can be set **per statement** with operation attributes, because SQL attaches the `WITH` attributes to the core `Scan`:
  `SELECT * FROM t WHERE c = 1 WITH 'db-cross-partition-scan-filtering-enabled' = 'true'`. The keys are documented for the Java API
  (`docs/api-guide.mdx:1619-1621`); they work from SQL as well.
- In ScalarDB Cluster these are node-side settings (the planner runs on the node).

> **Guidance for beginners**: assume that "aggregation, JOIN, ORDER BY on non-clustering columns, and non-key WHERE may bring
> everything into memory". Before using them on a large table, check that the key design already narrows the rows. **LIMIT is not a safety net** with JOIN or
> aggregation (§5.1), and even without them every result is held in memory (§5.3).
> → Directly related to `70-design-decisions-and-antipatterns.md` AP-4 (routine use of cross-partition scans).

---

## 6. WHERE → scan type selection

The planner chooses the scan type from the WHERE clause. The WHERE clause is first normalized
to DNF (CNF is expanded by a cartesian product). **Only equality predicates are promoted to a key or index**:
the column must appear **exactly once** in every AND-list, with `=`, and with the **same value as in
the first AND-list**.

| Condition (must hold in every OR branch) | Scan type chosen | Speed |
|---|---|---|
| All partition-key columns `=` and all clustering-key columns `=` | `GET` | Fastest (1 row) |
| All partition-key columns `=`, and ORDER BY is empty **or** a prefix of the clustering key in the clustering order (all columns in the same direction, or all reversed) | `SCAN` (single partition) | Fast |
| No ORDER BY, and some indexed column `=` (one column) | `SCAN_WITH_INDEX` | Medium |
| None of the above (range only, non-key only, `OR` with different key values, ORDER BY with an index, ORDER BY not following the clustering key, etc.) | **`SCAN_ALL`** (cross-partition scan + filter) | **Slow**; needs the settings in §5.4 |

- **A clustering-key range** becomes the scan boundary only when the plan is `SCAN` (the partition key is fully fixed by `=`) and the clustering-key predicates
  form a proper prefix: leading columns with `=`, then at most one column with one lower and/or one upper bound.
  Other predicates stay as filters.
- If the OR branches have different clustering-key ranges, the planner still uses one partition `SCAN`, but with **no** clustering boundary and all
  non-partition-key predicates as an OR filter.
- `WHERE pk = 1 OR pk = 2` (different values) is not "uniquely specified" → `SCAN_ALL`.
- `WHERE pk = 1 AND pk > 0` (the key column appears twice) is not promoted → `SCAN_ALL`.
- An indexed column plus ORDER BY disables `SCAN_WITH_INDEX` → `SCAN_ALL`.
- `WHERE pk = 1 ORDER BY non_clustering_col` → not `SCAN` → `SCAN_ALL` with ordering (needs `ordering.enabled`, JDBC only).

---

## 7. SQL-specific pitfalls (for beginners)

1. **Range predicates are never key/index lookups** — `pk > 10`, and `>=`/BETWEEN/LIKE/`!=` on an indexed column, all become `SCAN_ALL` + filter. Only `=` is promoted.
2. **A clustering-key range works only when the partition key is fully fixed with `=`**; otherwise `SCAN_ALL`.
3. **An index scan cannot be combined with ORDER BY** (`SCAN_WITH_INDEX` is disabled).
4. **`OR` over different partition-key values is a full scan.**
5. **There are no composite indexes** (one column per index).
6. **JOIN conditions are `=` only and must cover the joined table's full primary key or one indexed column** (`0064`–`0067`).
7. **With JOIN, WHERE/ORDER BY may only use the scanned table's columns** (`0042`); with a leading RIGHT OUTER JOIN, that is the RIGHT OUTER JOIN table.
8. **RIGHT OUTER JOIN only as the first join** (`0063`).
9. **With JOIN/GROUP BY/aggregates, LIMIT is applied client side** — the scan is unbounded (§5.1). Dangerous on large tables.
10. **GROUP BY, aggregates, HAVING and JOIN always run client side**; ORDER BY runs client side only with aggregation (§5.1).
11. **A non-key WHERE is only a filter**; under Consensus Commit the storage returns extra rows (before-image OR) and gets no LIMIT; and a SCAN_ALL with WHERE needs `cross_partition_scan.filtering.enabled=true` (§5.2, §5.4).
12. **GROUP BY takes column references (a projection alias is accepted); no DISTINCT, expressions or ordinals. SUM/AVG are numeric only; MIN/MAX reject BLOB.**
13. **IN lists, subqueries, UNION, CTEs, window functions, CASE and arithmetic are not in the grammar** (parse error).
14. **Every result is held in memory** — in the app (direct mode) or in the Cluster node (§5.3).
15. **`LIMIT 0` means no limit** (§3.2).
16. **An integer literal does not fit a FLOAT column; TIMESTAMP strings use a space, TIMESTAMPTZ must end with `Z`** (§4).
17. **UPDATE/DELETE by full primary key do not fail for a missing row** — they silently affect 0 rows (§3.1).

---

## 8. Other (DCL / ABAC / transaction control / types / API)

- **DCL**: `CREATE/ALTER/DROP USER`, `CREATE/DROP ROLE`, `GRANT/REVOKE` (privileges `SELECT/INSERT/UPDATE/DELETE/CREATE/DROP/TRUNCATE/ALTER/GRANT OPTION/ALL`, roles).
  User options include `AUTH_METHOD NONE | USERPASS[, OIDC]` (→ `11-configuration-cluster.md`,
  `docs/scalardb-cluster/scalardb-auth-with-sql.mdx`).
- **ABAC** (Enterprise): management SQL for policies, levels, compartments, groups (→ `11`).
- **Transaction control**: `BEGIN` / `START TRANSACTION [READ ONLY|READ WRITE] [WITH …]`, `COMMIT`/`ROLLBACK`/`ABORT`, `SET MODE`. Semantics are the same as `30-crud-api-and-limits.md`.
- **Types**: 11 types (§2). Values are passed as literals or bind markers. Type↔backend mapping: `20-data-model-and-types.md`.

### 8.1 Deprecated items (scheduled for removal in 3.20.0)

| Item | Deprecated in | Runtime warning |
|---|---|---|
| `SET MODE TWO_PHASE_COMMIT_TRANSACTION` / `TransactionMode.TWO_PHASE_COMMIT_TRANSACTION` | 3.19.0 | WARN once per JVM when the mode is set: "The TWO_PHASE_COMMIT_TRANSACTION transaction mode is deprecated as of release 3.19.0 and will be removed in release 3.20.0" |
| `PREPARE`, `VALIDATE` statements; `SqlSession.prepare()/validate()`; `SqlTwoPhaseCommitTransactionManager`, `SqlTwoPhaseCommitTransaction` | 3.19.0 | No log |
| `JOIN`, `SUSPEND`, `RESUME` statements (also in the normal transaction mode); `SqlSession.join/suspend/resume`; `SqlTransactionManager.join/resume` | 3.19.1 | WARN once per JVM on the first of these statements: "The JOIN, SUSPEND, and RESUME statements are deprecated as of release 3.19.0 and will be removed in release 3.20.0" |
| Spring Data for ScalarDB: `prepare/validate/suspend` on repositories, `twopc` package | 3.19.0 / 3.19.1 | — |

Docs: `docs/scalardb-sql/grammar.mdx:2936,2968,2993,3085,3219,3244`.

### 8.2 Other behavior that affects SQL users

- **Read-only begin for read-only work**: a one-shot SELECT that cannot run in one-operation mode (JOIN or aggregation) begins the internal transaction with
  `beginReadOnly`. A multi-statement `executeBatch` begins a read-only
  transaction only when every DML entry is a SELECT. A simple SELECT runs in
  one-operation mode through the manager-level `get`/`scan`.
- **Statement cache**: the parsed-statement cache stores only statements that contain bind markers; literal-only SQL is not cached.
  The cache is **off by default**: `scalar.db.sql.statement_cache.enabled` (default `false`) and
  `scalar.db.sql.statement_cache.size` (default 100).
- **Credentials in the `WITH` clause** (ScalarDB Cluster with auth): `auth-type`, `auth-userpass-username`, `auth-userpass-password`, `auth-oidc-jwt-access-token`
  can be given in the `WITH` clause of a statement executed outside a transaction, or of `BEGIN`/`START TRANSACTION`. The Cluster client strips them before sending.
  Docs: `docs/scalardb-sql/grammar.mdx:917`,
  `docs/scalardb-cluster/scalardb-auth-with-sql.mdx:197-278`. Details belong to `11-configuration-cluster.md`.
  - Pitfall (from the docs): inside a transaction, auth attributes on individual statements are **not** treated as credentials and are forwarded as ordinary attributes
    (`docs/scalardb-cluster/scalardb-auth-with-sql.mdx:274`); in `executeBatch`, only the first statement's auth attributes count (`scalardb-auth-with-sql.mdx:276`).
- **Spring Data for ScalarDB**: `ScalarDbJdbcConfiguration` is ordered with `@AutoConfigureBefore` against Spring Boot's
  `DataSourceTransactionManagerAutoConfiguration` and `JdbcTemplateAutoConfiguration` (Boot 3 and Boot 4 class names) so that its `@Primary` beans win.
