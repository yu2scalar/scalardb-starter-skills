# scalardb-starter-skills

Beginner-friendly [Claude Code](https://claude.com/claude-code) skills to support your first [ScalarDB](https://scalardb.scalar-labs.com/) Cluster application.

Walk the skills in order to go from an empty Kubernetes cluster to a running Spring Boot CRUD REST application backed by ScalarDB Cluster — including the Helm values, client properties, and schema files in between, all placed in a single project directory.

Targets **ScalarDB 3.18.0**.

## Skills

Run them in this order for the full walk-through. Each skill also works standalone.

| # | Skill | What it does | Status |
|---|---|---|---|
| 1 | `scalardb-set-k8s-env` | Select a Kubernetes environment (AKS / EKS / GKE / minikube), switch kubectl context, and verify connectivity. Starts minikube for you if needed; cloud clusters must already exist. | **available** |
| 2 | `scalardb-start-sample-db` | Start demo PostgreSQL + MySQL databases in-cluster (credentials `scalaradmin` / `scalaradmin` — **demo use only**). | **available** |
| 3 | `scalardb-generate-config` | Generate ScalarDB Cluster config through block-by-block Q&A (Envoy / cluster / storages): Helm `custom-values.yaml` + client `scalardb.properties` / `scalardb_sql.properties` + secrets script. Auto-detects the demo databases. Indirect client mode, SQL enabled. | planned |
| 4 | `scalardb-start-scalardb-cluster` | Start ScalarDB Cluster via Helm using the generated values, injecting your license key as a Kubernetes Secret (never written to files), and verify the rollout. | planned |
| 5 | `scalardb-generate-schema-file` | Generate `schema.json` from a preset (Order-Inventory or Transfer) plus a schema-loading script. | planned |
| 6 | `scalardb-generate-springboot-starter` | Scaffold a Spring Boot 3.5 / Java 17 / Gradle project from a schema file (generated or your own), exposing CRUD REST APIs in **both** the ScalarDB Java API and the ScalarDB SQL API, with Swagger UI. | planned |
| 7 | `scalardb-validate-config` | Read-only audit of existing ScalarDB configuration files against a reference-backed rule catalog. | planned |
| 8 | `scalardb-stop-sample-db` | Stop the demo databases (demo data is discarded — volumes are non-persistent). | **available** |

Skills land incrementally; the Status column tracks availability.

## Install

```bash
# From Claude Code
/plugin marketplace add /path/to/scalardb-starter-skills
/plugin install scalardb-starter-skills@scalardb-starter-skills
/reload-plugins
```

## Generated project layout

All skills write into one project directory, which is also the Spring Boot project root:

```
<project-name>/
├── .scalardb-starter-skills.json    # project marker + shared state between skills
├── scalardb/
│   ├── helm/custom-values.yaml
│   ├── secrets/create-scalardb-secrets.sh
│   ├── config/scalardb.properties, scalardb_sql.properties
│   └── schema/schema.json, load-schema.sh
├── build.gradle, settings.gradle, gradlew*
└── src/main/...
```

## Repository layout

```
scalardb-starter-skills/
├── .claude-plugin/marketplace.json
├── plugin/
│   ├── .claude-plugin/plugin.json
│   └── skills/<skill-name>/         # SKILL.md + templates/ + references/
└── README.md
```

## Notes

- The demo databases and fixed credentials are for evaluation only — never use them in production.
- A valid ScalarDB Cluster license is required to start the cluster. The skills accept it at start time via an environment variable or prompt and store it only in a Kubernetes Secret.

## License

TBD.
