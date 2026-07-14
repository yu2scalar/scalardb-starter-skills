---
name: scalardb-set-k8s-env
description: Select the Kubernetes environment (AKS / EKS / GKE / minikube) to run ScalarDB Cluster on, switch the kubectl context to it, and verify connectivity. Does not create cloud clusters — the cluster must already exist (for minikube, offers to start a local one). Records the choice in the project marker file when one is present.
---

# scalardb-set-k8s-env

> Status: **v0.1.0 (2026-07-14 — plan-006 P2 initial implementation)**
> Targets ScalarDB 3.18.0. Skill 1 of 8 in the scalardb-starter-skills walk-through.

## Overview

Interactive selector for the Kubernetes environment that the rest of the skill set (sample databases, ScalarDB Cluster) will deploy into. The skill:

1. asks which platform to use (AKS / EKS / GKE / minikube),
2. finds or prepares a matching kubectl context and switches to it,
3. verifies connectivity (`kubectl cluster-info`, `kubectl get nodes`),
4. records the result in `./.scalardb-starter-skills.json` **if that marker file already exists** in the current directory.

Later skills always re-check the live `kubectl config current-context` and only use this record to warn about mismatches — the live context is never silently overridden.

### What this skill does NOT do

- Create AKS / EKS / GKE clusters, node pools, or cloud accounts (user responsibility; the skill prints the credential-fetch command for each platform)
- Install kubectl / az / aws / gcloud / minikube
- Create a project directory or the marker file (that happens in `scalardb-generate-config`)
- Deploy anything into the cluster

## Phase 0 — preflight

1. Verify `kubectl` is on PATH (`kubectl version --client`). If missing, stop and point the user to the official install doc for their OS.
2. Read `./.scalardb-starter-skills.json` if present; remember any previously recorded `k8s` section for the mismatch warning in Phase 3.

## Phase 1 — platform selection

```
Q1. Which Kubernetes environment will you use?
    1) minikube  — local, good for trying things out (can be started by this skill)
    2) AKS       — Azure Kubernetes Service (cluster must already exist)
    3) EKS       — Amazon Elastic Kubernetes Service (cluster must already exist)
    4) GKE       — Google Kubernetes Engine (cluster must already exist)
```

## Phase 2 — context discovery and switch

1. Run `kubectl config get-contexts -o name` and show the list.
2. Suggest candidates matching the chosen platform by naming convention (informational only — the user picks):

   | platform | typical context name pattern |
   |---|---|
   | minikube | `minikube` |
   | AKS | the AKS cluster name (as created by `az aks get-credentials`) |
   | EKS | `arn:aws:eks:<region>:<account>:cluster/<name>` |
   | GKE | `gke_<project>_<location>_<name>` |

3. If no suitable context exists, print the platform's credential-fetch command and stop (re-run the skill afterwards):

   - AKS: `az aks get-credentials --resource-group <rg> --name <cluster>`
   - EKS: `aws eks update-kubeconfig --region <region> --name <cluster>`
   - GKE: `gcloud container clusters get-credentials <cluster> --location <location>`
   - minikube: none needed — continue to the minikube handling below.

4. **minikube only**: run `minikube status`. If the cluster is not running, ask:

   ```
   Q2. minikube is not running. Start it now with `minikube start`? (y/n)
   ```

   On `y`, run `minikube start` and wait for it to finish. On `n`, stop the skill.

5. Confirm the target context with the user, then run `kubectl config use-context <context>`.

## Phase 3 — connectivity check

1. `kubectl cluster-info` — must return the control-plane endpoint.
2. `kubectl get nodes -o wide` — show the node table (name, status, version).
3. If either fails, report the exact error and the most likely cause per platform (expired cloud credentials, stopped cluster, VPN requirement) and stop. Do not retry destructively.

## Phase 4 — record and report

1. If `./.scalardb-starter-skills.json` exists, merge in:

   ```json
   {
     "k8s": {
       "platform": "<minikube|aks|eks|gke>",
       "context": "<context-name>",
       "verifiedAt": "<ISO-8601 timestamp>"
     }
   }
   ```

   Do **not** create the file if it is absent — just skip this step and mention that the choice will be re-detected live by later skills.

2. Report to the user: chosen platform, active context, node count/versions, and the suggested next skill: `scalardb-start-sample-db` (for a demo database) or `scalardb-generate-config` (when using your own databases).

## Context-mismatch convention (shared with all later skills)

Every later skill compares `kubectl config current-context` against the recorded `k8s.context` (when a marker file is present). On mismatch it must **warn and ask** — "Recorded context is X, current is Y. Continue with Y, or switch back to X?" — and never switch automatically.
