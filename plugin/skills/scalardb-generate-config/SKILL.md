---
name: scalardb-generate-config
description: Generate a complete ScalarDB Cluster configuration through block-by-block Q&A - Helm custom-values.yaml (Envoy + ScalarDB Cluster, indirect mode, SQL enabled) plus client scalardb.properties / scalardb_sql.properties and a secrets script. Auto-detects the demo databases started by scalardb-start-sample-db; otherwise asks for your database connection details (11 backends). Targets ScalarDB 3.18.0 / Helm chart scalardb-cluster 1.11.1.
---

# scalardb-generate-config

> Status: **v0.1.0 (2026-07-14 — plan-006 P3 initial implementation)**
> Targets ScalarDB Cluster **3.18.0**, Helm chart **scalar-labs/scalardb-cluster 1.11.1** (appVersion 3.18.0).
> Skill 3 of 8 in the scalardb-starter-skills walk-through.

## Overview

Generates everything needed to configure ScalarDB Cluster, in one project directory:

| output | purpose |
|---|---|
| `scalardb/helm/custom-values.yaml` | Helm values: Envoy block + ScalarDB Cluster block (server properties + K8s deploy settings) |
| `scalardb/config/scalardb.properties` | Client properties for the ScalarDB **Java (CRUD) API** — the single source of truth for every consumer (Spring Boot app, Schema Loader, CLI) |
| `scalardb/config/scalardb_sql.properties` | Client properties for the ScalarDB **SQL API** |
| `scalardb/secrets/create-scalardb-secrets.sh` + `license_check_cert.pem` | Creates the `scalardb-credentials` Secret (license key comes from the environment at run time) |
| `.scalardb-starter-skills.json` | Project marker + state shared with the other skills |
| `README.md` (created or appended) | What was generated, the fixed values, and an edit guide |

Guidance style: when a choice conflicts with a ScalarDB constraint, this skill **presents the symptom, the trade-offs, and the alternatives — it never silently substitutes** the user's choice. Constraint claims cite `references/` (reference docs for 3.18.0, synced copies).

### Fixed values (not asked — see the edit guide in the generated README)

| item | fixed value | rationale |
|---|---|---|
| Client mode | indirect (`envoy.enabled=true`) | Simplest for starters; direct-kubernetes needs a ServiceAccount with endpoint access |
| TLS | off (all hops) | Starter scope |
| GraphQL | off | Mutually exclusive with auth at startup (`GraphQlServer.java:49-56`) |
| Encryption at rest | off | Starter scope |
| SQL interface | **on** (`scalar.db.sql.enabled=true`) | The Spring Boot starter generates SQL API endpoints |
| Coordinator | namespace `coordinator` mapped to the **first storage** | Sensible default; `scalar.db.consensus_commit.coordinator.namespace=coordinator` |
| Storage format | `multi-storage`, even with one backend | Uniform output; `namespace_mapping` always written with `:` separator |
| Helm chart | `scalar-labs/scalardb-cluster` **1.11.1** pinned | appVersion 3.18.0; image tag pinned to 3.18.0 |
| Membership | `scalar.db.cluster.membership.type=KUBERNETES` hardcoded | Chart 1.11.1 sets only the two endpoint env vars, not the type |
| Monitoring flags | `serviceMonitor` / `prometheusRule` / `grafanaDashboard` not written | Configure kube-prometheus-stack separately if needed |

## Phase 0 — project root

1. If `./.scalardb-starter-skills.json` exists, the current directory is the project root — continue.
2. Otherwise ask:

   ```
   Q0. Project name? (a new directory <project-name>/ will be created and used
       as the project root — it will also become the Spring Boot project root)
   ```

   Validate: lowercase letters, digits, `-`. Create `<project-name>/` and write the marker:

   ```json
   { "plugin": "scalardb-starter-skills", "createdAt": "<ISO-8601>" }
   ```

   All subsequent paths are relative to this project root.

## Phase 1 — demo database detection (live, via kubectl)

If `kubectl` is available and a current context exists, run
`kubectl -n scalardb-sample-db get svc postgres mysql --no-headers 2>/dev/null`.
Apply the shared context-mismatch convention first (warn + ask if the marker records a different `k8s.context`; never auto-switch).

