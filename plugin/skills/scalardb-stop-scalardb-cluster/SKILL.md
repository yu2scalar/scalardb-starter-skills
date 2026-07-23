---
name: scalardb-stop-scalardb-cluster
description: Stop the ScalarDB Cluster started by scalardb-start-scalardb-cluster via helm uninstall. Lets the user choose between uninstall-only (Secret and namespace kept for a quick restart, default) and full cleanup (Secret and namespace removed). Backend database data and schemas are never touched.
---

# scalardb-stop-scalardb-cluster

> Status: **v0.2.1 (2026-07-23 — aligned to unified plugin versioning, no functional change; v0.1.0 2026-07-17 = plan-006 A1 initial implementation)**
> Targets ScalarDB Cluster **3.18.0** / Helm chart **scalar-labs/scalardb-cluster 1.11.1**.
> Skill 8 of 9 in the scalardb-starter-skills walk-through.

## Overview

Tears down the ScalarDB Cluster release that `scalardb-start-scalardb-cluster` installed. Two scopes, chosen by the user:

- **(a) uninstall only** (default): `helm uninstall` the release. The `scalardb-credentials` Secret and the namespace stay, so `/scalardb-start-scalardb-cluster` can bring the cluster back without re-injecting the license key.
- **(b) full cleanup**: additionally delete the `scalardb-credentials` Secret and (after a final check) the namespace. Restarting later re-runs the secrets script, which needs `SCALAR_DB_CLUSTER_LICENSE_KEY` in the environment again.

Either way, **data and schemas stored in the backend databases are untouched** — stopping ScalarDB Cluster stops the serving layer only. If you also want to remove the demo databases, run `/scalardb-stop-sample-db` **after** this skill (stop the cluster first so it does not error against vanished storages).

### What this skill does NOT do

- Touch the `scalardb-sample-db` namespace or any backend database (that is `/scalardb-stop-sample-db` / the user's own DB operations)
- Delete data or schemas inside the storages
- Remove the `scalar-labs` Helm repo from the local Helm config
- Delete generated project files (`scalardb/`, the Spring Boot project, the marker)

## Phase 0 — preflight

1. `kubectl` and `helm` must be on PATH.
2. If `./.scalardb-starter-skills.json` exists, read `cluster.releaseName` and `cluster.namespace` (fall back to `config.releaseName` / `config.k8sNamespace`). If there is no marker, ask for the release name and namespace (both default `scalardb-cluster`).
3. Apply the shared context-mismatch convention (warn + ask when the marker's `k8s.context` differs from `kubectl config current-context`; never auto-switch) — uninstalling in the wrong cluster is the failure mode this guards against.

## Phase 1 — show what will be stopped and choose the scope

1. Run `helm status <releaseName> -n <namespace>`. If the release does not exist, report "nothing to stop" (show `helm list -n <namespace>` so the user can spot a different release name) and finish.
2. Show `kubectl -n <namespace> get deploy,svc,pod` and ask:

   ```
   Q1. Stop ScalarDB Cluster release "<releaseName>" in namespace "<namespace>"
       of context "<current-context>"?
       1) uninstall only — keep the credentials Secret and the namespace so the
          cluster can be restarted without re-injecting the license key  (default)
       2) full cleanup   — also delete the scalardb-credentials Secret and the
          namespace (restart will need SCALAR_DB_CLUSTER_LICENSE_KEY again)
       3) cancel
   Backend database data and schemas are NOT deleted in either case.
   ```

## Phase 2 — uninstall (both scopes)

```
helm uninstall <releaseName> -n <namespace>
kubectl -n <namespace> wait --for=delete pod -l app.kubernetes.io/instance=<releaseName> --timeout=180s
```

(If the label matches nothing, verify with `kubectl -n <namespace> get pods` instead.) If pods linger past the timeout, show their status and stop with advice rather than force-deleting.

## Phase 3 — full cleanup (scope 2 only)

1. `kubectl -n <namespace> delete secret scalardb-credentials`
2. Namespace removal, guarded:
   - If the namespace is `default` or starts with `kube-`, **never delete it** — report that only the Secret was removed and finish.
   - Otherwise show what is still in it (`kubectl -n <namespace> get all,secret,configmap`) and confirm:

     ```
     Q2. Namespace "<namespace>" still contains the resources above. Delete the
         whole namespace? (y/n)
     ```

   - On `y`: `kubectl delete namespace <namespace> --wait=true --timeout=180s`. If deletion hangs on finalizers, show the namespace status and advise rather than force-removing finalizers.

## Phase 4 — report

- Verify: `helm status <releaseName> -n <namespace>` must return "release: not found"; for scope 2 with namespace deletion, `kubectl get namespace <namespace>` must return NotFound.
- If `./.scalardb-starter-skills.json` exists, merge into the marker: `{ "cluster": { "status": "stopped", "stoppedAt": "<ISO-8601>" } }` (keep `releaseName` / `namespace` for history).
- Remind the user:
  - Backend data and schemas are still in the storage databases; restarting the cluster brings them back as-is.
  - Restart with `/scalardb-start-scalardb-cluster` (after full cleanup, `SCALAR_DB_CLUSTER_LICENSE_KEY` must be in the environment again).
  - To also remove the demo databases, run `/scalardb-stop-sample-db` next.
