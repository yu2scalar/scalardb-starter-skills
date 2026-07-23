---
name: scalardb-stop-sample-db
description: Stop and remove the demo PostgreSQL + MySQL databases started by scalardb-start-sample-db (deletes the scalardb-sample-db namespace). Confirms before deleting; all demo data is discarded.
---

# scalardb-stop-sample-db

> Status: **v0.2.1 (2026-07-23 — aligned to unified plugin versioning, no functional change; v0.1.0 2026-07-14 = plan-006 P2 initial implementation)**
> Targets ScalarDB 3.18.0. Skill 9 of 9 in the scalardb-starter-skills walk-through.

## Overview

Tears down everything `scalardb-start-sample-db` created by deleting the `scalardb-sample-db` namespace. Because the demo databases use `emptyDir` volumes there is nothing to preserve — deletion discards all demo data.

### What this skill does NOT do

- Touch anything outside the `scalardb-sample-db` namespace (ScalarDB Cluster itself is stopped separately by `/scalardb-stop-scalardb-cluster`)
- Delete databases the user configured themselves in `scalardb-generate-config`
- Back up data (demo data is by design disposable)

## Phase 0 — preflight

1. Verify `kubectl` is on PATH and a current context exists.
2. If `./.scalardb-starter-skills.json` exists and records `k8s.context` different from the current context, **warn and ask** (shared convention) before continuing — deleting in the wrong cluster is the failure mode this guards against.

## Phase 1 — show what will be deleted and confirm

1. Run `kubectl get namespace scalardb-sample-db`. If it does not exist, report "nothing to stop" and finish.
2. Show `kubectl -n scalardb-sample-db get deploy,svc,pod` and ask:

   ```
   Q1. Delete namespace "scalardb-sample-db" and everything in it from context
       "<current-context>"? All demo data will be discarded. (y/n)
   ```

   On `n`, stop without changes.

## Phase 2 — delete and verify

```
kubectl delete namespace scalardb-sample-db --wait=true --timeout=180s
```

Verify with `kubectl get namespace scalardb-sample-db` (must return NotFound). If deletion hangs on finalizers, show the namespace status and advise rather than force-removing finalizers.

## Phase 3 — report

- Confirm the namespace is gone.
- If `./.scalardb-starter-skills.json` exists and has a `sampleDb` section, set `"status": "stopped"` (keep the endpoints for history).
- Remind the user: if a running ScalarDB Cluster was using these databases as storages, it will now fail until reconfigured or stopped (`/scalardb-stop-scalardb-cluster` — ideally run it *before* this skill).
