# ScalarDB 3.18.0 リファレンス — Cluster 設定プロパティ

ScalarDB Cluster 固有の設定プロパティ。Core 共通の `scalar.db.*`(storage / transaction / consensus commit 等)は
[`10-configuration-core.md`](./10-configuration-core.md) を参照(Cluster ノードも内部で Core 設定を使う)。

## 典拠

- ソース: `scalardb-cluster` タグ `v3.18.0`(commit `36cf410a`)
- Docs: `docs-scalardb` ブランチ `3.18`(commit `834dc2af`)/ `docs/scalardb-cluster/scalardb-cluster-configurations.mdx` ほか
  (`integrate-oidc-for-access-control.mdx`, `authorize-with-abac.mdx`, `encrypt-data-at-rest.mdx`, `remote-replication.mdx`, `getting-started-with-vector-search.mdx`)

## プレフィックス階層

| 定数 | 値 |
|---|---|
| `DatabaseConfig.PREFIX`(Core) | `scalar.db.` |
| `ClusterConfig.PREFIX` | `scalar.db.cluster.` |
| `ClusterNodeConfig.PREFIX` | `scalar.db.cluster.node.` |
| `ClusterInternalConfig.PREFIX` | `scalar.db.cluster.internal.` |
| `MembershipConfig.PREFIX` | `scalar.db.cluster.membership.` |
| `ClusterClientConfig.PREFIX` | `scalar.db.cluster.client.` |
| `AuthConfig.PREFIX` | `scalar.db.cluster.auth.` |
| `AbacConfig.PREFIX` | `scalar.db.cluster.abac.` |
| `EncryptionConfig.PREFIX` | `scalar.db.cluster.encryption.` |
| SQL client(`SqlConfig` + `cluster_mode`) | `scalar.db.sql.cluster_mode.` |
| Replication | `scalar.db.replication.` |
| Embedding | `scalar.db.embedding.` |

列の定義・Group 凡例は [`10-configuration-core.md`](./10-configuration-core.md) と共通。Group 名は Cluster 用に細分している。

---

## 1. ノード / 共通(サーバ側)

### 1.1 共通(ClusterConfig)

典拠: `common/…/ClusterConfig.java`。ノード・クライアント双方が参照する基盤設定。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.port` | クラスタノードのポート番号 | `60053` | `ClusterConfig.java:21,81,180-182` | cluster-common |
| `scalar.db.cluster.node_cache.expiration_time_millis` | クラスタノードキャッシュの有効期限(ミリ秒) | `30000` | `ClusterConfig.java:24-25,84,184-189`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.distribution.consistent_hashing.vnode.count` | コンシステントハッシュ分散用の仮想ノード数 | `256` | `ClusterConfig.java:31-32,87,191-196`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.grpc.deadline_duration_millis` | gRPC のデッドライン期間(ミリ秒)。ノード間通信のためノード側にも必要 | `60000` | `ClusterConfig.java:45-46,93,120-121` | cluster-common |
| `scalar.db.cluster.grpc.max_inbound_message_size` | 単一 gRPC フレームの最大メッセージサイズ | `(空)`(gRPC 既定値) | `ClusterConfig.java:53-54,122`。リテラル `null` | cluster-common |
| `scalar.db.cluster.grpc.max_inbound_metadata_size` | 受信を許可するメタデータ最大サイズ | `(空)`(gRPC 既定値) | `ClusterConfig.java:61-62,123`。リテラル `null` | cluster-common |
| `scalar.db.cluster.auth.enabled` | 認証・認可を有効化するか | `false` | `ClusterConfig.java:65,198-200` | cluster-common |
| `scalar.db.cluster.tls.enabled` | ワイヤ暗号化(TLS)を有効化するか | `false` | `ClusterConfig.java:68,202-204` | cluster-common |
| `scalar.db.cluster.tls.ca_root_cert_pem` | TLS 用カスタム CA ルート証明書(PEM データ) | `(空)` | `ClusterConfig.java:71,130-131`。`pem` と `path` 両指定時は `pem` 優先 | cluster-common |
| `scalar.db.cluster.tls.ca_root_cert_path` | TLS 用カスタム CA ルート証明書(ファイルパス) | `(空)` | `ClusterConfig.java:74,132-133`。`pem` 未指定時のみ読込 | cluster-common |
| `scalar.db.cluster.tls.override_authority` | TLS 通信用のカスタム authority(接続先ホストは変えない。主にテスト用) | `(空)` | `ClusterConfig.java:77-78,137` | cluster-common |

> **注(コード未消費)**: `scalar.db.cluster.hop_limit`(定数 `ClusterConfig.java:38,90`、既定 `3`)は
> `ClusterConfig` のコンストラクタでは読まれずフィールド/getter も無い。実際にホップ制限として読まれるのは
> `scalar.db.cluster.internal.hop_limit`(§1.3)およびクライアント経路(§2)。

### 1.2 ノード(ClusterNodeConfig)

典拠: `node/…/ClusterNodeConfig.java`(プレフィックス `scalar.db.cluster.node.`, `:15`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.prometheus_exporter_port` | Prometheus exporter のポート番号 | `9080` | `ClusterNodeConfig.java:18,59,89-90` | cluster-node |
| `scalar.db.cluster.node.decommissioning_duration_secs` | シャットダウン時にデコミッションされるまでの秒数 | `30` | `ClusterNodeConfig.java:21-22,60,91-92` | cluster-node |
| `scalar.db.cluster.node.grpc.max_inbound_message_size` | 受信を許可する単一 gRPC メッセージ最大サイズ | `(空)`(gRPC 既定値) | `ClusterNodeConfig.java:27-28,96`。リテラル `null` | cluster-node |
| `scalar.db.cluster.node.grpc.max_inbound_metadata_size` | 受信を許可するメタデータ最大サイズ | `(空)`(gRPC 既定値) | `ClusterNodeConfig.java:34-35,97`。リテラル `null` | cluster-node |
| `scalar.db.cluster.node.grpc.max_connection_age_millis` | チャネルが存在可能な最大時間 | `(空)`(実効 `Integer.MAX_VALUE`=無限) | `ClusterNodeConfig.java:38-39,98`。コードは `null`、Docs は `Integer.MAX_VALUE` | cluster-node |
| `scalar.db.cluster.node.grpc.max_connection_age_grace_millis` | 最大存続時間到達後の猶予期間 | `(空)`(実効 `Integer.MAX_VALUE`=無限) | `ClusterNodeConfig.java:42-43,99-100`。同上 | cluster-node |
| `scalar.db.cluster.node.admin.port` | 管理用 gRPC サーバのポート。設定時は pause/unpause/checkPaused を専用ポートで稼働 | `(空)`(未設定時は `node.port` を使用) | `ClusterNodeConfig.java:48,94` | cluster-node |
| `scalar.db.cluster.node.standalone_mode.enabled` | スタンドアロンモードを有効化するか。有効時は `membership.*` を無視 | `false` | `ClusterNodeConfig.java:51,179-181` | cluster-node |
| `scalar.db.cluster.node.tls.cert_chain_path` | TLS 通信に使う証明書チェーンファイル | `(空)` | `ClusterNodeConfig.java:54,109` | cluster-node |
| `scalar.db.cluster.node.tls.private_key_path` | TLS 通信に使う秘密鍵ファイル | `(空)` | `ClusterNodeConfig.java:57,110` | cluster-node |

