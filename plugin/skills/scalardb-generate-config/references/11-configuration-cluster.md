# ScalarDB 3.19.1 Reference — Cluster configuration properties

Configuration properties specific to ScalarDB Cluster (cluster node, Transaction Coordinator node, Java Client SDK, and the
Enterprise features). Core `scalar.db.*` properties (storage / transaction / Consensus Commit, and so on) are covered in
[`10-configuration-core.md`](./10-configuration-core.md); cluster nodes and the Transaction Coordinator use them internally too.

Applies to ScalarDB 3.19.1. Official docs: `scalardb-cluster/scalardb-cluster-configurations.mdx` (main page; Transaction
Coordinator section `:1778-1956`), `scalardb-cluster/deployment-patterns-for-microservices.mdx`,
`scalardb-cluster/scalardb-auth-with-sql.mdx`, `scalardb-cluster/control-access-via-oidc-based-jwt-tokens.mdx`,
`scalardb-cluster/authorize-with-abac.mdx`, `scalardb-cluster/encrypt-data-at-rest.mdx`,
`scalardb-cluster/remote-replication.mdx`, `scalardb-cluster/getting-started-with-vector-search.mdx`.

## Prefix hierarchy

| Scope | Prefix |
|---|---|
| Core | `scalar.db.` |
| Cluster common | `scalar.db.cluster.` |
| Cluster node | `scalar.db.cluster.node.` |
| Cluster internal communication | `scalar.db.cluster.internal.` |
| Membership | `scalar.db.cluster.membership.` |
| Java Client SDK (primitive) | `scalar.db.cluster.client.` |
| Transaction Coordinator | `scalar.db.cluster.transaction_coordinator.` |
| Authentication | `scalar.db.cluster.auth.` |
| ABAC | `scalar.db.cluster.abac.` |
| Encryption | `scalar.db.cluster.encryption.` |
| SQL client (`cluster_mode`) | `scalar.db.sql.cluster_mode.` |
| Replication | `scalar.db.replication.` |
| Embedding | `scalar.db.embedding.` |

## Column definitions and legend

Column definitions are the same as in [`10-configuration-core.md`](./10-configuration-core.md):

| Column | Meaning |
|---|---|
| Property | Fully qualified property name |
| Description | Purpose and behavior |
| Default | The **effective** value when unset. `(empty)` = no value / disabled |
| Notes | Related properties, preconditions, special behavior, official-docs references (`page.mdx:line`) |
| Group | Applicability (legend below) |

Group legend (cluster-specific groups; finer than in 10):

| Group | Applies to |
|---|---|
| `cluster-common` | Shared settings used by nodes, clients and/or the TC |
| `cluster-node` | Cluster node process only |
| `membership` | Node (and TC) membership discovery |
| `cluster-client` | Java Client SDK, primitive (CRUD / transaction) interface |
| `cluster-client-sql` | Java Client SDK, SQL interface |
| `tc` | Transaction Coordinator process only |
| `tc-client` | Java Client SDK settings for reaching the TC |
| `otel` | Cluster node OpenTelemetry |
| `auth` / `oidc` / `abac` | Server-side authentication / OIDC / ABAC |
| `encryption` / `encryption-self` / `encryption-vault` | Data-at-rest encryption |
| `licensing` / `replication` / `embedding` | The respective Enterprise feature |

For most keys, an **empty value is treated as unset** and the default applies. This is why the shipped properties
templates can contain lines like `key=${env:SOME_ENV:-}` without harm.

---

## 1. Node / common (server side)

### 1.1 Common

