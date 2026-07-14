# Canonical code patterns

Verbatim copies (authoring-time, 2026-07-14) of the reference implementations
`demo-java-api` and `demo-sql-api`. The example table
`ProductTestAllTypesTest` (namespace `product_test`, partition key + clustering
key, one column of every ScalarDB data type) exists purely to demonstrate the
patterns — the skill generates the same class set **per table of the user's
schema.json**, substituting names, keys, and types.

| directory | contents | generated per |
|---|---|---|
| `javaapi/` | ScalarDbConfig (TransactionFactory), Controller / Service / Repository using Get/Insert/Update/Upsert/Delete/Scan builders | table |
| `sqlapi/` | ScalarDbSqlConfig (SqlSessionFactory), SqlApiRetryConfig, SqlRepository base, Controller / Service / Repository using SQL + prepared statements | table |
| `shared/` | RetryProperties, CoreApiRetryConfig (retryable-exception policy incl. cause-chain check), model, DTO, ModelMapper mapper, ApiResponse, ResponseStatusDto, CustomException | model/dto/mapper per table; the rest once |

Package names in these files (`com.example.demo_java_api` / `demo_sql_api`)
are replaced with the generated project's package. Keep the behavior exactly:
RetryTemplate around each transaction, rollback in the catch path,
`UnknownTransactionStatusException` is never auto-retried, error-code mapping
in `determineErrorCode`.