### 1.3 内部通信(ClusterInternalConfig)

典拠: `internal-common/…/ClusterInternalConfig.java`(プレフィックス `scalar.db.cluster.internal.`, `:16`)。ノード間内部通信用。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.internal.node.port` | ノード間内部通信用 gRPC サーバのポート番号 | `60054` | `ClusterInternalConfig.java:19,56,106-108` | cluster-node |
| `scalar.db.cluster.internal.hop_limit` | ホップ数制限(リクエスト無限循環防止) | `3` | `ClusterInternalConfig.java:25,59,73`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.internal.grpc.deadline_duration_millis` | 内部 gRPC のデッドライン期間(ミリ秒) | `60000` | `ClusterInternalConfig.java:32-33,62,74-75`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.internal.grpc.max_inbound_message_size` | 単一 gRPC フレームの最大メッセージサイズ | `(空)`(gRPC 既定値) | `ClusterInternalConfig.java:40-41,76`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.internal.grpc.max_inbound_metadata_size` | 受信を許可するメタデータ最大サイズ | `(空)`(gRPC 既定値) | `ClusterInternalConfig.java:48-49,77`。[公式Docs未掲載] | cluster-common |
| `scalar.db.cluster.internal.singleton_name_for_cluster_request_router` | クラスタリクエストルータのシングルトン名(ソースコメントも「TODO: Add a description」) | `(空)` | `ClusterInternalConfig.java:52-53,78-79`。[公式Docs未掲載] | cluster-common |

### 1.4 メンバーシップ(MembershipConfig)