Shared base settings read by nodes, clients, and the TC.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.port` | Port number of cluster nodes | `60053` | The client and TC overwrite this key internally with the target port when they build a router | cluster-common |
| `scalar.db.cluster.transaction_coordinator.port` | Port number of TC nodes | `60055` | Read by the TC server and by TC peers | cluster-common |
| `scalar.db.cluster.id` | Logical ID of this ScalarDB Cluster. Used as the participant ID in the Separated Cluster (TC) pattern and as the OTel resource attribute `scalardb.cluster.id` | `(empty)` | On a node it is **copied unconditionally** into Core `scalar.db.consensus_commit.participant_id`. Required when `node.transaction_participant.enabled=true` (`DB-CLUSTER-10074`) and on a TC client that has its own cluster connection (`DB-CLUSTER-10080`). Docs: `scalardb-cluster-configurations.mdx:49-53,2055-2059` | cluster-common |
| `scalar.db.cluster.node_cache.expiration_time_millis` | Expiration time of the cluster node cache (ms) | `30000` | | cluster-common |
| `scalar.db.cluster.distribution.consistent_hashing.vnode.count` | Number of virtual nodes for consistent hashing | `256` | | cluster-common |
| `scalar.db.cluster.grpc.deadline_duration_millis` | gRPC deadline (ms). Also needed on nodes (node-to-node calls) and on the TC (TC-to-TC and TC-to-cluster calls) | `60000` | Docs: `scalardb-cluster-configurations.mdx:115-119,1805` | cluster-common |
| `scalar.db.cluster.grpc.max_inbound_message_size` | Max size of a single gRPC message that an **outbound channel** (client→node, node→node, TC→cluster) accepts | `(empty)` (gRPC default, 4 MiB) | On a node, inherits `node.grpc.max_inbound_message_size` if unset; on the TC, inherits `transaction_coordinator.grpc.max_inbound_message_size` if unset | cluster-common |
| `scalar.db.cluster.grpc.max_inbound_metadata_size` | Max metadata size accepted by outbound channels | `(empty)` (gRPC default) | Same inheritance as above | cluster-common |
| `scalar.db.cluster.auth.enabled` | Enable authentication and authorization | `false` | **Has no effect on the TC** (the TC has no auth service; docs `scalardb-cluster-configurations.mdx:1793`) | cluster-common |
| `scalar.db.cluster.tls.enabled` | Enable wire encryption (TLS) | `false` | | cluster-common |
| `scalar.db.cluster.tls.ca_root_cert_pem` | Custom CA root certificate (PEM data) | `(empty)` | Read only when `tls.enabled=true`. If the key is **present** it wins over `_path`, even if its value is empty | cluster-common |
| `scalar.db.cluster.tls.ca_root_cert_path` | Custom CA root certificate (file path) | `(empty)` | Read only if `tls.enabled=true` and the `_pem` key is absent | cluster-common |
| `scalar.db.cluster.tls.override_authority` | Custom authority for TLS (does not change the host connected to; mainly for tests) | `(empty)` | Read only when `tls.enabled=true` | cluster-common |

> **Note on `scalar.db.cluster.hop_limit`** (default `3`): it is read by the primitive / SQL / embedding / replication
> clients, by the client's TC connection, and per target cluster by the TC. The hop limit used between nodes is
> `scalar.db.cluster.internal.hop_limit` (§1.3).

> **Per-connection override passthrough**: every property `<sourcePrefix><rest>` is copied to `scalar.db.cluster.<rest>` for
> one connection only. Used by the client's TC connection (`scalar.db.cluster.client.transaction_coordinator.<rest>`) and by
> the TC per target cluster (`scalar.db.cluster.transaction_coordinator.clusters.<id>.<rest>`).

#### Pitfalls

- The CA / authority keys are simply ignored when TLS is off. The flip side: a typo in `tls.enabled` silently disables TLS
  and the CA keys are dropped without a warning.
- `ca_root_cert_pem` is chosen by **key presence**. A template line `scalar.db.cluster.tls.ca_root_cert_pem=` (empty) makes
  the `_path` key ignored and leaves no CA configured.
- `scalar.db.cluster.grpc.max_inbound_message_size` on a **node** is not the node's server limit; it is the limit of the
  node's outbound channels to other nodes. The server limit is `scalar.db.cluster.node.grpc.max_inbound_message_size` (§1.2).
- `scalar.db.cluster.id` overwrites any `scalar.db.consensus_commit.participant_id` set on a node.

### 1.2 Node

Prefix `scalar.db.cluster.node.`, plus node-level keys.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.prometheus_exporter_port` | Prometheus exporter port | `9080` | | cluster-node |
| `scalar.db.cluster.node.decommissioning_duration_secs` | Seconds to stay decommissioned before shutdown | `30` | | cluster-node |
| `scalar.db.cluster.node.grpc.max_inbound_message_size` | Max single gRPC message the node's **server** accepts | `(empty)` (gRPC default) | | cluster-node |
| `scalar.db.cluster.node.grpc.max_inbound_metadata_size` | Max metadata size the node's server accepts | `(empty)` (gRPC default) | | cluster-node |
| `scalar.db.cluster.node.grpc.max_connection_age_millis` | Max lifetime of a channel | `(empty)` (effectively unlimited) | Applied only if set | cluster-node |
| `scalar.db.cluster.node.grpc.max_connection_age_grace_millis` | Grace period after max age | `(empty)` (effectively unlimited) | Same as above | cluster-node |
| `scalar.db.cluster.node.admin.port` | Port of a separate admin gRPC server (pause / unpause / checkPaused) | `(empty)` (admin service on `node.port`) | | cluster-node |
| `scalar.db.cluster.node.standalone_mode.enabled` | Standalone mode. When `true`, `membership.*` is overwritten with STATIC / `127.0.0.1` | `false` | | cluster-node |
| `scalar.db.cluster.node.transaction_participant.enabled` | Serve the `TransactionParticipant` gRPC service so the TC can drive 2PC against this cluster | `false` | Requires `scalar.db.cluster.id` (`DB-CLUSTER-10074`), a Core transaction manager that provides a participant (Consensus Commit only; `jdbc` / `single-crud-operation` throw `UnsupportedOperationException`), and is **rejected together with replication log writer**. Docs: `scalardb-cluster-configurations.mdx:127-131` | cluster-node |
| `scalar.db.cluster.node.tls.cert_chain_path` | Certificate chain file for TLS | `(empty)` | | cluster-node |
| `scalar.db.cluster.node.tls.private_key_path` | Private key file for TLS | `(empty)` | | cluster-node |
| `scalar.db.transaction.enabled` | Whether the node serves the transaction services (CRUD, admin, 2PC, participant, SQL). Set `false` e.g. for an embedding-only node | `true` | Docs: `scalardb-cluster-configurations.mdx:133-137` | cluster-node |
| `scalar.db.cluster.node.scanner_management.expiration_time_millis` | Server-side scanners idle for longer than this are closed | `60000` | Docs: `scalardb-cluster-configurations.mdx:139-143` | cluster-node |

> The node always forces `scalar.db.active_transaction_management.enabled=false` and
> `scalar.db.attribute_propagation.enabled=false` on the underlying Core transaction manager,
> because the cluster layer does that work itself.

#### Pitfalls

- `transaction_participant.enabled=true` without `scalar.db.cluster.id` fails at node start with `DB-CLUSTER-10074`.
- `transaction_participant.enabled=true` together with `scalar.db.replication.log_writer.enabled=true` fails at start
  ("The transaction participant is not supported when the remote replication feature is enabled").
- Turning on `transaction_participant.enabled` on a node is not enough for cross-cluster transactions: the TC (§9) and the
  clients (§2.1 TC keys) must be configured too, and the **Coordinator table must be shared** (§9 Pitfalls).
- A long-idle `Scanner` held by an application is closed by the node after 60 s
  (`scanner_management.expiration_time_millis`); later `next()` calls fail.

### 1.3 Internal communication

Prefix `scalar.db.cluster.internal.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.internal.node.port` | Port of the node's internal gRPC server | `60054` | In v3.19.1 this server hosts only the internal cache service; request forwarding between nodes goes to the peer's `node.port` | cluster-node |
| `scalar.db.cluster.internal.hop_limit` | Hop limit (prevents infinite forwarding) | `3` | | cluster-common |
| `scalar.db.cluster.internal.grpc.deadline_duration_millis` | Internal gRPC deadline (ms) | `60000` | | cluster-common |
| `scalar.db.cluster.internal.grpc.max_inbound_message_size` | Max message size (internal server) | `(empty)` (gRPC default) | | cluster-common |
| `scalar.db.cluster.internal.grpc.max_inbound_metadata_size` | Max metadata size (internal server) | `(empty)` (gRPC default) | | cluster-common |
| `scalar.db.cluster.internal.singleton_name_for_cluster_request_router` | Singleton name of the internal request router | `(empty)` | **Always overwritten by the node** with a random UUID, so a user value has no effect | cluster-common |

#### Pitfalls

