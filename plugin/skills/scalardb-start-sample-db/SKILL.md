---
name: scalardb-start-sample-db
description: Start demo PostgreSQL and MySQL databases inside the current Kubernetes cluster (namespace scalardb-sample-db) so ScalarDB Cluster has storages to connect to. Fixed credentials scalaradmin/scalaradmin, no persistence — demo use only. scalardb-generate-config auto-detects these databases at run time.
---

# scalardb-start-sample-db

> Status: **v0.1.0 (2026-07-14 — plan-006 P2 initial implementation)**
> Targets ScalarDB 3.18.0. Skill 2 of 8 in the scalardb-starter-skills walk-through.

## Overview

Deploys a demo **PostgreSQL 16** and **MySQL 8.4** (official images, plain manifests, `emptyDir` volumes) into namespace `scalardb-sample-db` of the currently selected Kubernetes cluster, waits for both to become ready, and prints their in-cluster endpoints and credentials.

`scalardb-generate-config` detects these databases **live** (by listing Services in `scalardb-sample-db`) — no state file is required for the hand-off.

### What this skill does NOT do

- Provide production-grade databases (fixed credentials, no persistence, no TLS — demo only)
- Persist data: volumes are `emptyDir`; **all data is lost when a pod restarts**
- Create a project directory or the marker file
- Install or configure ScalarDB itself

## Phase 0 — preflight

1. Verify `kubectl` is on PATH and `kubectl config current-context` returns a context.
2. If `./.scalardb-starter-skills.json` exists and records `k8s.context` different from the current context, **warn and ask** (shared convention): "Recorded context is X, current is Y. Continue with Y, or switch back to X?" Never switch automatically.
3. Show the current context and confirm:

   ```
   Q1. The demo databases will be deployed into context "<current-context>",
       namespace "scalardb-sample-db". Proceed? (y/n)
   ```

## Phase 1 — check for an existing deployment

Run `kubectl get namespace scalardb-sample-db` .

- If the namespace exists, show `kubectl -n scalardb-sample-db get deploy,svc,pod` and ask whether to (a) leave it as is and just re-print the connection info (default), or (b) re-apply the manifest (safe; `kubectl apply` is idempotent). Never delete anything here — that is `scalardb-stop-sample-db`'s job.
- If it does not exist, continue.

## Phase 2 — apply the manifest

```
kubectl apply -f <skill-dir>/templates/sample-db.yaml
```

The manifest (`templates/sample-db.yaml`) contains: the namespace, a Secret `sample-db-credentials` (scalaradmin/scalaradmin), PostgreSQL 16 (database `scalardb`) and MySQL 8.4 Deployments with readiness probes and `emptyDir` volumes, ClusterIP Services `postgres` and `mysql`, and a MySQL init ConfigMap that grants the demo user global privileges (ScalarDB creates one database per namespace on MySQL).

## Phase 3 — wait for readiness

```
kubectl -n scalardb-sample-db rollout status deployment/postgres --timeout=180s
kubectl -n scalardb-sample-db rollout status deployment/mysql   --timeout=180s
```

If a rollout times out, show `kubectl -n scalardb-sample-db describe pod` and `logs` for the failing pod, explain the likely cause (image pull, insufficient node resources), and stop.

## Phase 4 — report

Print the following (and merge a `sampleDb` section into `./.scalardb-starter-skills.json` **only if that file already exists**):

```
Demo databases are running in namespace scalardb-sample-db:

| database   | in-cluster endpoint                                   | initial database | credentials              |
|------------|-------------------------------------------------------|------------------|--------------------------|
| PostgreSQL | postgres.scalardb-sample-db.svc.cluster.local:5432    | scalardb         | scalaradmin / scalaradmin|
| MySQL      | mysql.scalardb-sample-db.svc.cluster.local:3306       | (per namespace)  | scalaradmin / scalaradmin|

*** DEMO USE ONLY ***
- Credentials are fixed and well-known. Never expose these databases outside the cluster.
- Storage is emptyDir: ALL DATA IS LOST when a pod restarts.

Next: run /scalardb-generate-config — it will detect these databases and offer
to use them as ScalarDB storages. Stop them later with /scalardb-stop-sample-db.
```

Marker merge shape (only when the file exists):

```json
{
  "sampleDb": {
    "namespace": "scalardb-sample-db",
    "status": "running",
    "postgres": { "host": "postgres.scalardb-sample-db.svc.cluster.local", "port": 5432, "database": "scalardb" },
    "mysql":    { "host": "mysql.scalardb-sample-db.svc.cluster.local",    "port": 3306 },
    "startedAt": "<ISO-8601 timestamp>"
  }
}
```