- **Both found** — show the endpoints and ask:

  ```
  Q1. Demo databases from scalardb-start-sample-db were detected. Use them as
      ScalarDB storages?
      1) both PostgreSQL and MySQL (recommended — demonstrates multi-storage)
      2) PostgreSQL only
      3) MySQL only
      4) no — I'll enter my own database(s)
  ```

  Adopted demo databases prefill the storage list of Phase 5:

  | storage name | backend | `contact_points` | credentials |
  |---|---|---|---|
  | `postgres` | JDBC (PostgreSQL) | `jdbc:postgresql://postgres.scalardb-sample-db.svc.cluster.local:5432/scalardb` | scalaradmin / scalaradmin (demo constants, used as script defaults) |
  | `mysql` | JDBC (MySQL) | `jdbc:mysql://mysql.scalardb-sample-db.svc.cluster.local:3306/` | scalaradmin / scalaradmin (demo constants, used as script defaults) |

- **Not found / kubectl unavailable / declined** — continue with an empty storage list; Phase 5 collects the user's own databases.

## Phase 2 — Envoy block

```
Q2. How should the Envoy endpoint be exposed? (service type)
    1) ClusterIP    — access via `kubectl port-forward` (recommended for minikube)
    2) LoadBalancer — cloud load balancer (recommended for AKS / EKS / GKE)
```

Recommend based on the platform (marker `k8s.platform` if recorded, else current-context heuristics). The choice also determines the client `contact_points` host in Phase 7:

- ClusterIP → `indirect:localhost` + README instructions for `kubectl port-forward svc/scalardb-cluster-envoy 60053:60053 -n <namespace>`
- LoadBalancer → `indirect:<ENVOY_LOAD_BALANCER_IP>` placeholder + README instructions to fill it from `kubectl get svc`

> Chart 1.11.1 does not expose an Envoy replica count in its values — do not ask about it.

## Phase 3 — ScalarDB Cluster block

| Q | item | default | notes |
|---|---|---|---|
| Q3.1 | Kubernetes namespace to install into | `scalardb-cluster` | recorded in the marker; used by the secrets script and `scalardb-start-scalardb-cluster` |
| Q3.2 | `replicaCount` | 3 (chart default) | suggest 1 for minikube |
| Q3.3 | `logLevel` | INFO | TRACE / DEBUG / INFO / WARN / ERROR |
| Q3.4 | resources | none (chart default `{}` — recommended for minikube) | if requested: requests/limits for cpu & memory |
| Q3.5 | License type | Trial | Trial → bundle `references/license-pem/trial-cert.pem`; Production → `production-cert.pem`. Copy the chosen PEM to `scalardb/secrets/license_check_cert.pem`. **Never ask for the license key value** — it is provided via env var when running the secrets script |

## Phase 4 — authentication

```
Q4. Enable ScalarDB Cluster authentication (scalar.db.cluster.auth.enabled)?
    1) no  (default — simplest for a starter)
    2) yes — the cluster creates a default superuser admin/admin at startup
             (AuthService.java:66-67); client properties will authenticate as
             admin/admin, and the README will tell you to change the password
```

When enabled, the client properties templates emit `scalar.db.cluster.auth.enabled=true` plus `scalar.db.username` / `scalar.db.password` (Java API) and `scalar.db.sql.cluster_mode.username` / `password` (SQL API), with `AUTH_USERNAME=admin`, `AUTH_PASSWORD=admin`.

## Phase 5 — storage loop

Iterate over storages (prefilled entries from Phase 1 are confirmed one by one; the user can rename them or add more). For each storage:

### Q5.1 storage name

Lowercase snake_case, unique. Used in `scalar.db.multi_storage.storages.<name>.*` and in Secret keys `STORAGES_<NAME>_USERNAME` / `_PASSWORD`.

### Q5.2 backend (11 choices)

| # | display | `…<name>.storage` | notes |
|---|---|---|---|
| 1 | PostgreSQL | `jdbc` | |
| 2 | MySQL | `jdbc` | creates one database per ScalarDB namespace; JDBC URL ends with `/` |
| 3 | Oracle | `jdbc` | service name required |
| 4 | Db2 | `jdbc` | dbname required |
| 5 | SQL Server | `jdbc` | `;databaseName=` form |
| 6 | MariaDB | `jdbc` | same URL form as MySQL |
| 7 | YugabyteDB | `jdbc` | PostgreSQL-compatible, port 5433 |
| 8 | Cassandra | `cassandra` | |
| 9 | DynamoDB | `dynamo` | region as contact_points |
| 10 | DynamoDB Local | `dynamo` | adds `dynamo.endpoint_override` automatically |
| 11 | Cosmos DB | `cosmos` | **no username emitted** (Cosmos does not use one) |