- For a `RESOURCE_EXHAUSTED: gRPC message exceeds maximum size 4194304` error on a large **request**, raise the node's
  server limit `scalar.db.cluster.node.grpc.max_inbound_message_size` (and, since node-to-node forwarding uses the same
  main port, that is the limit on every node). For a large **response**, raise `scalar.db.cluster.grpc.max_inbound_message_size`
  on the client. The `internal.grpc.*` keys only affect the internal cache server in v3.19.1.

### 1.4 Membership

Prefix `scalar.db.cluster.membership.`.
Used when `standalone_mode.enabled=false`. **The TC also forms its own cluster with these keys** (§9).

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.membership.type` | Membership type | Effectively required (unset → exception) | Values `KUBERNETES` / `STATIC`. There is no default in the code; the container images (node and TC) set `KUBERNETES` as an env variable | membership |
| `scalar.db.cluster.membership.kubernetes.endpoint.namespace_name` | `KUBERNETES`: namespace of the endpoint | `default` | | membership |
| `scalar.db.cluster.membership.kubernetes.endpoint.name` | `KUBERNETES`: endpoint name | `(empty)` | | membership |
| `scalar.db.cluster.membership.static.ip_addresses` | `STATIC`: comma-separated IPs of the nodes | `(empty)` (empty array) | | membership |

#### Pitfalls

- Starting the node or TC from the JAR (not the image) without `membership.type` fails.
- The client-side `contact_points` (`indirect:` / `direct-kubernetes:`) is translated into these membership keys internally:
  `indirect:` → `STATIC` with the given host; `direct-kubernetes:` → `KUBERNETES`.

### 1.5 OpenTelemetry and storage health check

**Cluster nodes only** — the TC server has no OpenTelemetry wiring. The node properties template documents these keys in
comments.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.opentelemetry.enabled` | Enable the OpenTelemetry SDK. When `false`, no SDK or instrumentation is created | `false` | | otel |
| `scalar.db.cluster.node.opentelemetry.service_name` | `service.name` resource attribute | `scalardb-cluster-node` | Blank → default with a warning | otel |
| `scalar.db.cluster.node.opentelemetry.traces.endpoint` | OTLP **HTTP** endpoint for traces. Unset → traces not exported (sampler `alwaysOff`) | `(empty)` | | otel |
| `scalar.db.cluster.node.opentelemetry.metrics.endpoint` | OTLP HTTP endpoint for metrics. Unset → metrics not exported | `(empty)` | Exporter uses **delta** temporality preference | otel |
| `scalar.db.cluster.node.opentelemetry.logs.endpoint` | OTLP HTTP endpoint for logs. Unset → logs not exported | `(empty)` | | otel |
| `scalar.db.cluster.node.opentelemetry.metrics.export_interval_seconds` | Metrics export interval (s) | `60` | Non-positive → `60` with a warning | otel |
| `scalar.db.cluster.node.opentelemetry.storage_health_check.enabled` | Periodic storage health check, exported as gauge `scalardb.cluster.storage.status` | `true` | Runs only if `opentelemetry.enabled=true` **and** `metrics.endpoint` is non-blank | otel |
| `scalar.db.cluster.node.opentelemetry.storage_health_check.interval_seconds` | Interval between health checks (s) | `30` | Non-positive → `30` with a warning | otel |

Behavior notes:

- Resource attributes: `service.name`, `host.name` (from env `HOSTNAME`, else `scalardb-cluster-node-<random>`), and
  `scalardb.cluster.id` from `scalar.db.cluster.id` (omitted with a warning when blank).
- Propagators: W3C trace context + W3C baggage.
- The health checker calls `getNamespaceNames()` on a **separate** storage admin created from a copy of the node
  properties with JDBC admin pool overrides `min_idle=1`, `max_total=2`, `connection_timeout_millis=5000`.
  Initialization failures only log a warning.

#### Pitfalls

- `opentelemetry.enabled=true` with no endpoint is accepted; only a warning is logged and nothing is exported.
- Malformed endpoint URLs do **not** stop the node; the affected signal is disabled with a warning. Check the node log
  when no data arrives.
- The storage health check is "enabled" by default but does nothing unless OTel is enabled **and** a metrics endpoint is set.
- The exporters are OTLP over HTTP, not gRPC; point them at the collector's HTTP receiver.
- Metrics use delta temporality where possible; Prometheus-style backends that expect cumulative data may need conversion
  in the collector.

---

## 2. Client connection (application side)

Applications connect through two interfaces: the primitive CRUD / transaction API (§2.1) and the SQL interface (§2.2).
Client-side TLS / gRPC settings share `scalar.db.cluster.*` from §1.1.

### 2.1 Primitive CRUD / transactions