典拠: `common/…/MembershipConfig.java`(プレフィックス `scalar.db.cluster.membership.`, `:15`)。
`standalone_mode.enabled=false`(通常のクラスタ)時に有効。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.membership.type` | メンバーシップ方式 | 実質必須(未設定は例外) | `MembershipConfig.java:18,48`。enum: `KUBERNETES` / `STATIC`(`MembershipType.java`)。**リテラル既定 `""` は `valueOf` で例外**。Docs は既定 `KUBERNETES` と記載(コードと不一致) | membership |
| `scalar.db.cluster.membership.kubernetes.endpoint.namespace_name` | `KUBERNETES` 方式用。エンドポイントの namespace 名 | `default` | `MembershipConfig.java:24-25,50-51` | membership |
| `scalar.db.cluster.membership.kubernetes.endpoint.name` | `KUBERNETES` 方式用。メンバーシップ情報取得先エンドポイント名 | `(空)` | `MembershipConfig.java:31,52` | membership |
| `scalar.db.cluster.membership.static.ip_addresses` | `STATIC` 方式用。静的クラスタノードのカンマ区切り IP アドレス | `(空)`(空配列) | `MembershipConfig.java:37,54-55`。[公式Docs未掲載] | membership |

---

## 2. クライアント接続(アプリ側)

アプリは 2 系統のインターフェースで Cluster に接続する。CRUD/トランザクション API(プリミティブ)は §2.1、
SQL インターフェースは §2.2。TLS/gRPC のクライアント設定は §1.1 の `scalar.db.cluster.*`(ClusterConfig)を共有する。

### 2.1 プリミティブ CRUD/トランザクション(ClusterClientConfig)

典拠: `client/…/ClusterClientConfig.java`(プレフィックス `scalar.db.cluster.client.`)。
`scalar.db.transaction_manager=cluster` を指定して使う。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.transaction_manager` | クラスタ利用時は `cluster` を指定(不一致で例外) | `(空)` | 定数値 `cluster`(`ClusterClientConfig.java:16,62-66`) | cluster-client |
| `scalar.db.contact_points` | 接続先。client mode を接頭辞で表現: `indirect:<LB の IP>` / `direct-kubernetes:<namespace>/<endpoint>`(または `direct-kubernetes:<endpoint>`)/ `singleton:<name>`。必須(空で例外) | `(空)` | client mode enum: `indirect` / `direct-kubernetes` / `singleton`(内部専用)。`ClusterClientConfig.java:68-111,140-147` | cluster-client |
| `scalar.db.contact_port` | 接続先ポート。indirect/direct-kubernetes でのみ使用 | `60053` | リテラル `0`→実効 `60053`(`ClusterClientConfig.java:30,78,85,149-153`) | cluster-client |
| `scalar.db.cluster.hop_limit` | ホップ数上限 | `3` | `ClusterClientConfig.java:115-117` / `ClusterConfig.java:38,90` | cluster-client |
| `scalar.db.cluster.auth.enabled` | 認証・認可を有効化するか | `false` | `ClusterClientConfig.java:120` / `ClusterConfig.java:65` | cluster-client |
| `scalar.db.cluster.client.auth.type` | クライアントの認証方式 | `userpass` | enum `AuthType`: `USERPASS` / `OIDC_JWT`(`ClusterClientConfig.java:22,121-124`)。[公式Docs未掲載] | cluster-client |
| `scalar.db.username` | ユーザー名(userpass 認証時) | `(空)` | `ClusterClientConfig.java:125` / `DatabaseConfig.java:47` | cluster-client |
| `scalar.db.password` | パスワード(userpass 認証時) | `(空)` | `ClusterClientConfig.java:126` / `DatabaseConfig.java:48` | cluster-client |
| `scalar.db.cluster.client.auth.oidc_jwt.access_token` | OIDC_JWT 認証時に付与する JWT アクセストークン | `(空)` | `ClusterClientConfig.java:23,127`。[公式Docs未掲載] | cluster-client |
| `scalar.db.cluster.client.auth.userpass_cache.size` | userpass 認証結果のキャッシュサイズ | `10000` | `ClusterClientConfig.java:28,32,128-130`。[公式Docs未掲載] | cluster-client |
| `scalar.db.cluster.client.scan_fetch_size` | Scanner が一度に取得するレコード数 | `10` | `ClusterClientConfig.java:20,31,132-133` | cluster-client |
| `scalar.db.cluster.client.piggyback_begin.enabled` | 最初の CRUD まで begin を遅延し begin RPC を削減 | `false` | 有効時 begin 前の `getId()` は例外、`resume()`/`join()` は常に例外(`ClusterClientConfig.java:25,134-135`) | cluster-client |
| `scalar.db.cluster.client.write_buffering.enabled` | 無条件書き込みをバッファしバッチ実行して RPC 削減 | `false` | 有効時 `resume()`/`join()` は常に例外(`ClusterClientConfig.java:26,136-137`) | cluster-client |

### 2.2 SQL インターフェース(ClusterClientSqlConfig)

典拠: `client/…/sql/ClusterClientSqlConfig.java`(プレフィックス `scalar.db.sql.cluster_mode.`)。
`scalar.db.sql.connection_mode=cluster` を指定して使う。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.sql.connection_mode` | クラスタ SQL 利用時は `cluster` を指定(不一致で例外) | `(空)` | 定数値 `cluster`(`ClusterClientSqlConfig.java:19,60-64`) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.contact_points` | 接続先。`indirect:<LB の IP>` / `direct-kubernetes:<namespace>/<endpoint>`(または `<endpoint>`)。必須(null で例外) | `(空)` | client mode enum: `indirect` / `direct-kubernetes`(`singleton` なし)。`ClusterClientSqlConfig.java:22,66-100,123-131` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.contact_port` | 接続先ポート番号 | `60053` | `ClusterClientSqlConfig.java:23,35,76,84` | cluster-client-sql |
| `scalar.db.cluster.hop_limit` | ホップ数上限 | `3` | `ClusterClientSqlConfig.java:102-103` | cluster-client-sql |
| `scalar.db.cluster.auth.enabled` | 認証・認可を有効化するか | `false` | `ClusterClientSqlConfig.java:106,189-191` | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.type` | クライアントの認証方式 | `userpass` | enum `AuthType`: `USERPASS` / `OIDC_JWT`(`ClusterClientSqlConfig.java:24,107-109`)。[公式Docs未掲載] | cluster-client-sql |
| `scalar.db.sql.cluster_mode.username` | ユーザー名 | `(空)` | JDBC プロパティ `user` が非空ならそちらを優先(`ClusterClientSqlConfig.java:26,110,193-201`) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.password` | パスワード | `(空)` | JDBC プロパティ `password` があればそちらを優先(`ClusterClientSqlConfig.java:27,111,203-215`) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.oidc_jwt.access_token` | OIDC_JWT 認証時の JWT アクセストークン | `(空)` | `ClusterClientSqlConfig.java:25,112`。[公式Docs未掲載] | cluster-client-sql |
| `scalar.db.sql.cluster_mode.auth.userpass_cache.size` | userpass 認証結果のキャッシュサイズ | `10000` | `ClusterClientSqlConfig.java:33,113-117`。[公式Docs未掲載] | cluster-client-sql |
| `scalar.db.sql.cluster_mode.client.piggyback_begin.enabled` | 最初の execute まで begin を遅延 | `false` | 有効時 begin 前の `getTransactionId()` は `Optional.empty()`、`resume()`/`join()` は常に例外(`ClusterClientSqlConfig.java:29,30,119`) | cluster-client-sql |
| `scalar.db.sql.cluster_mode.client.write_buffering.enabled` | INSERT/UPSERT をバッファしバッチ実行 | `false` | 有効時 `resume()`/`join()` は常に例外(`ClusterClientSqlConfig.java:29,31,120`) | cluster-client-sql |