Object storage backends (s3 / cloud-storage / blob-storage) are not offered by this starter.

### Q5.3 backend-specific inputs

**JDBC (1–7)** — the JDBC backend interprets `contact_points` as a **JDBC URL** (`JdbcConfig.java:157`). Ask host / port / dbname-or-service individually and assemble:

| vendor | URL pattern | default port | dbname/service |
|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://<host>:<port>/<dbname>` | 5432 | required |
| MySQL | `jdbc:mysql://<host>:<port>/` | 3306 | **not used** (DB per namespace, `RdbEngineMysql.java:49-55`) |
| MariaDB | `jdbc:mariadb://<host>:<port>/` | 3306 | **not used** |
| Oracle | `jdbc:oracle:thin:@<host>:<port>/<service>` | 1521 | service required |
| Db2 | `jdbc:db2://<host>:<port>/<dbname>` | 50000 | required |
| SQL Server | `jdbc:sqlserver://<host>:<port>;databaseName=<dbname>` | 1433 | required |
| YugabyteDB | `jdbc:yugabytedb://<host>:<port>/<dbname>` | 5433 | required |

Do not emit `contact_port` for JDBC (it is inside the URL). Credentials: env placeholders in the Secret script (`STORAGES_<NAME>_USERNAME` / `_PASSWORD`; demo databases get `scalaradmin` as the overridable default, user databases get `<SET_ME>`).

Managed-DB note for the README: on Aurora / RDS and similar, if `CREATE DATABASE` / `CREATE SCHEMA` privileges are restricted, pre-create the database/schema for each ScalarDB namespace (see the official requirements page for per-vendor permissions).

**Cassandra (8)** — contact_points (host CSV), contact_port (default 9042), auth y/n → username/password.

**DynamoDB (9)** — region → `contact_points`; AWS access key id → username; secret access key → password; optional `dynamo.namespace.prefix` (`DynamoConfig.java:25`).

**DynamoDB Local (10)** — endpoint URL (default `http://localhost:8000`) → emitted as `dynamo.endpoint_override` (`DynamoConfig.java:19`, the **only** difference from real DynamoDB); region/credentials may be dummies.

**Cosmos DB (11)** — account endpoint URL → `contact_points`; account key → password; `emit_username=false`.

### Q5.4 cross-partition scan (per storage)

```
Q5.4a Enable cross_partition_scan for storage "<name>"? (default: no)
Q5.4b   … also enable filtering? (default: no)
Q5.4c   … also enable ordering? (JDBC storages only; default: no)
```

Constraint guidance (cite `references/`):

- **ordering on a non-JDBC storage is a startup error** (`Dynamo.java:58-61` / `Cassandra.java:51-54` / `Cosmos.java:53-56`). Do not ask Q5.4c for NoSQL; explain that it is auto-false.
- **filtering on NoSQL runs in-JVM** (fetch-then-filter, `FilterableScanner.java:46`) — OOM risk on large scans. Present the trade-off and let the user decide.

### Q5.5 "Add another storage? (y/n)" → loop or continue.

## Phase 6 — namespaces and mapping

```
Q6. Which schema do you plan to load later (scalardb-generate-schema-file)?
    1) preset A: Order-Inventory — namespaces: order, inventory  (recommended)
    2) preset B: Transfer        — namespaces: account, audit
    3) custom — enter namespace names now
    4) decide later — map only "coordinator"; unmapped namespaces fall back to
       the default storage
```

For each namespace, ask which storage it lives on (auto-assign when there is only one storage; with the two demo storages suggest spreading them, e.g. `order:postgres, inventory:mysql`). Then always write:

```
scalar.db.multi_storage.namespace_mapping=<ns1>:<st1>,…,coordinator:<first-storage>
scalar.db.multi_storage.default_storage=<first-storage>
```