Prefix `scalar.db.cluster.client.`. Used with `scalar.db.transaction_manager=cluster`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.transaction_manager` | Must be `cluster` (otherwise exception) | `(empty)` | | cluster-client |
| `scalar.db.contact_points` | Target cluster, mode as prefix: `indirect:<LB host>` / `direct-kubernetes:<namespace>/<endpoint>` (or `direct-kubernetes:<endpoint>` → namespace `default`) / `singleton:<name>` (internal use) | `(empty)` | Optional when `transaction_coordinator.enabled=true` (facade-only client); otherwise empty → `DB-CLUSTER-10028`. `singleton:` is primarily for internal use | cluster-client |
| `scalar.db.contact_port` | Port of the contact point (indirect / direct-kubernetes only) | `60053` | `0` is treated as `60053` | cluster-client |
| `scalar.db.cluster.hop_limit` | Hop limit | `3` | | cluster-client |
| `scalar.db.cluster.auth.enabled` | Enable authentication and authorization | `false` | | cluster-client |
| `scalar.db.cluster.client.auth.type` | Client authentication type: `userpass` / `oidc_jwt` | `userpass` | Docs: `scalardb-cluster-configurations.mdx:2115-2119` | cluster-client |
| `scalar.db.username` | Username (userpass) | `(empty)` | | cluster-client |
| `scalar.db.password` | Password (userpass) | `(empty)` | | cluster-client |
| `scalar.db.cluster.client.auth.oidc_jwt.access_token` | JWT access token sent with `oidc_jwt`. Fixed at initialization; cannot be refreshed | `(empty)` | Docs: `scalardb-cluster-configurations.mdx:2121-2125` | cluster-client |
| `scalar.db.cluster.client.auth.userpass_cache.size` | Max number of cached userpass tokens (one per username/password pair) | `10000` | Docs: `scalardb-cluster-configurations.mdx:2127-2131` | cluster-client |
| `scalar.db.cluster.client.auth.userpass_cache.expiration_margin_millis` | Evict a cached token this long before it really expires, so re-login happens ahead of expiry. Clamped to at most half of each token's remaining lifetime; `0` disables | `60000` | Docs: `scalardb-cluster-configurations.mdx:2133-2137` | cluster-client |
| `scalar.db.cluster.client.scan_fetch_size` | Records fetched per round-trip by a Scanner | `10` | | cluster-client |
| `scalar.db.cluster.client.piggyback_begin.enabled` | Delay begin until the first CRUD to save the begin RPC | `false` | `getId()` before the real begin → `IllegalStateException` `DB-CLUSTER-10061`; `join()` → `DB-CLUSTER-10078`, `resume()` → `DB-CLUSTER-10059` | cluster-client |
| `scalar.db.cluster.client.write_buffering.enabled` | Buffer unconditional writes and send them in batches | `false` | `join()` → `DB-CLUSTER-10079`, `resume()` → `DB-CLUSTER-10060` | cluster-client |

TC connection keys (only effective for `GlobalTransactionManager`, see §9):

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.client.transaction_coordinator.enabled` | Send transaction control (begin / commit / rollback) of a Global transaction to the TC; DML still goes to the client's own cluster | `false` | Read when creating a `GlobalTransactionManager`. Docs: `scalardb-cluster-configurations.mdx:2061-2065` | tc-client |
| `scalar.db.cluster.client.transaction_coordinator.contact_points` | TC contact point, same format as `contact_points` (`indirect:` / `direct-kubernetes:`; no `singleton:`) | `(empty)` (required when enabled) | Empty → `DB-CLUSTER-10028` with this property name | tc-client |
| `scalar.db.cluster.client.transaction_coordinator.contact_port` | TC port. **Different default from `scalar.db.contact_port`** | `60055` | | tc-client |
| `scalar.db.cluster.client.transaction_coordinator.<rest>` | Overrides `scalar.db.cluster.<rest>` for the TC connection only (e.g. `…transaction_coordinator.tls.ca_root_cert_path`) | — | Passthrough. Docs: `scalardb-cluster-configurations.mdx:2079-2083` | tc-client |
| `scalar.db.cluster.id` | ID of the cluster this application belongs to (participant ID). Required when TC is enabled **and** `scalar.db.contact_points` is set | `(empty)` | Otherwise `DB-CLUSTER-10080`. Must match one of the TC's `transaction_coordinator.clusters`, otherwise `DB-CLUSTER-10075` at enlist time | tc-client |

#### Pitfalls

- **Deprecated**: `DistributedTransactionManager.join()` / `resume()` are deprecated in the cluster client and scheduled
  for removal in 3.20.0. They throw `IllegalStateException` when **authentication is enabled** (`DB-CLUSTER-10082` /
  `DB-CLUSTER-10083`), as well as with piggyback-begin and write-buffering. These guards apply to the one-phase manager
  only; the deprecated 2PC manager's `join()` / `resume()` have no such checks.
- A facade-only TC client (TC enabled, no `scalar.db.contact_points`) can only begin / commit / roll back; any operation that
  needs its own cluster fails with `DB-CLUSTER-10081`. Creating a plain `DistributedTransactionManager` from such a config
  fails the same way.
- `transaction_coordinator.enabled=true` does **not** change `DistributedTransactionManager`; only
  `TransactionFactory.getGlobalTransactionManager()` goes through the TC. Without it, a `GlobalTransactionManager` runs
  single-phase inside one cluster.
- A typo under `scalar.db.cluster.client.transaction_coordinator.` is not rejected: it is remapped to an unknown
  `scalar.db.cluster.<typo>` key and silently ignored (passthrough).
- `cluster.id` mismatch with the TC's list is not detected at startup; it surfaces at the first enlist (`DB-CLUSTER-10075`).

### 2.2 SQL interface

