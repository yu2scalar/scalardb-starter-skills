---
name: scalardb-validate-config
description: Read-only audit of an existing ScalarDB Cluster configuration - Helm custom-values.yaml, client scalardb.properties / scalardb_sql.properties, and schema.json - against a reference-backed rule catalog. Detects startup-blocking constraints (NoSQL + cross-partition ordering, auth + GraphQL), namespace-mapping mistakes, cross-file inconsistencies, and AI-leverage lints (property-name typos, enum violations, homoglyphs). Reports findings with severity, recommendation, and citations; never modifies files.
---

# scalardb-validate-config

> Status: **v0.1.0 (2026-07-14 — plan-006 P7 initial implementation)**
> Targets ScalarDB Cluster **3.18.0**. Skill 7 of 9 in the scalardb-starter-skills walk-through.

## Overview

A **read-only** audit. The user supplies up to four files (any subset works — do not require a complete set); the skill parses them, applies the rule catalog, and emits one Markdown report table. Rule semantics come from the synced 3.18.0 reference docs in `references/` — cite them (file + section/table row) in every finding so the user can read the context. The skill **never modifies files**.

### What this skill does NOT do

- Fix anything (report only)
- Connect to a live cluster / run helm
- Audit Spring Boot `application.properties` or generated Java code
- Version-compatibility checks against a running deployment

## Phase 0 — collect targets

```
Q0. Which files should be audited? (blank line to finish; any subset is fine)
    Defaults when a .scalardb-starter-skills.json marker is present:
      scalardb/helm/custom-values.yaml
      scalardb/config/scalardb.properties
      scalardb/config/scalardb_sql.properties
      scalardb/schema/schema.json
```

Role detection (case-insensitive, by content when the name is ambiguous): YAML with a `scalardbCluster:` key → Helm values (extract `scalardbClusterNodeProperties` for the property rules); properties with `scalar.db.sql.connection_mode` → SQL client; other properties with `scalar.db.transaction_manager` → Java client; JSON whose top-level keys look like `namespace.table` → schema. Unreadable/missing file → finding `R0` (Error) and continue.

## Rule catalog

Severity: **Error** = will fail at startup or cannot work as written; **Warning** = works but risky, redundant, or likely unintended; **Info** = advisory.

### Server properties (from `scalardbClusterNodeProperties`)