> クライアント側 TLS/gRPC は §1.1 の `scalar.db.cluster.tls.*` / `scalar.db.cluster.grpc.*` を共有する。

### 2.3 その他のトランザクションマネージャ

| プロパティ | 説明 | 備考 |
|---|---|---|
| `scalar.db.transaction_manager=cluster-transaction` | 既存 tx マネージャをラップするクラスタ tx | `ClusterTransactionConfig`。**必須**: `scalar.db.cluster.transaction.original_transaction_manager`(ラップ対象名、未指定で例外, `:15,17,28-32`)。[公式Docs未掲載] |
| `scalar.db.sql.connection_mode=cluster-sql-transaction` | 上記の SQL 版 | `ClusterSqlTransactionConfig`。固有プロパティなし(識別子定数のみ)。[公式Docs未掲載] |

---

## 3. 認証・認可(auth / OIDC)

`scalar.db.cluster.auth.enabled=true`(§1.1)で有効化。サーバ側検証設定は以下。クライアント側の認証方式は §2 の `auth.type`。

### 3.1 AuthConfig

典拠: `auth/…/AuthConfig.java`(プレフィックス `scalar.db.cluster.auth.`, `:15`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.auth.cache_expiration_time_millis` | 認証・認可情報キャッシュの有効期限(ミリ秒) | `60000`(1 分) | `AuthConfig.java:17,24,40` | auth |
| `scalar.db.cluster.auth.auth_token_expiration_time_minutes` | 認証・認可トークンの有効期限(分) | `1440`(1 日) | `AuthConfig.java:18-19,25,41-45` | auth |
| `scalar.db.cluster.auth.auth_token_gc_thread_interval_minutes` | トークン GC スレッドの実行間隔(分) | `360`(6 時間) | `AuthConfig.java:20-21,26,46-50` | auth |
| `scalar.db.cluster.auth.pepper` | パスワードのハッシュ化前に付加する秘密値(pepper) | `(空)` | `AuthConfig.java:22,31,51,69-71`。未指定時は pepper なし | auth |

### 3.2 OIDC(OidcAuthConfig)

典拠: `auth/…/OidcAuthConfig.java`(プレフィックス `scalar.db.cluster.auth.oidc.`, `:19`)。
`trusted_issuers` が非空だと OIDC 有効化。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.auth.oidc.trusted_issuers` | 信頼する issuer(`iss`)のリスト(カンマ区切り) | `(空)` | `OidcAuthConfig.java:24,56-58,66-70,90-106`。**非空で OIDC 有効化**。**2 件以上指定で例外**(実質 1 件のみ) | oidc |
| `scalar.db.cluster.auth.oidc.username.claim_name` | ScalarDB ユーザー名を抽出する JWT クレーム名 | `(空)`(必須) | `OidcAuthConfig.java:21-22,61-64`。null/空で例外(`OIDC_USERNAME_CLAIM_REQUIRED`) | oidc |
| `scalar.db.cluster.auth.oidc.audience.name` | JWT の `aud` クレームに含まれるべき audience 値 | `scalardb` | `OidcAuthConfig.java:23,38,65` | oidc |
| `scalar.db.cluster.auth.oidc.jwt.jwks.url_cache.expiration_seconds` | JWKS URL キャッシュの有効期限(秒) | `86400`(24 時間) | `OidcAuthConfig.java:25-28,39,71-75`。[公式Docs未掲載] | oidc |
| `scalar.db.cluster.auth.oidc.jwt.jwks.content_cache.expiration_seconds` | JWKS コンテンツキャッシュの有効期限(秒) | `86400`(24 時間) | `OidcAuthConfig.java:25-30,40-41,76-80`。[公式Docs未掲載] | oidc |
| `scalar.db.cluster.auth.oidc.jwt.access_token.require_at_jwt_typ` | アクセストークンの `typ` を `at+jwt`/`application/at+jwt` に強制するか | `true` | `OidcAuthConfig.java:25,31-32,42,81-83` | oidc |
| `scalar.db.cluster.auth.oidc.jwt.max_clock_skew_seconds` | JWT 検証時に許容するクロックスキュー(秒) | `10` | `OidcAuthConfig.java:25,33,43,84-85`。[公式Docs未掲載] | oidc |
| `scalar.db.cluster.auth.oidc.abac.claim_name` | ABAC 情報を格納する JWT クレーム名 | `scalardb_abac` | `OidcAuthConfig.java:34-35,44,86` | oidc |
| `scalar.db.cluster.auth.oidc.abac.enabled` | OIDC-ABAC 連携(JWT クレームによる動的制限)を有効化するか | `false` | `OidcAuthConfig.java:34,36,87`。ABAC クレームは権限を制限のみ可(拡張不可) | oidc |

---

## 4. ABAC(属性ベースアクセス制御)

典拠: `abac/…/AbacConfig.java`(プレフィックス `scalar.db.cluster.abac.`, `:14`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.abac.enabled` | ABAC 機能を有効化するか | `false` | `AbacConfig.java:14,16-17,33`。有効化には認証・認可の有効化も必要 | abac |
| `scalar.db.cluster.abac.cache_expiration_time_millis` | ABAC メタデータキャッシュの有効期限(ミリ秒) | `60000`(1 分) | `AbacConfig.java:19-22,34-35`。ポリシー更新反映まで最大この時間 | abac |

---

## 5. 保存時暗号化(encryption)

`scalar.db.cluster.encryption.enabled=true` で有効化し、`type` で実装を選ぶ。

### 5.1 共通(EncryptionConfig)

典拠: `encryption/…/EncryptionConfig.java`(プレフィックス `scalar.db.cluster.encryption.`, `:11`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.enabled` | 暗号化機能を有効にするか | `false` | `EncryptionConfig.java:14,58` | encryption |
| `scalar.db.cluster.encryption.type` | 暗号化実装タイプ | `(空)`(必須) | `EncryptionConfig.java:17,35-38`。未設定で例外。enum: `vault` / `self` | encryption |
| `scalar.db.cluster.encryption.delete_data_encryption_key_on_drop_table.enabled` | テーブル drop 時にデータ暗号鍵(DEK)を削除するか | `false` | `EncryptionConfig.java:20-21,42` | encryption |

### 5.2 self-encryption(`type=self`)

典拠: `encryption/…/self/SelfConfig.java`(プレフィックス `scalar.db.cluster.encryption.self.`, `:14`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.self.key_type` | 鍵タイプ | `AES128_GCM` | `SelfConfig.java:17,41`。**JavaDoc は `AES256_GCM` と誤記、実効は `AES128_GCM`**。許容値: `AES128_GCM` / `AES256_GCM` / `AES128_EAX` / `AES256_EAX` / `AES128_CTR_HMAC_SHA256` / `AES256_CTR_HMAC_SHA256` / `CHACHA20_POLY1305` / `XCHACHA20_POLY1305` | encryption-self |
| `scalar.db.cluster.encryption.self.associated_data_required` | AEAD で関連データ(AD)を必須とするか | `false` | `SelfConfig.java:20,55` | encryption-self |
| `scalar.db.cluster.encryption.self.kubernetes.secret.namespace_name` | Kubernetes Secret の namespace 名 | `default` | `SelfConfig.java:23-24,57` | encryption-self |
| `scalar.db.cluster.encryption.self.data_encryption_key_cache_expiration_time` | DEK キャッシュの有効期限(ミリ秒) | `60000`(60 秒) | `SelfConfig.java:29-33,58-62` | encryption-self |

### 5.3 HashiCorp Vault(`type=vault`)

典拠: `encryption/…/vault/VaultConfig.java`(プレフィックス `scalar.db.cluster.encryption.vault.`, `:19`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.encryption.vault.address` | Vault サーバのアドレス | `(空)`(必須) | `VaultConfig.java:22,65-68`。未設定で例外 | encryption-vault |
| `scalar.db.cluster.encryption.vault.token` | Vault 認証トークン | `(空)`(必須) | `VaultConfig.java:28,73-76`。未設定で例外 | encryption-vault |
| `scalar.db.cluster.encryption.vault.transit_secrets_engine_path` | transit secrets engine のパス | `transit` | `VaultConfig.java:25,70-71` | encryption-vault |
| `scalar.db.cluster.encryption.vault.namespace` | Vault の名前空間(任意) | `(空)` | `VaultConfig.java:31,78` | encryption-vault |
| `scalar.db.cluster.encryption.vault.key_type` | 鍵タイプ | `aes128-gcm96` | `VaultConfig.java:34,79`。**JavaDoc は `aes256-gcm96` と誤記、実効は `aes128-gcm96`**。許容値: `aes128-gcm96` / `aes256-gcm96` / `chacha20-poly1305` | encryption-vault |
| `scalar.db.cluster.encryption.vault.associated_data_required` | AEAD で関連データ(AD)を必須とするか | `false` | `VaultConfig.java:37,88` | encryption-vault |
| `scalar.db.cluster.encryption.vault.column_batch_size` | Vault への 1 リクエストに含める列数 | `64` | `VaultConfig.java:40,51,89` | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.ca_root_cert_pem` | Vault との TLS 用 CA ルート証明書(PEM) | `(空)` | `VaultConfig.java:43,91-92`。`pem` 優先。[公式Docs未掲載] | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.ca_root_cert_path` | Vault との TLS 用 CA ルート証明書(パス) | `(空)` | `VaultConfig.java:46,93-94`。[公式Docs未掲載] | encryption-vault |
| `scalar.db.cluster.encryption.vault.tls.override_authority` | Vault との TLS 用カスタム authority | `(空)` | `VaultConfig.java:49,98`。[公式Docs未掲載] | encryption-vault |

---

## 6. ライセンス(licensing)

典拠: `licensing/…/ClusterNodeWithLicenseCheckerConfig.java`(プレフィックス `scalar.db.cluster.node.`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cluster.node.licensing.license_key` | ライセンスキー | `(空)` | `ClusterNodeWithLicenseCheckerConfig.java:12,22` | licensing |
| `scalar.db.cluster.node.licensing.license_check_cert_pem` | ライセンスチェック証明書(PEM データ) | `(空)` | `ClusterNodeWithLicenseCheckerConfig.java:13-14,24-25`。`pem` 優先 | licensing |
| `scalar.db.cluster.node.licensing.license_check_cert_path` | ライセンスチェック証明書(ファイルパス) | `(空)` | `ClusterNodeWithLicenseCheckerConfig.java:15-16,26-28`。[公式Docs未掲載] | licensing |

---

## 7. レプリケーション(replication)

Enterprise の Remote Replication 機能。プライマリ(log-writer)/バックアップ(log-applier)の 2 サイト構成。
`ReplicationConfig` はコンストラクタで `scalar.db.transaction.manager` を `consensus-commit` に内部強制する(`:39`)。

### 7.1 共通(ReplicationConfig)

典拠: `replication/common/…/ReplicationConfig.java`。両サイトで一致必須。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.replication.partition_count` | `transaction_groups` テーブルのパーティション数。両サイトで一致必須(変更に両サイト再起動) | `256` | `ReplicationConfig.java:20,25,42` | replication |
| `scalar.db.replication.repl_db.namespace` | レプリケーションテーブルの名前空間名。両サイトで一致必須 | `replication` | `ReplicationConfig.java:21,26,44` | replication |
| `scalar.db.replication.record_table_suffix` | レプリケーションレコードメタデータテーブルのサフィックス | `__records` | `ReplicationConfig.java:22-23,27,46` | replication |

### 7.2 log-writer(プライマリ: 書き込み捕捉)

典拠: `replication/log-writer/…/LogWriterConfig.java`。
コンストラクタで Consensus Commit の one-phase commit が有効だと例外(非サポート, `:50-53`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.replication.log_writer.enabled` | LogWriter 機能の有効/無効 | `false` | `LogWriterConfig.java:22,77` | replication |
| `scalar.db.replication.log_writer.compression_type` | レプリケーション DB に保存する書き込みの圧縮方式 | `GZIP` | `LogWriterConfig.java:23,33,100-106`。enum: `NONE` / `GZIP` | replication |
| `scalar.db.replication.log_writer.group_commit.retention.time_millis` | トランザクショングループをコミットするまでの最大待機時間 | `100` | `LogWriterConfig.java:24-25,35,59` | replication |
| `scalar.db.replication.log_writer.group_commit.retention.values` | バッチにまとめる最大トランザクション数 | `32` | `LogWriterConfig.java:26-27,34,56` | replication |
| `scalar.db.replication.log_writer.group_commit.timeout_check_interval_millis` | グループコミットのタイムアウトチェック間隔 | `20` | `LogWriterConfig.java:28-29,36,64` | replication |
| `scalar.db.replication.log_writer.group_commit.max_thread_pool_size` | グループコミット処理の最大スレッドプールサイズ | `4096` | `LogWriterConfig.java:30-31,37,69` | replication |

### 7.3 log-applier(バックアップ: 適用)

典拠: `replication/log-applier/…/LogApplierConfig.java`。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.replication.log_applier.enabled` | LogApplier 機能の有効/無効 | `false` | `LogApplierConfig.java:20,143` | replication |
| `scalar.db.replication.log_applier.transaction.expiration_millis` | クリーンアップ用のトランザクション有効期限 | `30000` | `LogApplierConfig.java:21-22,57,97` | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.threads` | トランザクショングループのスキャナスレッド数 | `16` | `LogApplierConfig.java:23-24,58,100` | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.fetch_size` | 1 回のスキャンで取得するレコード数 | `32` | `LogApplierConfig.java:25-26,59,105`。`scalar.db.scan_fetch_size` にも反映(`:193-194`) | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.wait_millis` | スキャン間の待機時間(ミリ秒) | `1000` | `LogApplierConfig.java:27-28,60,110` | replication |
| `scalar.db.replication.log_applier.transaction_group_scanner.dedup.expiration_millis` | トランザクショングループ重複排除キャッシュの有効期限 | `10000` | `LogApplierConfig.java:29-30,61,115` | replication |
| `scalar.db.replication.log_applier.transaction_applier.threads` | トランザクション適用スレッド数 | `128` | `LogApplierConfig.java:31-32,62,120`。**Docs は `transaction_handler.threads` と誤記(実ソースは `transaction_applier.threads`)** | replication |
| `scalar.db.replication.log_applier.max_record_version` | 整数オーバーフロー防止用の最大レコードバージョン(開発用) | `2147482647` | `LogApplierConfig.java:33,63,67-69,122`。`Integer.MAX_VALUE - 1000` | replication |
| `scalar.db.replication.log_applier.replication_status_service.threads` | レプリケーションステータスサービスのスレッド数 | `16` | `LogApplierConfig.java:34-35,71,123` | replication |
| `scalar.db.replication.log_applier.coordinator_state_cache.expiration_millis` | コーディネータ状態キャッシュの有効期限 | `30000` | `LogApplierConfig.java:36-37,77-78,133` | replication |
| `scalar.db.replication.log_applier.coordinator_state_cache.size` | コーディネータ状態キャッシュの最大サイズ | `1000` | `LogApplierConfig.java:38-39,80,138` | replication |
| `scalar.db.replication.log_applier.write_operation.expiration_millis` | GC 用の書き込み操作有効期限 | `86400000`(1 日) | `LogApplierConfig.java:51-52,74,128` | replication |

### 7.4 admin / client / provider

| プロパティ / 定数 | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.replication.admin.enabled` | レプリケーション Admin 機能。`true`(または `log_applier.enabled=true`)でアプリテーブル作成時にレプリケーションメタデータテーブルが自動作成される | `false` | `ReplicationAdminConfig.java:13,15,22`。[公式Docs未掲載(挙動は remote-replication.mdx:694)] | replication |
| `scalar.db.replication.client.contact_points` | クラスタ接続先。`indirect:<HOST>` / `direct-kubernetes:<NAMESPACE>/<ENDPOINT>`(または `<ENDPOINT>`)。必須 | `(空)`(必須) | `ReplicationClientConfig.java:13,30-35`。[公式Docs未掲載] | replication |
| `scalar.db.replication.client.contact_port` | クラスタノードのポート | `60053` | `ReplicationClientConfig.java:14,16,42,50`。[公式Docs未掲載] | replication |
| `scalar.db.cluster.hop_limit` | ホップ数上限(replication client 経由) | `3` | `ReplicationClientConfig.java:71-72`。共通クラスタ設定を流用 | replication |
| `scalar.db.transaction_manager=replication-consensus-commit` | provider の tx マネージャ名(識別子定数のみ、固有プロパティなし) | — | `ReplicationTransactionConfig.java:3-5`。[公式Docs未掲載] | replication |

---

## 8. 埋め込み / ベクトル検索(embedding)

Enterprise のベクトル検索機能。`scalar.db.embedding.enabled=true` で有効化。サーバ側(node)で store と model を定義し、
クライアントが名前で参照する。`<S>`=ストア名、`<M>`=モデル名(`stores` / `models` に列挙した名前)。

### 8.1 全体(EmbeddingConfig / node 側)

典拠: `node/…/embedding/EmbeddingConfig.java`(プレフィックス `scalar.db.embedding.`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.embedding.enabled` | 埋め込み(ベクトル検索)機能の有効/無効 | `false` | `EmbeddingConfig.java:13,113` | embedding |
| `scalar.db.embedding.stores` | 埋め込みストア名のカンマ区切りリスト | `(空)`(空配列) | `EmbeddingConfig.java:16,117-118` | embedding |
| `scalar.db.embedding.models` | 埋め込みモデル名のカンマ区切りリスト | `(空)`(空配列) | `EmbeddingConfig.java:19,258-259` | embedding |

### 8.2 ストア(`scalar.db.embedding.stores.<S>.*`)

`type` で種別を選び、種別ごとに固有プロパティを持つ。典拠: `EmbeddingConfig.java`。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.embedding.stores.<S>.type` | ストア種別 | `(空)` | `:121-123`。enum: `in-memory` / `opensearch` / `azure-cosmos-nosql` / `azure-ai-search` / `pgvector` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.server_url` | OpenSearch サーバ URL | `(空)` | `:33,125-128`(必須扱い) | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.api_key` | OpenSearch API キー | `(空)` | `:34,130` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.user_name` | ユーザ名 | `(空)` | `:35,136` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.password` | パスワード | `(空)` | `:36,142` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.region` | AWS リージョン | `(空)` | `:37,148` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.service_name` | AWS サービス名 | `(空)` | `:38,154` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.access_key_id` | AWS アクセスキー ID | `(空)` | `:39,160` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.secret_access_key` | AWS シークレットアクセスキー | `(空)` | `:40,166` | embedding |
| `scalar.db.embedding.stores.<S>.opensearch.index_name` | インデックス名 | `(空)` | `:41,173` | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.endpoint` | エンドポイント | `(空)` | `:45,179` | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.key` | キー | `(空)` | `:46,184` | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.database_name` | データベース名 | `(空)` | `:47,189` | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.container_name` | コンテナ名 | `(空)` | `:48-49,194` | embedding |
| `scalar.db.embedding.stores.<S>.azure-cosmos-nosql.dimensions` | 埋め込み次元数 | `1536` | `:50,199-201` | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.endpoint` | エンドポイント | `(空)` | `:54,204` | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.api_key` | API キー | `(空)` | `:55,209` | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.index_name` | インデックス名 | `(空)` | `:56,214` | embedding |
| `scalar.db.embedding.stores.<S>.azure-ai-search.dimensions` | 埋め込み次元数 | `1536` | `:57,219-221` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.host` | ホスト | `(空)` | `:61,224` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.port` | ポート | `5432` | `:62,229-230` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.user` | ユーザ | `(空)` | `:63,233` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.password` | パスワード | `(空)` | `:64,238` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.database` | データベース | `(空)` | `:65,243` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.table` | テーブル | `(空)` | `:66,248` | embedding |
| `scalar.db.embedding.stores.<S>.pgvector.dimensions` | 埋め込み次元数 | `1536` | `:67,253-255` | embedding |

### 8.3 モデル(`scalar.db.embedding.models.<M>.*`)

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.embedding.models.<M>.type` | モデル種別 | `(空)` | `:262-263`。enum: `in-process` / `bedrock-titan` / `azure-open-ai` / `vertex-ai` / `open-ai` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.region` | AWS リージョン | `(空)` | `:78,266` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.access_key_id` | アクセスキー ID | `(空)` | `:79,271` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.secret_access_key` | シークレットアクセスキー | `(空)` | `:80-81,276` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.model` | モデル名 | `(空)` | `:82,284` | embedding |
| `scalar.db.embedding.models.<M>.bedrock-titan.dimensions` | 次元数 | `(空)` | `:83,289-295` | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.endpoint` | エンドポイント | `(空)` | `:87,298` | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.api_key` | API キー | `(空)` | `:88,303` | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.deployment_name` | デプロイメント名 | `(空)` | `:89,308` | embedding |
| `scalar.db.embedding.models.<M>.azure-open-ai.dimensions` | 次元数 | `(空)` | `:90,313-317` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.project` | GCP プロジェクト | `(空)` | `:94,320` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.location` | ロケーション | `(空)` | `:95,325` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.endpoint` | エンドポイント | `(空)` | `:96,330-334` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.publisher` | パブリッシャ | `(空)` | `:97,337` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.model_name` | モデル名 | `(空)` | `:98,342` | embedding |
| `scalar.db.embedding.models.<M>.vertex-ai.output_dimensionality` | 出力次元数 | `(空)` | `:99-100,347-353` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.api_key` | API キー | `(空)` | `:104,356` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.model_name` | モデル名 | `(空)` | `:105,361-364` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.base_url` | ベース URL | `(空)` | `:106,367` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.organization_id` | 組織 ID | `(空)` | `:107,373` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.dimensions` | 次元数 | `(空)` | `:108,379` | embedding |
| `scalar.db.embedding.models.<M>.open-ai.user` | ユーザ識別子 | `(空)` | `:109,385` | embedding |

### 8.4 埋め込みクライアント(ScalarDbEmbeddingClientConfig)

典拠: `embedding/client/…/ScalarDbEmbeddingClientConfig.java`(プレフィックス `scalar.db.embedding.client.`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.embedding.client.contact_points` | クラスタ接続先。`indirect:<HOST>` / `direct-kubernetes:<NAMESPACE>/<ENDPOINT>`(または `<ENDPOINT>`)。必須 | `(空)`(必須) | `ScalarDbEmbeddingClientConfig.java:14,36-40` | embedding |
| `scalar.db.embedding.client.contact_port` | クラスタノードのポート | `60053` | `ScalarDbEmbeddingClientConfig.java:15,19,47,55`。[公式Docs未掲載] | embedding |
| `scalar.db.embedding.client.store` | 使用する埋め込みストアのインスタンス名(サーバ側 stores と対応) | `(空)` | `ScalarDbEmbeddingClientConfig.java:16,76` | embedding |
| `scalar.db.embedding.client.model` | 使用する埋め込みモデルのインスタンス名(サーバ側 models と対応) | `(空)` | `ScalarDbEmbeddingClientConfig.java:17,77` | embedding |
| `scalar.db.cluster.hop_limit` | ホップ数上限 | `3` | `ScalarDbEmbeddingClientConfig.java:74-75`。[公式Docs未掲載] | embedding |

---

## 付録: Docs と実装の乖離(90-deep-dives に転記予定)

| 項目 | Docs 表記 | 実装(v3.18.0) | 出典 |
|---|---|---|---|
| `scalar.db.cluster.membership.type` の既定 | `KUBERNETES` | 既定なし(未設定は `valueOf("")` で例外=実質必須) | `MembershipConfig.java:18,48` |
| `scalar.db.replication.log_applier` のスレッド数キー | `...transaction_handler.threads` | `...transaction_applier.threads`(実ソース) | `LogApplierConfig.java:31-32` |
| `encryption.self.key_type` の既定 | `AES128_GCM`(Docs 正) | 実効 `AES128_GCM`(ただし JavaDoc は `AES256_GCM` と誤記) | `SelfConfig.java:16,41` |
| `encryption.vault.key_type` の既定 | `aes128-gcm96`(Docs 正) | 実効 `aes128-gcm96`(ただし JavaDoc は `aes256-gcm96` と誤記) | `VaultConfig.java:33,79` |
| `scalar.db.cluster.hop_limit`(ClusterConfig) | — | 定数はあるがコンストラクタ未消費(getter 無し)。実際に効くのは `cluster.internal.hop_limit` とクライアント経路 | `ClusterConfig.java:38,90` |
| `node.grpc.max_connection_age_millis` / `_grace_millis` | `Integer.MAX_VALUE`(無限) | コード上リテラルは `null`(→ gRPC 既定=無限) | `ClusterNodeConfig.java:38-43` |