Prefix `scalar.db.sql.cluster_mode.`. Used with `scalar.db.sql.connection_mode=cluster`. **No TC keys**: the SQL client has
no Transaction Coordinator support in v3.19.1.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.sql.connection_mode` | Must be `cluster` (otherwise exception) | `(empty)` | | cluster-client-sql |
| `scalar.db.sql.cluster_mode.contact_points` | `indirect:<LB host>` / `direct-kubernetes:<namespace>/<endpoint>` (or `<endpoint>`). Required | `(empty)` | No `singleton:`. Validation errors are `DB-CLUSTER-10028..10030` (§2.4) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.contact_port` | Port | `60053` | | cluster-client-sql |
| `scalar.db.cluster.hop_limit` | Hop limit | `3` | | cluster-client-sql |
| `scalar.db.cluster.auth.enabled` | Enable authentication and authorization | `false` | | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.type` | `userpass` / `oidc_jwt` | `userpass` | Docs: `scalardb-cluster-configurations.mdx:2290-2294` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.username` | Username | `(empty)` | JDBC property `user` wins when non-empty | cluster-client-sql |
| `scalar.db.sql.cluster_mode.password` | Password | `(empty)` | JDBC property `password` wins when **present**; present-but-empty means "no password", the `cluster_mode.password` key is then ignored | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.oidc_jwt.access_token` | JWT access token for `oidc_jwt` | `(empty)` | Docs: `scalardb-cluster-configurations.mdx:2296-2300` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.userpass_cache.size` | Userpass token cache size | `10000` | Docs: `scalardb-cluster-configurations.mdx:2302-2306` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.userpass_cache.expiration_margin_millis` | Same as the primitive key | `60000` | Docs: `scalardb-cluster-configurations.mdx:2308-2312` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.client.piggyback_begin.enabled` | Delay begin until the first execute | `false` | `resume()` → `DB-CLUSTER-10059`; `SqlSession.getTransactionId()` returns `Optional.empty()` before the real begin (`scalardb-cluster-configurations.mdx:2263`) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.client.write_buffering.enabled` | Buffer INSERT / UPSERT and send them in batches | `false` | `resume()` → `DB-CLUSTER-10060` | cluster-client-sql |

> Client-side TLS / gRPC uses `scalar.db.cluster.tls.*` / `scalar.db.cluster.grpc.*` from §1.1.

#### Pitfalls

- Unlike the primitive client, the SQL client's `resume()` does **not** reject authentication-enabled configs in v3.19.1;
  only piggyback-begin and write-buffering are checked.
- Applications that need the TC (Global transactions across clusters) must use the primitive interface; there is no SQL path.
- `user` / `password` JDBC properties silently override `cluster_mode.username` / `password`.

### 2.3 Transaction manager names that are not available

| Property | Status |
|---|---|
| `scalar.db.transaction_manager=cluster-transaction` | **Not available.** No transaction manager named `cluster-transaction` is registered, and `scalar.db.cluster.transaction.original_transaction_manager` is not read; the node builds the transaction managers directly from `scalar.db.transaction_manager` |
| `scalar.db.sql.connection_mode=cluster-sql-transaction` | **Not available** |

#### Pitfalls

- If a hand-written node properties file contains `scalar.db.transaction_manager=cluster-transaction` or
  `scalar.db.cluster.transaction.original_transaction_manager`, it does not work: set the real transaction manager
  (for example `consensus-commit`) instead.

### 2.4 Contact-point validation

All client-side contact points are parsed the same way: `scalar.db.contact_points` (primitive, except `singleton:`),
`scalar.db.sql.cluster_mode.contact_points`, `scalar.db.embedding.client.contact_points`,
`scalar.db.replication.client.contact_points`, `scalar.db.cluster.client.transaction_coordinator.contact_points`, and the
TC's `scalar.db.cluster.transaction_coordinator.clusters.<id>.contact_points`.

| Error | Condition |
|---|---|
| `DB-CLUSTER-10028` "The contact points must not be empty. Property: %s" | null / empty |
| `DB-CLUSTER-10029` "…must be prefixed with 'indirect:' or 'direct-kubernetes:'. Property: %s" | unknown prefix |
| `DB-CLUSTER-10030` "The format of the contact points for the direct-kubernetes mode is …" | more than one `/` |

Read the `Property:` part of the message to know which key is wrong.

---

## 3. Authentication and authorization (auth / OIDC / attribute-based)

Enabled with `scalar.db.cluster.auth.enabled=true` (§1.1). The server-side settings are below; the client-side type is
`auth.type` in §2.

### 3.1 Authentication (server side)

Prefix `scalar.db.cluster.auth.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.auth.cache_expiration_time_millis` | Expiration of the server-side auth cache (ms) | `60000` (1 min) | | auth |
| `scalar.db.cluster.auth.auth_token_expiration_time_minutes` | Token lifetime (min) | `1440` (1 day) | | auth |
| `scalar.db.cluster.auth.auth_token_gc_thread_interval_minutes` | Interval of the expired-token GC thread (min) | `360` (6 h) | | auth |
| `scalar.db.cluster.auth.pepper` | Secret added before hashing passwords | `(empty)` (no pepper) | | auth |
| `scalar.db.system_namespace_name` | Namespace of the auth / ABAC / encryption system tables | `scalardb` | Also used by ABAC and encryption | auth |

### 3.2 OIDC

Prefix `scalar.db.cluster.auth.oidc.`. OIDC is enabled when `trusted_issuers` is non-empty.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.auth.oidc.trusted_issuers` | Trusted issuers (`iss`), comma-separated | `(empty)` | **Non-empty enables OIDC. Two or more → exception** (effectively one) | oidc |
| `scalar.db.cluster.auth.oidc.username.claim_name` | JWT claim holding the ScalarDB username | `(empty)` (required) | null / empty → exception (`OIDC_USERNAME_CLAIM_REQUIRED`) | oidc |
| `scalar.db.cluster.auth.oidc.audience.name` | Audience that `aud` must contain | `scalardb` | | oidc |
| `scalar.db.cluster.auth.oidc.jwt.jwks.url_cache.expiration_seconds` | JWKS URL cache expiration (s) | `86400` (24 h) | Docs: `scalardb-cluster-configurations.mdx:1008-1012` | oidc |
| `scalar.db.cluster.auth.oidc.jwt.jwks.content_cache.expiration_seconds` | JWKS content cache expiration (s) | `86400` (24 h) | Docs: `scalardb-cluster-configurations.mdx:1014-1018` | oidc |
| `scalar.db.cluster.auth.oidc.jwt.access_token.require_at_jwt_typ` | Require `typ` = `at+jwt` / `application/at+jwt` | `true` | | oidc |
| `scalar.db.cluster.auth.oidc.jwt.max_clock_skew_seconds` | Allowed clock skew (s) | `10` | Docs: `scalardb-cluster-configurations.mdx:1020-1024` | oidc |
| `scalar.db.cluster.auth.oidc.abac.claim_name` | JWT claim carrying ABAC information | `scalardb_abac` | | oidc |
| `scalar.db.cluster.auth.oidc.abac.enabled` | Enable OIDC-ABAC integration (JWT claim can only restrict, not extend) | `false` | | oidc |

#### Pitfalls (auth / OIDC)

- `trusted_issuers` looks like a list but more than one value fails at startup.
- With authentication enabled, cluster-client `join()` / `resume()` always throw (§2.1).

### 3.3 Attribute-based authentication (client side)

Not configuration properties but **reserved operation attribute keys** that carry credentials for one transaction,
operation, or SQL statement. Documented in `scalardb-auth-with-sql.mdx:197-278`.

| Attribute key | Constant | Meaning |
|---|---|---|
| `auth-type` | `AuthOperationAttributes.TYPE` | `userpass` or `oidc_jwt` (case-insensitive). Selects attribute-based auth |
| `auth-userpass-username` | `USERPASS_USERNAME` | Username; required for `userpass` |
| `auth-userpass-password` | `USERPASS_PASSWORD` | Password; optional |
| `auth-oidc-jwt-access-token` | `OIDC_JWT_ACCESS_TOKEN` | JWT; required for `oidc_jwt` |

Behavior: attributes are honored only when `scalar.db.cluster.auth.enabled=true`; otherwise the default credentials are
used. The keys are stripped before the request leaves the client. Unknown type → `DB-CLUSTER-10067`; missing required key
→ `DB-CLUSTER-10068`, both `IllegalArgumentException` before any request. For `mutate()`-style multi-operation calls the
first operation's attributes decide.

#### Pitfalls