| id | check | severity | basis |
|---|---|---|---|
| S1 | `multi_storage.namespace_mapping` entries use `:` separator (an `=` inside an entry is the classic mistake) | Error | `MultiStorageConfig.java:123` — `split(":", -1)` |
| S2 | mapping contains a `coordinator:<storage>` entry and `consensus_commit.coordinator.namespace` is set consistently | Warning | starter convention; coordinator tables land on `default_storage` otherwise |
| S3 | `multi_storage.default_storage` names a storage defined in `multi_storage.storages` | Error | reference `10-configuration-core.md` |
| S4 | every storage listed in `multi_storage.storages` has `.storage` and `.contact_points` | Error | reference `10-configuration-core.md` |
| S5 | non-JDBC storage with `cross_partition_scan.ordering.enabled=true` → startup exception | Error | `Dynamo.java:58-61` / `Cassandra.java:51-54` / `Cosmos.java:53-56` |
| S6 | non-JDBC storage with `cross_partition_scan.filtering.enabled=true` → in-JVM fetch-then-filter, OOM risk on large scans | Warning | `FilterableScanner.java:46` |
| S7 | `cluster.auth.enabled=true` together with `graphql.enabled=true` → GraphQL server refuses to start | Error | `GraphQlServer.java:49-56` |
| S8 | JDBC storage `contact_points` is a `jdbc:` URL; `contact_port` present on a JDBC storage is ignored (noise) | Error / Warning | `JdbcConfig.java:157` |
| S9 | MySQL/MariaDB URL carries a database name (ScalarDB creates one DB per namespace — URL should end with `/`); PostgreSQL/Db2/SQL Server/YugabyteDB URL missing the dbname; Oracle missing service name | Warning / Error | `RdbEngineMysql.java:49-55` and the per-vendor RdbEngine classes |
| S10 | Cosmos storage with a `username` (unused by Cosmos) | Info | `CosmosConfig.java` (no username read) |
| S11 | literal secrets in the file: `licensing.license_key`, storage `password` etc. carrying literal values instead of `${env:...}` references | Warning | secret hygiene (starter writes env refs + Secret) |
| S12 | `licensing.license_key` / `license_check_cert_pem` (or `_path`) present (missing → node won't start) | Error | `ClusterNodeWithLicenseCheckerConfig.java:12-16` |
| S13 | `cluster.membership.type` present (KUBERNETES for the Helm chart; the chart does not inject it) | Warning | chart 1.11.1 deployment template |
| S14 | `jdbc:mysql:` URL without `allowPublicKeyRetrieval=true` and without TLS options (`sslMode=` / `useSSL=`) — fails at startup against MySQL 8.x default `caching_sha2_password` (`RSA public key is not available client side`) | Warning | MariaDB Connector/J `CachingSha2PasswordPlugin`; observed in starter E2E 2026-07-17 |

### Helm values (outside the properties block)

| id | check | severity | basis |
|---|---|---|---|
| H1 | `scalardbCluster.secretName` set when the properties use `${env:...}` for credentials | Error | chart deployment `envFrom` (values → env only via this Secret) |
| H2 | `envoy.enabled=true` when clients use `indirect:` contact points | Error | indirect mode goes through Envoy |
| H3 | `image.tag` (or chart pin) consistent with the intended ScalarDB version | Info | chart appVersion mapping |

### Client properties (Java / SQL)

| id | check | severity | basis |
|---|---|---|---|
| C1 | Java: `transaction_manager=cluster`; SQL: `sql.connection_mode=cluster` | Error | `ClusterClientConfig.java:16` / `ClusterClientSqlConfig.java:19` |
| C2 | contact points carry a valid mode prefix (`indirect:` / `direct-kubernetes:`) and a non-empty target | Error | `ClusterClientConfig.java:68-111` |
| C3 | auth cross-file: server `cluster.auth.enabled` vs each client's `cluster.auth.enabled` (+ username/password present when enabled) | Warning | reference `11-configuration-cluster.md` §2 |
| C4 | Java and SQL clients point at the same host/port | Warning | cross-file consistency |

### Schema (schema.json)

| id | check | severity | basis |
|---|---|---|---|
| N1 | every schema namespace appears in `namespace_mapping` (otherwise its tables silently land on `default_storage`) | Warning | `MultiStorageConfig` fallback behavior |
| N2 | user tables in the `coordinator` namespace | Warning | collides with coordinator tables |
| N3 | tables without `transaction: true` | Info | starter targets transactional tables |
| N4 | key columns referenced by `partition-key` / `clustering-key` exist in `columns`; types are valid ScalarDB types | Error | reference `20-data-model-and-types.md`* |

*`20-data-model-and-types.md` is not bundled with this skill — cite `50-backend-feature-matrix.md` or note the source when reporting N4.

### AI-leverage lints (all audited files)

| id | check | severity |
|---|---|---|
| L1 | unknown `scalar.db.*` property key — fuzzy-match against the key tables in `references/10-configuration-core.md` and `11-configuration-cluster.md`, report the nearest known key as the typo candidate | Warning |
| L2 | enum-valued properties (isolation level, serializable strategy, storage type, log level, `membership.type`, client auth type, …) hold a value listed in the reference tables | Error |
| L3 | non-ASCII / homoglyph characters in property keys or values (e.g. full-width colon in `namespace_mapping`, U+2010 hyphens) | Error |

## Report format

One table, sorted Error → Warning → Info:

```
| severity | rule | file:line | finding | recommendation | citation |
```

Follow with a one-paragraph summary (counts per severity; "no findings — configuration is consistent with the 3.18.0 reference" when clean). Cite reference doc sections (they are Japanese — quote the property key rows verbatim and explain in English).

## references/

- `10-configuration-core.md` / `11-configuration-cluster.md` — property catalogs (key tables for L1/L2)
- `40-sql-support-and-limits.md` / `50-backend-feature-matrix.md` / `60-error-codes.md` — supporting matrices
- `70-design-decisions-and-antipatterns.md` / `90-deep-dives.md` — constraint background for recommendations
- `sync-source.md` — provenance of the synced copies