Separator is **`:`** (`MultiStorageConfig.java:123` — `split(":", -1)`), never `=`. The mapping (including `coordinator`) is always written, even with one storage. Record the namespace list in the marker for `scalardb-generate-schema-file`.

## Phase 7 — render and write

Render the templates (`templates/*.tmpl`, handlebars-style: `{{TOKEN}}`, `{{#if}}…{{/if}}`, `{{#each STORAGES}}…{{/each}}` — evaluate them yourself, do not ship the tokens) and write:

```
<project-root>/
├── scalardb/
│   ├── helm/custom-values.yaml
│   ├── config/scalardb.properties
│   ├── config/scalardb_sql.properties
│   └── secrets/
│       ├── create-scalardb-secrets.sh   (chmod +x)
│       └── license_check_cert.pem       (copied per Q3.5)
├── .scalardb-starter-skills.json        (merged: see below)
├── .gitignore                           (append: scalardb/secrets/*.pem — and never commit real keys)
└── README.md                            (create or append the "ScalarDB configuration" section)
```

Key template tokens: `STORAGES[]` (`name`, `NAME_UPPER`, `backend_display`, `scalar_db_storage`, `contact_points`, `contact_port`, `emit_username`, `username_default`, `password_default`, `dynamo_endpoint_override`, `dynamo_namespace_prefix`, `cps_enabled`, `cps_filtering`, `cps_ordering`, `backend_is_jdbc`), `STORAGE_NAMES_CSV`, `NAMESPACE_MAPPING_CSV`, `FIRST_STORAGE`, `ENVOY_SERVICE_TYPE`, `REPLICA_COUNT`, `LOG_LEVEL`, `RESOURCES*`, `AUTH_ENABLED`, `AUTH_USERNAME`, `AUTH_PASSWORD`, `K8S_NAMESPACE`, `CLIENT_CONTACT_HOST`, `CLIENT_HOST_COMMENT`, `CHART_VERSION` (=1.11.1), `SCALARDB_VERSION` (=3.18.0).

Marker merge:

```json
{
  "config": {
    "chartVersion": "1.11.1",
    "scalardbVersion": "3.18.0",
    "k8sNamespace": "<Q3.1>",
    "releaseName": "scalardb-cluster",
    "envoyServiceType": "<Q2>",
    "authEnabled": false,
    "storages": [ { "name": "...", "backend": "..." } ],
    "namespaces": { "<ns>": "<storage>" },
    "generatedAt": "<ISO-8601>"
  }
}
```

README section must include: the fixed-values table with edit pointers (file + key), the storage/namespace summary, the port-forward or LoadBalancer instructions matching Q2, the Aurora/RDS permissions note (JDBC storages only), the demo-DB warning (when adopted), and the auth password-change note (when enabled).

## Phase 8 — report and hand-off

Show the generated file list and:

```
Next: /scalardb-start-scalardb-cluster
  1. runs scalardb/secrets/create-scalardb-secrets.sh
     (set SCALAR_DB_CLUSTER_LICENSE_KEY in your environment first)
  2. helm install with scalardb/helm/custom-values.yaml
```

## Input validation

| input | check | on violation |
|---|---|---|
| project name | `^[a-z][a-z0-9-]*$` | re-ask |
| storage name | lowercase snake_case, unique | re-ask |
| namespace name | lowercase snake_case | re-ask |
| JDBC host | non-empty | re-ask |
| port | integer 1–65535 | re-ask; confirm if it differs from the vendor default |
| dbname / service | required for PostgreSQL / Oracle / Db2 / SQL Server / YugabyteDB; not asked for MySQL / MariaDB | re-ask |
| DynamoDB region | `^[a-z]{2}-[a-z]+-\d$` recommended (dummy allowed for Local only) | warn |
| DynamoDB Local endpoint | http(s) URL | re-ask |
| Cosmos endpoint | `https://….documents.azure.com…` recommended | warn |
| replicaCount | positive integer | re-ask |

## references/

- `10-configuration-core.md` / `11-configuration-cluster.md` — property catalogs (3.18.0, source-cited)
- `50-backend-feature-matrix.md` — per-backend capabilities/limits
- `70-design-decisions-and-antipatterns.md` / `90-deep-dives.md` — guidance material for constraint explanations
- `sync-source.md` — provenance of the synced copies
- `license-pem/` — trial / production license-check certificates