- Auth attributes on operations **inside** a begun transaction are not interpreted as credentials and are forwarded as
  normal attributes (`scalardb-auth-with-sql.mdx:274`). Put them on `begin(attributes)` or on one-shot operations.
- With a TC, the token captured at begin/enlist is forwarded to the participants and cannot be refreshed by the TC; if a
  participant rejects it during commit, the transaction aborts with the retryable `DB-CLUSTER-20003`
  (`CommitConflictException`). Retry from the beginning.

---

## 4. ABAC (attribute-based access control)

Prefix `scalar.db.cluster.abac.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.abac.enabled` | Enable ABAC | `false` | Requires authentication: otherwise `ABAC_FEATURE_REQUIRES_AUTH_FEATURE` at start. Rejected by the standard edition node | abac |
| `scalar.db.cluster.abac.cache_expiration_time_millis` | Expiration of the ABAC metadata cache (ms) | `60000` (1 min) | Policy changes take effect after at most this time | abac |

---

## 5. Data-at-rest encryption

Enabled with `scalar.db.cluster.encryption.enabled=true`; `type` selects the implementation.

### 5.1 Common

Prefix `scalar.db.cluster.encryption.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.enabled` | Enable encryption | `false` | | encryption |
| `scalar.db.cluster.encryption.type` | Implementation | `(empty)` (required) | Unset → exception. Values: `vault` / `self` | encryption |
| `scalar.db.cluster.encryption.delete_data_encryption_key_on_drop_table.enabled` | Delete the DEK when dropping a table | `false` | | encryption |

### 5.2 Self-encryption (`type=self`)

Prefix `scalar.db.cluster.encryption.self.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.self.key_type` | Key type | `AES128_GCM` | Allowed: `AES128_GCM` / `AES256_GCM` / `AES128_EAX` / `AES256_EAX` / `AES128_CTR_HMAC_SHA256` / `AES256_CTR_HMAC_SHA256` / `CHACHA20_POLY1305` / `XCHACHA20_POLY1305` | encryption-self |
| `scalar.db.cluster.encryption.self.associated_data_required` | Require associated data (AEAD) | `false` | | encryption-self |
| `scalar.db.cluster.encryption.self.kubernetes.secret.namespace_name` | Namespace of the Kubernetes Secret | `default` | | encryption-self |
| `scalar.db.cluster.encryption.self.data_encryption_key_cache_expiration_time` | DEK cache expiration (ms) | `60000` (60 s) | The key has **no `_millis` suffix** | encryption-self |

### 5.3 HashiCorp Vault (`type=vault`)

Prefix `scalar.db.cluster.encryption.vault.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.vault.address` | Vault address | `(empty)` (required) | Unset → exception | encryption-vault |
| `scalar.db.cluster.encryption.vault.token` | Vault token | `(empty)` (required) | Unset → exception | encryption-vault |
| `scalar.db.cluster.encryption.vault.transit_secrets_engine_path` | Path of the transit secrets engine | `transit` | | encryption-vault |
| `scalar.db.cluster.encryption.vault.namespace` | Vault namespace (optional) | `(empty)` | | encryption-vault |
| `scalar.db.cluster.encryption.vault.key_type` | Key type | `aes128-gcm96` | Allowed: `aes128-gcm96` / `aes256-gcm96` / `chacha20-poly1305` | encryption-vault |
| `scalar.db.cluster.encryption.vault.associated_data_required` | Require associated data (AEAD) | `false` | | encryption-vault |
| `scalar.db.cluster.encryption.vault.column_batch_size` | Columns per Vault request | `64` | | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.ca_root_cert_pem` | CA root certificate for TLS to Vault (PEM) | `(empty)` | `pem` wins (by key presence) | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.ca_root_cert_path` | CA root certificate for TLS to Vault (path) | `(empty)` | | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.override_authority` | Custom authority for TLS to Vault | `(empty)` | | encryption-vault |

#### Pitfalls

- The default key types are `AES128_GCM` (self) and `aes128-gcm96` (Vault) (`encrypt-data-at-rest.mdx:55,83`).
- `self.data_encryption_key_cache_expiration_time_millis` (with `_millis`) is silently ignored; the real key ends in `_time`.

---

## 6. Licensing

Prefix `scalar.db.cluster.node.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.licensing.license_key` | License key | `(empty)` | | licensing |
| `scalar.db.cluster.node.licensing.license_check_cert_pem` | License check certificate (PEM) | `(empty)` | `pem` wins (by key presence) | licensing |
| `scalar.db.cluster.node.licensing.license_check_cert_path` | License check certificate (path) | `(empty)` | | licensing |

---

## 7. Remote replication

Enterprise Remote Replication: primary (log-writer) / backup (log-applier) sites. Replication forces
`scalar.db.transaction_manager=consensus-commit` on its own copy of the properties.

### 7.1 Common

Must match on both sites.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.replication.partition_count` | Partitions of the `transaction_groups` table. Same on both sites (change needs a restart of both) | `256` | | replication |
| `scalar.db.replication.repl_db.namespace` | Namespace of the replication tables. Same on both sites | `replication` | | replication |
| `scalar.db.replication.record_table_suffix` | Suffix of the replication record metadata tables | `__records` | | replication |

### 7.2 log-writer (primary: capture)

