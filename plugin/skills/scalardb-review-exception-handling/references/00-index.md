# ScalarDB 3.19.1 Reference — index

This directory describes the configuration, data model, API/SQL support and limits of ScalarDB **3.19.1**.
It is the reference set used by `scalardb-starter-skills`.

Applies to ScalarDB 3.19.1 (Core, Cluster and SQL). Official docs (`docs-scalardb`, directory `docs/`, version label `3.19`):

- Docs (Core configuration): `docs/configurations.mdx`, `docs/database-configurations.mdx`
- Docs (Cluster configuration): `docs/scalardb-cluster/scalardb-cluster-configurations.mdx`
- Docs (API): `docs/api-guide.mdx`, `docs/scalardb-sql/sql-api-guide.mdx`, `docs/scalardb-sql/grammar.mdx`
- Docs (data model): `docs/data-modeling.mdx`, `docs/database-adapters.mdx`
- Docs (status codes): `docs/scalardb-core-status-codes.mdx`, `docs/scalardb-cluster/scalardb-cluster-status-codes.mdx` and the other `*-status-codes.mdx` pages

`page.mdx:line` references point to these official docs pages.

## Axis files

| File | Axis | Main consumers (skills) |
|---|---|---|
| `10-configuration-core.md` | Core configuration properties (common / transaction managers / Consensus Commit / per storage) | generate-config, validate-config |
| `11-configuration-cluster.md` | Cluster configuration properties (node / client / gRPC / auth / ABAC / encryption / replication / embedding / **Transaction Coordinator**) | generate-config, validate-config |
| `20-data-model-and-types.md` | Data model, data types, per-backend type mapping, Coordinator table | generate-schema-file, generate-springboot-starter |
| `30-crud-api-and-limits.md` | CRUD / transaction API and limits (scan, index, isolation, 2PC, **Global Transaction**) | generate-springboot-starter |
| `40-sql-support-and-limits.md` | SQL DDL/DML support, limits, pushdown and in-memory processing | generate-springboot-starter, validate-config |
| `50-backend-feature-matrix.md` | Per-storage feature / limit matrix | generate-config, generate-schema-file |
| `60-error-codes.md` | Status / error code catalog (structure, category → action, frequent codes) | generate-springboot-starter, validate-config (error-log analysis) |
| `70-design-decisions-and-antipatterns.md` | Design decision flow + anti-patterns (AP-n), the material skills turn into Q&A and checks | all skills |
| `90-deep-dives.md` | Design deep dives | all skills |

## Column definitions (configuration property tables)

| Column | Meaning |
|---|---|
| Property | Fully qualified property name |
| Description | Purpose and behavior of the property |
| Default | Value effectively applied when the property is not set. `(empty)` means disabled / not specified. |
| Notes | Related properties, preconditions, special behavior, deprecations. |
| Group | When the property applies (`base` / `option` / storage name / `consensus-commit` / `cluster` / `auth` / `tls` / …) |

## Common tags and symbols

| Tag / symbol | Meaning |
|---|---|
| ✅ / ❌ / — / △ | Supported / not supported / not applicable / partially supported; each file's legend gives the exact meaning for its tables |
| **Pitfalls** | What beginners actually trip over for the feature in that section |

## Scope

- Core (OSS) + ScalarDB SQL + ScalarDB Cluster (gRPC / auth / ABAC / encryption / replication / embedding / Transaction Coordinator).
- **Analytics (Spark integration etc.) is out of scope.**
