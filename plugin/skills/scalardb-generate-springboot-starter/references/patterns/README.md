# Canonical code patterns

Reference implementations of a Java API and a SQL API CRUD application on
Spring Boot 4.1, with the official-style retry policy (Spring Retry library style
and Spring Framework built-in style) and unified error-code handling. The
example table `ProductTestAllTypesTest` (namespace `product_test`, partition
key + clustering key, one column of every ScalarDB data type) exists purely to
demonstrate the patterns — the skill generates the same class set **per table
of the user's schema.json**, substituting names, keys, and types.

## Retry styles (choose ONE per generated project)

| style | retry configs | service pattern | dependency |
|---|---|---|---|
| **Spring Framework built-in** (default, Boot 4+) | `builtinretry/CoreApiRetryConfig` + `builtinretry/SqlApiRetryConfig` (`org.springframework.core.retry`, builder-constructed RetryPolicy) | `builtinretry/*Service` — no-arg lambda, checked `RetryException` unwrapped to its cause | none (built into Framework 7) |
| Spring Retry library | `shared/CoreApiRetryConfig` + `sqlapi/SqlApiRetryConfig` (custom `SimpleRetryPolicy` with cause-chain scan) | `javaapi/*Service` / `sqlapi/*SqlService` — `execute(context -> …)`, original exception propagates | `org.springframework.retry:spring-retry:2.0.13` (explicit — not in the Boot 4 BOM) |

Both styles implement the SAME official-style policy: exclusions first
(anywhere in the cause chain) because they subclass the retryable base types —
Core API excludes `UnknownTransactionStatusException` /
`UnsatisfiedConditionException` from the `TransactionException` family; SQL API
excludes `UnknownTransactionStatusException` / `AuthenticationException` /
`AuthorizationException` from the `SqlException` family. Everything else in the
family is retried with bounded attempts + exponential backoff
(`RetryProperties`, shared by both styles).

| directory | contents | generated per |
|---|---|---|
| `javaapi/` | ScalarDbConfig (TransactionFactory), Controller / Service / Repository using Get/Insert/Update/Upsert/Delete/Scan builders (Service = Spring Retry style) | table |
| `sqlapi/` | ScalarDbSqlConfig (SqlSessionFactory), SqlApiRetryConfig (Spring Retry style), SqlRepository base, Controller / Service / Repository using SQL + prepared statements | table |
| `builtinretry/` | Built-in-style mirrors: CoreApiRetryConfig, SqlApiRetryConfig, Java-API Service, SQL-API Service (all other classes are shared between styles) | configs once; services per table |
| `shared/` | RetryProperties (style-agnostic), CoreApiRetryConfig (Spring Retry style), model, DTO, ModelMapper mapper, ApiResponse, ResponseStatusDto, CustomException | model/dto/mapper per table; the rest once |

Package names in these files (`com.example.demo_java_api` / `demo_sql_api`)
are replaced with the generated project's package. Keep the behavior exactly:
RetryTemplate around each transaction, rollback in the catch path — except on
`UnknownTransactionStatusException`, which is never rolled back and never
auto-retried (the commit may already have been applied): the handler logs it
with the transaction ID and throws `CustomException(9200)` whose message carries
that ID for manual verification — and error-code mapping in `determineErrorCode`.

## Error codes (both styles, both APIs)

`determineErrorCode` scans the whole cause chain; a `CustomException` code set
closer to the source (e.g. repository not-found) is preserved:

| code | meaning |
|---|---|
| 9100 | `UnsatisfiedConditionException` (Java API only) |
| 9200 | `UnknownTransactionStatusException` — no rollback, never auto-retried; the message carries the transaction ID for manual verification (HTTP 500) |
| 9300 | other `TransactionException` (Java) / `SqlException` (SQL) — retries exhausted |
| 9400 | other `RuntimeException` |
| 9500 | anything else |
| 9600 | `AuthenticationException` / `AuthorizationException` (SQL API only) |
| 9700 | not-found (thrown by repositories as `CustomException`) → HTTP 404 |
| 9800 | scenario business abort (preset scenario endpoints only; `ScenarioException`, transaction aborted, never retried) → HTTP 409 — see SKILL.md Phase 2.5 |