Consensus Commit one-phase commit is rejected when the log writer is configured.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.replication.log_writer.enabled` | Enable LogWriter | `false` | **Cannot be combined with `node.transaction_participant.enabled=true`** | replication |
| `scalar.db.replication.log_writer.compression_type` | Compression of stored writes | `GZIP` | Values `NONE` / `GZIP` | replication |
| `scalar.db.replication.log_writer.group_commit.retention.time_millis` | Max wait before committing a transaction group | `100` | | replication |
| `scalar.db.replication.log_writer.group_commit.retention.values` | Max transactions per batch | `32` | | replication |
| `scalar.db.replication.log_writer.group_commit.timeout_check_interval_millis` | Timeout check interval | `20` | | replication |
| `scalar.db.replication.log_writer.group_commit.max_thread_pool_size` | Max thread pool size for group commit | `4096` | | replication |

### 7.3 log-applier (backup: apply)

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.replication.log_applier.enabled` | Enable LogApplier | `false` | | replication |
| `scalar.db.replication.log_applier.transaction.expiration_millis` | Transaction expiration used for cleanup | `30000` | | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.threads` | Scanner threads | `16` | | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.fetch_size` | Records per scan | `32` | Also written into `scalar.db.scan_fetch_size` | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.wait_millis` | Wait between scans (ms) | `1000` | | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.dedup.expiration_millis` | Dedup cache expiration | `10000` | | replication |
| `scalar.db.replication.log_applier.transaction_applier.threads` | Applier threads | `128` | The key is `transaction_applier.threads`; `transaction_handler.threads` is not read | replication |
| `scalar.db.replication.log_applier.max_record_version` | Max record version (overflow guard, for development) | `2147482647` | `Integer.MAX_VALUE - 1000` | replication |
| `scalar.db.replication.log_applier.replication_status_service.threads` | Replication status service threads | `16` | | replication |
| `scalar.db.replication.log_applier.coordinator_state_cache.expiration_millis` | Coordinator state cache expiration | `30000` | | replication |
| `scalar.db.replication.log_applier.coordinator_state_cache.size` | Coordinator state cache size | `1000` | | replication |
| `scalar.db.replication.log_applier.write_operation.expiration_millis` | Write operation expiration for GC | `86400000` (1 day) | | replication |

### 7.4 admin / client / provider

| Property / constant | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.replication.admin.enabled` | Replication Admin. With `true` (or `log_applier.enabled=true`) the replication metadata tables are created together with application tables | `false` | Behavior described in `remote-replication.mdx:699` | replication |
| `scalar.db.replication.client.contact_points` | Cluster contact point: `indirect:<HOST>` / `direct-kubernetes:<NAMESPACE>/<ENDPOINT>` (or `<ENDPOINT>`). Required | `(empty)` (required) | Validated as in §2.4 | replication |
| `scalar.db.replication.client.contact_port` | Node port | `60053` | | replication |
| `scalar.db.cluster.hop_limit` | Hop limit (replication client) | `3` | | replication |
| `scalar.db.transaction_manager=replication-consensus-commit` | Transaction manager name registered by replication (identifier only) | — | | replication |

#### Pitfalls (replication)

- Setting `log_applier.transaction_handler.threads` is silently ignored; the thread count stays 128. The key that is read
  is `log_applier.transaction_applier.threads`.
- Replication and the TC participant role are mutually exclusive in v3.19.1 (§7.2).

---

## 8. Embedding / vector search

Enterprise vector search. Enabled with `scalar.db.embedding.enabled=true`. Stores and models are defined on the node and
referenced by name from the client. `<S>` = store name, `<M>` = model name (names listed in `stores` / `models`).

### 8.1 Overall (node side)

Prefix `scalar.db.embedding.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.embedding.enabled` | Enable embedding | `false` | | embedding |
| `scalar.db.embedding.stores` | Comma-separated store names | `(empty)` (empty array) | | embedding |
| `scalar.db.embedding.models` | Comma-separated model names | `(empty)` (empty array) | | embedding |

### 8.2 Stores (`scalar.db.embedding.stores.<S>.*`)

`type` selects the kind; each kind has its own keys.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.embedding.stores.<S>.type` | Store type | `(empty)` | Values: `in-memory` / `opensearch` / `azure-cosmos-nosql` / `azure-ai-search` / `pgvector` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.server_url` | OpenSearch server URL | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.api_key` | API key | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.user_name` | User name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.password` | Password | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.region` | AWS region | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.service_name` | AWS service name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.access_key_id` | AWS access key ID | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.secret_access_key` | AWS secret access key | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.index_name` | Index name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.endpoint` | Endpoint | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.key` | Key | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.database_name` | Database name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.container_name` | Container name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.dimensions` | Embedding dimensions | `1536` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.endpoint` | Endpoint | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.api_key` | API key | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.index_name` | Index name | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.dimensions` | Embedding dimensions | `1536` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.host` | Host | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.port` | Port | `5432` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.user` | User | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.password` | Password | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.database` | Database | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.table` | Table | `(empty)` | | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.dimensions` | Embedding dimensions | `1536` | | embedding |

### 8.3 Models (`scalar.db.embedding.models.<M>.*`)

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.embedding.models.<M>.type` | Model type | `(empty)` | Values: `in-process` / `bedrock-titan` / `azure-open-ai` / `vertex-ai` / `open-ai` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.region` | AWS region | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.access_key_id` | Access key ID | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.secret_access_key` | Secret access key | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.model` | Model name | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.dimensions` | Dimensions | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.endpoint` | Endpoint | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.api_key` | API key | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.deployment_name` | Deployment name | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.dimensions` | Dimensions | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.project` | GCP project | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.location` | Location | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.endpoint` | Endpoint | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.publisher` | Publisher | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.model_name` | Model name | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.output_dimensionality` | Output dimensionality | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.api_key` | API key | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.model_name` | Model name | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.base_url` | Base URL | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.organization_id` | Organization ID | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.dimensions` | Dimensions | `(empty)` | | embedding |
| `scalar.db.embedding.models.<M>.open-ai.user` | User identifier | `(empty)` | | embedding |

### 8.4 Embedding client

Prefix `scalar.db.embedding.client.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.embedding.client.contact_points` | Cluster contact point: `indirect:<HOST>` / `direct-kubernetes:<NAMESPACE>/<ENDPOINT>` (or `<ENDPOINT>`). Required | `(empty)` (required) | Validated as in §2.4 | embedding |
| `scalar.db.embedding.client.contact_port` | Node port | `60053` | Docs: `scalardb-cluster-configurations.mdx:2324-2328` | embedding |
| `scalar.db.embedding.client.store` | Store name (matches the node's `stores`) | `(empty)` | | embedding |
| `scalar.db.embedding.client.model` | Model name (matches the node's `models`) | `(empty)` | | embedding |
| `scalar.db.cluster.hop_limit` | Hop limit | `3` | | embedding |

#### Pitfalls (embedding)

- An embedding-only node can set `scalar.db.transaction.enabled=false` (§1.2) to avoid starting the transaction services.

---

## 9. Transaction Coordinator

The Transaction Coordinator (TC) is a **separate process** (command `scalardb-transaction-coordinator-node`) that drives
two-phase commit across several ScalarDB Clusters for applications that use the Global Transaction API ("Separated
Cluster" pattern). TC nodes form their own cluster with the same membership / consistent-hashing mechanism as cluster
nodes and route requests by transaction ID.
Docs: `scalardb-cluster-configurations.mdx:1778-1956`, `deployment-patterns-for-microservices.mdx`.

What has to be configured, in three places:

| Where | Keys | Section |
|---|---|---|
| TC process | `scalar.db.cluster.transaction_coordinator.*` (below), membership, Core storage for the Coordinator table | §9.1-9.3 |
| Every participating cluster's nodes | `scalar.db.cluster.node.transaction_participant.enabled=true`, `scalar.db.cluster.id` | §1.1, §1.2 |
| Applications (primitive client) | `scalar.db.cluster.client.transaction_coordinator.enabled=true`, `…contact_points`, `scalar.db.cluster.id` | §2.1 |

### 9.1 Target clusters

Prefix `scalar.db.cluster.transaction_coordinator.`.

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.transaction_coordinator.clusters` | Comma-separated IDs of the target clusters; each must equal that cluster's `scalar.db.cluster.id` | `(empty)` (required) | Empty → `DB-CLUSTER-10076`; duplicates → `DB-CLUSTER-10077`. Docs: `scalardb-cluster-configurations.mdx:1816-1820` | tc |
| `scalar.db.cluster.transaction_coordinator.clusters.<ID>.contact_points` | Contact point of target cluster `<ID>` (`indirect:` / `direct-kubernetes:`) | `(empty)` (required per ID) | Validated as in §2.4 | tc |
| `scalar.db.cluster.transaction_coordinator.clusters.<ID>.contact_port` | Node port of target cluster `<ID>` | `60053` | | tc |
| `scalar.db.cluster.transaction_coordinator.clusters.<ID>.<rest>` | Overrides `scalar.db.cluster.<rest>` for the connection to `<ID>` only (e.g. `tls.ca_root_cert_path`, `hop_limit`) | — | Docs: `scalardb-cluster-configurations.mdx:1848-1852` | tc |

### 9.2 TC node

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.transaction_coordinator.port` | TC gRPC port (§1.1) | `60055` | | tc |
| `scalar.db.cluster.transaction_coordinator.admin.port` | Separate admin (pause) port | `(empty)` (admin on the main port) | | tc |
| `scalar.db.cluster.transaction_coordinator.prometheus_exporter_port` | Prometheus exporter port | `9080` | | tc |
| `scalar.db.cluster.transaction_coordinator.standalone_mode.enabled` | Single-node mode (STATIC membership on `127.0.0.1`) | `false` | | tc |
| `scalar.db.cluster.transaction_coordinator.decommissioning_duration_secs` | Seconds to stay decommissioned before shutdown | `30` | | tc |
| `scalar.db.cluster.transaction_coordinator.grpc.max_inbound_message_size` | TC server's max inbound message size; also copied to `scalar.db.cluster.grpc.max_inbound_message_size` if that is unset | `(empty)` (gRPC default) | | tc |
| `scalar.db.cluster.transaction_coordinator.grpc.max_inbound_metadata_size` | Same for metadata | `(empty)` (gRPC default) | | tc |
| `scalar.db.cluster.transaction_coordinator.grpc.max_connection_age_millis` | Max connection age | `(empty)` (effectively unlimited) | Applied only if set | tc |
| `scalar.db.cluster.transaction_coordinator.grpc.max_connection_age_grace_millis` | Grace after max age | `(empty)` (effectively unlimited) | | tc |

### 9.3 TC wire encryption and shared settings

| Property | Description | Default | Notes | Group |
|---|---|---|---|---|
| `scalar.db.cluster.transaction_coordinator.tls.cert_chain_path` | Server certificate chain of the TC | `(empty)` | **Required when `scalar.db.cluster.tls.enabled=true`**, otherwise `IllegalArgumentException` at start | tc |
| `scalar.db.cluster.transaction_coordinator.tls.private_key_path` | Server private key of the TC | `(empty)` | Same requirement | tc |

Settings the TC shares with cluster nodes (docs `scalardb-cluster-configurations.mdx:1797-1810`):

- `scalar.db.transaction_manager`: must be `consensus-commit` (`jdbc` and `single-crud-operation` cannot be used).
- `scalar.db.cluster.membership.*`, `scalar.db.cluster.grpc.deadline_duration_millis`, `scalar.db.cluster.tls.*` (§1.1, §1.4).
- `scalar.db.active_transaction_management.expiration_time_millis` — **TC default `60000`**, and
  `…max_active_transactions` (Core default `10000`).
- `scalar.db.scan_fetch_size` for scans the TC runs on target clusters.
- `scalar.db.storage`, `scalar.db.contact_points`, … = the storage of the **Coordinator table**, not the target clusters
  (docs `scalardb-cluster-configurations.mdx:1954`). `scalar.db.contact_points` in the TC file is a database URL.

#### Pitfalls (Transaction Coordinator)

- **The TC and every participating cluster must use the same physical Coordinator table** (same database and
  `scalar.db.consensus_commit.coordinator.namespace`). The TC writes commit/abort decisions through its own storage. Each
  participant reads coordinator state through **its own node storage** and, when it finds an expired prepared record
  without a state row, writes `ABORTED` there. Participants have no call to ask the TC for a state. With separate tables,
  a transaction the TC committed can be aborted by a participant's lazy recovery. How each cluster points its
  `coordinator` namespace at the shared database (for example multi-storage namespace mapping) is a deployment choice.
- Group commit cannot be used on the TC: the Consensus Commit coordinator throws "Group commit is not yet supported on the
  new Coordinator implementation".
- `scalar.db.cluster.auth.enabled` has no effect on the TC; it forwards the caller's token to the participants
  (attach-only, cannot refresh). Restrict network access to the TC.
- The default TC Prometheus port (`9080`) equals the node default; change one of them if a TC and a node share a host.
- Keys under `scalar.db.cluster.transaction_coordinator.` and `scalar.db.cluster.node.` are not interchangeable even when
  the suffix is the same (e.g. `standalone_mode.enabled`); a node-prefixed key in the TC file is ignored
  (docs `scalardb-cluster-configurations.mdx:1787`).
- OpenTelemetry keys (§1.5) are not read by the TC.
- The TC validates only that `clusters` is non-empty and unique. A client whose `scalar.db.cluster.id` is not in the list
  fails later with `DB-CLUSTER-10075`.
