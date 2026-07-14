# ScalarDB 3.18.0 リファレンス — Core 設定プロパティ

ScalarDB Core (OSS) の設定プロパティ(`scalar.db.*`)を、ソースコードを典拠にまとめた表。
Cluster 固有の設定は [`11-configuration-cluster.md`](./11-configuration-cluster.md) を参照。

## 典拠

- ソース: `scalardb` タグ `v3.18.0`(commit `1953f0131`)
- Docs: `docs-scalardb` ブランチ `3.18`(commit `834dc2af`)/ `docs/configurations.mdx`, `docs/database-configurations.mdx`, `docs/consensus-commit.mdx`, `docs/multi-storage-transactions.mdx`
- 主な典拠クラス: `config/DatabaseConfig.java`, `transaction/consensuscommit/ConsensusCommitConfig.java`,
  `transaction/singlecrudoperation/SingleCrudOperationTransactionConfig.java`,
  `storage/{cassandra,cosmos,dynamo,jdbc,multistorage}/…Config.java`,
  `storage/objectstorage/{s3,blobstorage,cloudstorage}/…Config.java`
- プレフィックス: `DatabaseConfig.PREFIX = "scalar.db."`(`DatabaseConfig.java:44`)

## 列の定義

| 列 | 意味 |
|---|---|
| 設定項目 (プロパティ名) | プロパティの完全修飾名 |
| 説明 | プロパティの目的・挙動 |
| 既定値 | 未設定時に**実効的に適用される値**。`(空)` は無効/未指定相当。リテラル既定値と実効値が異なる場合は実効値を記載し備考で明示 |
| 備考 | 典拠 `ソースファイル:行`、関連プロパティ、前提条件、非推奨・特殊挙動。`[公式Docs未掲載]` はソースのみで確認できる項目 |
| Group | 適用条件(下記凡例) |

## Group 列の凡例

| Group | 意味 |
|---|---|
| `base` | ストレージ/トランザクション方式に関わらず適用される基本設定 |
| `option` | 任意のチューニング・機能トグル |
| `transaction` | transaction manager の選択に関わる設定 |
| `consensus-commit` | `transaction_manager=consensus-commit` 時に意味を持つ |
| `cassandra` / `cosmos` / `dynamo` | 該当ストレージ選択時のみ |
| `jdbc` / `jdbc-pool` / `jdbc-admin-pool` | JDBC ストレージ(共通 / コネクションプール / admin プール) |
| `multi-storage` | `storage=multi-storage` 時のみ |
| `s3` / `blob-storage` / `cloud-storage` | Object Storage 各種選択時のみ |

---

## 1. 共通設定 (DatabaseConfig)

典拠: `config/DatabaseConfig.java`

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.storage` | ストレージ実装 | `cassandra` | `DatabaseConfig.java:49,101`。enum: `cassandra` / `jdbc` / `dynamo` / `cosmos` / `multi-storage` / `s3` / `blob-storage` / `cloud-storage` | base |
| `scalar.db.transaction_manager` | トランザクションマネージャー | `consensus-commit` | `DatabaseConfig.java:50,193-194`。enum: `consensus-commit` / `single-crud-operation`。後者では `scalar.db.consensus_commit.*` は無視 | transaction |
| `scalar.db.contact_points` | 接続先コンタクトポイント(カンマ区切り。JDBC は接続 URL、S3/Blob は `region-or-uri/bucket` 等ストレージ毎に意味が異なる) | `(空)` | `DatabaseConfig.java:45,102-103`(既定 `new String[0]`) | base |
| `scalar.db.contact_port` | 全コンタクトポイントのポート番号 | `0` | `DatabaseConfig.java:46,104-105`。`>= 0` を検証。`0` は各ストレージの既定ポートに委ねる | base |
| `scalar.db.username` | DB アクセス用ユーザー名(ストレージ毎に AWS access key / GCP project id 等に読み替え) | `(空)` | `DatabaseConfig.java:47,106`。`Optional` 保持 | base |
| `scalar.db.password` | DB アクセス用パスワード(ストレージ毎に AWS secret key / SA キー JSON / Cosmos key 等に読み替え) | `(空)` | `DatabaseConfig.java:48,107`。`Optional` 保持 | base |
| `scalar.db.default_namespace_name` | 名前空間未指定の操作に適用される既定名前空間 | `(空)` | `DatabaseConfig.java:59,115`。システム名前空間 `scalardb`(`DEFAULT_SYSTEM_NAMESPACE_NAME`, `:67`)とは別物 | option |
| `scalar.db.metadata.cache_expiration_time_secs` | メタデータキャッシュの有効期限(秒) | `60` | `DatabaseConfig.java:51-52,66,197-202`。`-1` で無期限 | option |
| `scalar.db.active_transaction_management.enabled` | 進行中トランザクション管理機能の有効化 | `true` | `DatabaseConfig.java:53-54,110-111`。[公式Docs未掲載] | option |
| `scalar.db.active_transaction_management.expiration_time_millis` | 進行中トランザクションのアイドル失効時間(ミリ秒) | `-1` | `DatabaseConfig.java:55-56,204-205`。`-1` は無期限 | option |
| `scalar.db.attribute_propagation.enabled` | 属性伝播の有効化 | `true` | `DatabaseConfig.java:57-58,114`。[公式Docs未掲載] | option |
| `scalar.db.cross_partition_scan.enabled` | パーティション横断スキャンの有効化 | `true` | `DatabaseConfig.java:60-61,116`。`filtering`/`ordering` を使うには本項 `true` 必須(`:122-128` で違反時例外) | option |
| `scalar.db.cross_partition_scan.filtering.enabled` | 横断スキャンのフィルタリング有効化 | `false` | `DatabaseConfig.java:62,117-118`。前提: `cross_partition_scan.enabled=true` | option |
| `scalar.db.cross_partition_scan.ordering.enabled` | 横断スキャンのオーダリング有効化 | `false` | `DatabaseConfig.java:63,119-120`。前提: `cross_partition_scan.enabled=true`。**オーダリングは JDBC のみ対応** | option |
| `scalar.db.scan_fetch_size` | ストレージスキャン 1 バッチあたりの取得レコード数 | `10` | `DatabaseConfig.java:64,68,130`。Object Storage 系では既定以外を設定すると warn を出し無視 | option |

> **後方互換で無視されるキー**: consensus commit の分離レベル旧キー `scalar.db.isolation_level` は
> `scalar.db.consensus_commit.isolation_level` の非推奨フォールバック(4.0.0 で削除予定、下記参照)。

---

## 2. トランザクションマネージャー

`scalar.db.transaction_manager` で選択する(既定 `consensus-commit`)。

- `consensus-commit`: 分散トランザクションプロトコル。設定は §3 を参照。
- `single-crud-operation`: 単一 CRUD 操作の軽量トランザクション。
  `SingleCrudOperationTransactionConfig.java` は**固有の `scalar.db.*` プロパティを一切持たない**
  (定数 `TRANSACTION_MANAGER_NAME="single-crud-operation"` のみ, `:7`)。選択時は `scalar.db.consensus_commit.*` は無視される。

---

## 3. Consensus Commit 設定

典拠: `transaction/consensuscommit/ConsensusCommitConfig.java`(全プロパティのプレフィックスは `scalar.db.consensus_commit.`, `:23`)。
`transaction_manager=consensus-commit` の場合のみ有効。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.consensus_commit.isolation_level` | 分離レベル | `SNAPSHOT` | `ConsensusCommitConfig.java:24,110-119`。enum: `SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED`。旧キー `scalar.db.isolation_level` にフォールバック(`:103-118`、4.0.0 で削除予定) | consensus-commit |
| `scalar.db.consensus_commit.coordinator.namespace` | Coordinator テーブルの名前空間名 | `(空)`(実効既定名 `coordinator`) | `ConsensusCommitConfig.java:25,131`。リテラル既定は `null`、実効的な既定名は `coordinator`(公式Docs) | consensus-commit |
| `scalar.db.consensus_commit.parallel_executor_count` | 並列実行のためのエグゼキュータ数 | `128` | `ConsensusCommitConfig.java:27,61,133-134` | consensus-commit |
| `scalar.db.consensus_commit.parallel_preparation.enabled` | 準備フェーズを並列実行するか | `true` | `ConsensusCommitConfig.java:28,135` | consensus-commit |
| `scalar.db.consensus_commit.parallel_validation.enabled` | 検証フェーズ(EXTRA_READ)を並列実行するか | `parallel_commit.enabled` の値(既定 `true`) | `ConsensusCommitConfig.java:29,140-141`。`parallel_commit.enabled` を継承 | consensus-commit |
| `scalar.db.consensus_commit.parallel_commit.enabled` | コミットフェーズを並列実行するか | `true` | `ConsensusCommitConfig.java:30,136` | consensus-commit |
| `scalar.db.consensus_commit.parallel_rollback.enabled` | ロールバックフェーズを並列実行するか | `parallel_commit.enabled` の値(既定 `true`) | `ConsensusCommitConfig.java:31,142-143`。`parallel_commit.enabled` を継承 | consensus-commit |
| `scalar.db.consensus_commit.async_commit.enabled` | コミットフェーズを非同期実行するか | `false` | `ConsensusCommitConfig.java:33,145` | consensus-commit |
| `scalar.db.consensus_commit.async_rollback.enabled` | ロールバックフェーズを非同期実行するか | `async_commit.enabled` の値(既定 `false`) | `ConsensusCommitConfig.java:34,148`。`async_commit.enabled` を継承 | consensus-commit |
| `scalar.db.consensus_commit.coordinator.write_omission_on_read_only.enabled` | 読み取り専用 tx での Coordinator 書込み省略最適化 | `true` | `ConsensusCommitConfig.java:36-37,150-151` | consensus-commit |
| `scalar.db.consensus_commit.one_phase_commit.enabled` | 1 フェーズコミット最適化 | `false` | `ConsensusCommitConfig.java:38,153` | consensus-commit |
| `scalar.db.consensus_commit.parallel_implicit_pre_read.enabled` | 暗黙のプリリードを並列実行するか | `true` | `ConsensusCommitConfig.java:39-40,155` | consensus-commit |
| `scalar.db.consensus_commit.include_metadata.enabled` | Get/Scan 結果に tx メタデータを含めるか | `false` | `ConsensusCommitConfig.java:41,157` | consensus-commit |
| `scalar.db.consensus_commit.index.eventually_consistent_read.enabled` | インデックスの結果整合読み取りを許可(before-image チェックをスキップ) | `false` | `ConsensusCommitConfig.java:42-43,158-159`。後方互換オプション、新規ワークロード非推奨 | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.enabled` | トランザクション状態のグループコミット有効化 | `false` | `ConsensusCommitConfig.java:46-47,161`。**2PC インターフェースとは併用不可** | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.slot_capacity` | グループ内スロットの最大数 | `20` | `ConsensusCommitConfig.java:48-49,63,162-166` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.group_size_fix_timeout_millis` | グループのスロットサイズ確定タイムアウト(ミリ秒) | `40` | `ConsensusCommitConfig.java:50-51,64,167-171` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.delayed_slot_move_timeout_millis` | 遅延スロットを隔離グループへ移動するタイムアウト(ミリ秒) | `1200` | `ConsensusCommitConfig.java:52-53,65,172-176` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.old_group_abort_timeout_millis` | 古い進行中グループをアボートするタイムアウト(ミリ秒) | `60000` | `ConsensusCommitConfig.java:54-55,66,177-181` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.timeout_check_interval_millis` | グループコミット関連タイムアウトのチェック間隔(ミリ秒) | `20` | `ConsensusCommitConfig.java:56-57,67,182-186` | consensus-commit |
| `scalar.db.consensus_commit.coordinator.group_commit.metrics_monitor_log_enabled` | グループコミットのメトリクスを定期ログ出力するか | `false` | `ConsensusCommitConfig.java:58-59,187-188` | consensus-commit |

**非推奨(4.0.0 で削除予定):**
- `scalar.db.isolation_level` — `isolation_level` の後方互換フォールバック(`ConsensusCommitConfig.java:103-108`)。
- `scalar.db.consensus_commit.serializable_strategy` — 非推奨。`SERIALIZABLE` では常に `EXTRA_READ` 戦略(`:123-128`)。

---

## 4. ストレージ別設定

各ストレージの接続情報は原則 §1 の共通プロパティ(`contact_points` / `contact_port` / `username` / `password`)で与え、
以下は各ストレージ固有の設定。`scalar.db.storage` の値が一致しないと各 Config が `IllegalArgumentException` を投げる。

### 4.1 Cassandra

典拠: `storage/cassandra/CassandraConfig.java`。固有プロパティは 1 件のみ(接続系は共通設定)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.system_namespace_name` | システム名前空間名。`admin.getNamespaceNames()` が Cluster 構成のシステム名前空間を返せるよう導入(Cluster 定義の設定を再利用) | `(空)` | `CassandraConfig.java:17,21`。`Optional<String>`。[公式Docs未掲載] | cassandra |

### 4.2 Cosmos DB

典拠: `storage/cosmos/CosmosConfig.java`(プレフィックス `scalar.db.cosmos.`, `:14`)。
エンドポイントは `contact_points` の先頭要素、キーは `password` から取得(`:34-35`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cosmos.table_metadata.database` | テーブルメタデータを格納する Cosmos DB データベース名 | `scalardb` | `CosmosConfig.java:15,36-37`。実効既定は `DEFAULT_SYSTEM_NAMESPACE_NAME="scalardb"`(`CosmosAdmin.java:85,91`)。[公式Docs未掲載] | cosmos |
| `scalar.db.cosmos.consistency_level` | Cosmos DB 操作の一貫性レベル | `STRONG` | `CosmosConfig.java:17,38`。enum: `STRONG` / `BOUNDED_STALENESS` の 2 値のみ(`CosmosUtils.java:33-37`)。公式Docs記載あり | cosmos |

### 4.3 DynamoDB

典拠: `storage/dynamo/DynamoConfig.java`(プレフィックス `scalar.db.dynamo.`, `:18`)。
リージョンは `contact_points` の先頭要素、access key = `username`、secret key = `password`(`:40-42`)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.dynamo.endpoint_override` | ScalarDB が通信する DynamoDB エンドポイント。主にローカルテスト用 | `(空)` | `DynamoConfig.java:19,50-57`。非推奨のハイフン形 `scalar.db.dynamo.endpoint-override` にフォールバック(4.0.0 で削除予定・警告)。公式Docs記載あり | dynamo |
| `scalar.db.dynamo.table_metadata.namespace` | テーブルメタデータを格納する名前空間名 | `scalardb` | `DynamoConfig.java:20,58-59`。実効既定は `"scalardb"`(`DynamoAdmin.java:216`)。`namespace.prefix` が前置される。[公式Docs未掲載] | dynamo |
| `scalar.db.dynamo.namespace.prefix` | ユーザー/メタデータ名前空間名のプレフィックス。単一 AWS リージョンで複数 ScalarDB 環境を使う場合に有用 | `(空)` = `""` | `DynamoConfig.java:21,60`。実効既定は空文字列(`DynamoAdmin.java` の `.orElse("")`)。公式Docs記載あり | dynamo |

### 4.4 JDBC

典拠: `storage/jdbc/JdbcConfig.java`(プレフィックス `scalar.db.jdbc.`, `:25`)。
接続 URL / ユーザ名 / パスワードは共通設定(`contact_points` / `username` / `password`)から取得(`:194-196`。`contact_points` 空で例外)。

#### コネクションプール(通常)

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.connection_pool.min_idle` | プールの最小アイドル接続数 | `20` | `JdbcConfig.java:26,77,198` | jdbc-pool |
| `scalar.db.jdbc.connection_pool.max_total` | アイドル+アクティブ接続の最大合計数 | `200` | `JdbcConfig.java:27,78,203` | jdbc-pool |
| `scalar.db.jdbc.connection_pool.connection_timeout_millis` | 接続取得を待つ最大時間(ミリ秒) | `30000`(実効) | `JdbcConfig.java:28-29,208`。JdbcConfig リテラル既定は `null`、実効値はプール(HikariCP)側 | jdbc-pool |
| `scalar.db.jdbc.connection_pool.idle_timeout_millis` | アイドル接続を許す最大時間(ミリ秒)。`0` で除去しない | `600000`(実効) | `JdbcConfig.java:30-31,210`。実効値はプール側 | jdbc-pool |
| `scalar.db.jdbc.connection_pool.max_lifetime_millis` | 接続の最大生存時間(ミリ秒)。`0` で無制限 | `1800000`(実効) | `JdbcConfig.java:32-33,212`。DB/インフラのタイムアウトより数秒短く推奨 | jdbc-pool |
| `scalar.db.jdbc.connection_pool.keepalive_time_millis` | keepalive 間隔(ミリ秒)。`max_lifetime_millis` 未満必須、`0` で無効 | `0`(実効) | `JdbcConfig.java:34-35,214` | jdbc-pool |
| `scalar.db.jdbc.isolation_level` | JDBC の分離レベル | `(空)`→基盤 DB 依存 | `JdbcConfig.java:37,217-222`。enum: `READ_UNCOMMITTED`(=[公式Docs未掲載]) / `READ_COMMITTED` / `REPEATABLE_READ` / `SERIALIZABLE` | jdbc |
| `scalar.db.jdbc.table_metadata.schema` | テーブルメタデータを格納するスキーマ名 | `(空)` | `JdbcConfig.java:39,224`。[公式Docs未掲載] | jdbc |

#### テーブルメタデータ用プール

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.table_metadata.connection_pool.min_idle` | 最小アイドル接続数 | `5` | `JdbcConfig.java:40-41,80,225` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.max_total` | 最大合計接続数 | `25` | `JdbcConfig.java:42-43,81,230` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.connection_timeout_millis` | 接続取得待ち最大時間(ミリ秒) | `30000`(実効) | `JdbcConfig.java:44-45,235` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.idle_timeout_millis` | アイドル許容時間(ミリ秒) | `600000`(実効) | `JdbcConfig.java:46-47,240` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.max_lifetime_millis` | 最大生存時間(ミリ秒) | `1800000`(実効) | `JdbcConfig.java:48-49,245` | jdbc-pool |
| `scalar.db.jdbc.table_metadata.connection_pool.keepalive_time_millis` | keepalive 間隔(ミリ秒) | `0`(実効) | `JdbcConfig.java:50-51,250` | jdbc-pool |

#### admin 用プール

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.admin.connection_pool.min_idle` | 最小アイドル接続数 | `5` | `JdbcConfig.java:53-54,83,256` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.max_total` | 最大合計接続数 | `25` | `JdbcConfig.java:55-56,84,261` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.connection_timeout_millis` | 接続取得待ち最大時間(ミリ秒) | `30000`(実効) | `JdbcConfig.java:57-58,266` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.idle_timeout_millis` | アイドル許容時間(ミリ秒) | `600000`(実効) | `JdbcConfig.java:59-60,269` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.max_lifetime_millis` | 最大生存時間(ミリ秒) | `1800000`(実効) | `JdbcConfig.java:61-62,271` | jdbc-admin-pool |
| `scalar.db.jdbc.admin.connection_pool.keepalive_time_millis` | keepalive 間隔(ミリ秒) | `0`(実効) | `JdbcConfig.java:63-64,273` | jdbc-admin-pool |

#### 方言固有(MySQL / Oracle / Db2 / Spanner)

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.jdbc.mysql.variable_key_column_size` | MySQL(MariaDB/TiDB 含む)で TEXT/BLOB をキーに使う際のカラムサイズ(最小 64) | `128` | `JdbcConfig.java:66-67,105,276`。64 未満で例外(`:294-298`) | jdbc |
| `scalar.db.jdbc.oracle.variable_key_column_size` | Oracle で TEXT/BLOB をキーに使う際のカラムサイズ(最小 64) | `128` | `JdbcConfig.java:68-69,105,282` | jdbc |
| `scalar.db.jdbc.db2.variable_key_column_size` | IBM Db2 で TEXT/BLOB をキーに使う際のカラムサイズ(最小 64) | `128` | `JdbcConfig.java:70,105,288` | jdbc |
| `scalar.db.jdbc.oracle.time_column.default_date_component` | Oracle で `TIME` 型格納時に使う固定日付(Oracle に日付なし時刻型が無いため) | `1970-01-01` | `JdbcConfig.java:71-72,113,300`。`ISO_LOCAL_DATE` でパース | jdbc |
| `scalar.db.jdbc.db2.time_column.default_date_component` | Db2 で `TIME` 型格納時に使う固定日付(Db2 は TIMESTAMP を使用) | `1970-01-01` | `JdbcConfig.java:73-74,119,309` | jdbc |
| `scalar.db.jdbc.spanner.time_column.default_date_component` | Spanner で `TIME` 型格納時に使う固定日付(TIMESTAMPTZ で格納) | `1970-01-01` | `JdbcConfig.java:75-76,124,317` | jdbc |

**削除済み(設定しても WARN を出し無視、4.0.0 まで警告継続。`JdbcConfig.java:180-189`):**
`scalar.db.jdbc.connection_pool.max_idle` / `scalar.db.jdbc.prepared_statements_pool.enabled` /
`scalar.db.jdbc.prepared_statements_pool.max_open` / `scalar.db.jdbc.table_metadata.connection_pool.max_idle` /
`scalar.db.jdbc.admin.connection_pool.max_idle`。
> import 機能に対応する `scalar.db.jdbc.*` プロパティは存在しない。

### 4.5 Multi-storage

典拠: `storage/multistorage/MultiStorageConfig.java`(プレフィックス `scalar.db.multi_storage.`, `:21`)。
`scalar.db.storage=multi-storage` の場合のみ。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.multi_storage.storages` | 配下に定義するストレージ名のカンマ区切りリスト | `(空)` | `MultiStorageConfig.java:22,48-51` | multi-storage |
| `scalar.db.multi_storage.storages.<name>.<property>` | 各ストレージ `<name>` のサブ設定。`<property>` は通常の `scalar.db.` 以下のキー(例 `storage`, `contact_points`, `username`, `password`, `jdbc.connection_pool.min_idle` 等)。接頭辞を除去し個別ストレージの `scalar.db.<property>` として解決 | `(空)` | `MultiStorageConfig.java:70-76`。ネストした `multi-storage` 定義は不可(`:78-82`) | multi-storage |
| `scalar.db.multi_storage.namespace_mapping` | 名前空間→ストレージ名のマッピング。`namespace:storage` をカンマ区切り | `(空)` | `MultiStorageConfig.java:24,115-129`。参照先ストレージ未定義で例外 | multi-storage |
| `scalar.db.multi_storage.default_storage` | マッピングに該当しない場合の既定ストレージ名 | `(空)` | `MultiStorageConfig.java:25,43-44`。`storages` に無い名前で例外 | multi-storage |
| `scalar.db.multi_storage.table_mapping` | テーブル→ストレージ名のマッピング。`table:storage` をカンマ区切り | `(空)` | `MultiStorageConfig.java:23,90-112`。**非推奨(3.6.0 以降、4.0.0 で削除予定・warn)**。`namespace_mapping` を推奨 | multi-storage |

### 4.6 Object Storage (S3 / Azure Blob / Google Cloud Storage)

3.18 で追加。`ObjectStorageConfig.java` はインターフェースのみで、実プロパティは各実装クラスが定義。
**いずれも公式 Docs には未掲載**(ソースが唯一の典拠)。プレフィックスとプロパティキーは `_`(アンダースコア)、
`scalar.db.storage` に指定する値は `-`(ハイフン)である点に注意。

#### S3(`storage=s3`)

典拠: `storage/objectstorage/s3/S3Config.java`(プレフィックス `scalar.db.s3.`, `:17-18`)。
`contact_points` = `S3_REGION/BUCKET_NAME` 形式(`:47-60`、形式不正で例外)、`username`=AWS access key、`password`=AWS secret key。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.s3.table_metadata.namespace` | テーブルメタデータ用名前空間 | `scalardb` | `S3Config.java:19,63-67` | s3 |
| `scalar.db.s3.multipart_upload_part_size_bytes` | マルチパートアップロードの各パートサイズ(バイト) | `(空)`(SDK 既定に委譲) | `S3Config.java:21-22,76-77`。`Optional<Long>` | s3 |
| `scalar.db.s3.multipart_upload_max_concurrency` | マルチパートアップロードの最大並行数 | `(空)` | `S3Config.java:23-24,78-79`。`Optional<Integer>` | s3 |
| `scalar.db.s3.multipart_upload_threshold_size_bytes` | マルチパートアップロードに切替える閾値サイズ(バイト) | `(空)` | `S3Config.java:25-26,80-81`。`Optional<Long>` | s3 |
| `scalar.db.s3.request_timeout_secs` | リクエストタイムアウト(秒) | `(空)` | `S3Config.java:27,82`。`Optional<Integer>` | s3 |

> `scalar.db.scan_fetch_size` を既定以外にすると「S3 に適用不可」の warn を出し無視(`S3Config.java:69-74`)。

#### Azure Blob Storage(`storage=blob-storage`)

典拠: `storage/objectstorage/blobstorage/BlobStorageConfig.java`(プレフィックス `scalar.db.blob_storage.`, `:15-17`)。
`contact_points` = `BLOB_URI/CONTAINER_NAME` 形式(`:46-57`、形式不正で例外)。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.blob_storage.table_metadata.namespace` | テーブルメタデータ用名前空間 | `scalardb` | `BlobStorageConfig.java:18,60-64` | blob-storage |
| `scalar.db.blob_storage.parallel_upload_block_size_bytes` | 並列アップロードのブロックサイズ(バイト) | `(空)` | `BlobStorageConfig.java:20-21,73-74`。`Optional<Long>` | blob-storage |
| `scalar.db.blob_storage.parallel_upload_max_concurrency` | 並列アップロードの最大並行数 | `(空)` | `BlobStorageConfig.java:22-23,75-76`。`Optional<Integer>` | blob-storage |
| `scalar.db.blob_storage.parallel_upload_threshold_size_bytes` | 並列アップロードに切替える閾値サイズ(バイト) | `(空)` | `BlobStorageConfig.java:24-25,77-78`。`Optional<Long>` | blob-storage |
| `scalar.db.blob_storage.request_timeout_secs` | リクエストタイムアウト(秒) | `(空)` | `BlobStorageConfig.java:26,79`。`Optional<Integer>` | blob-storage |

> `scan_fetch_size` を既定以外にすると warn を出し無視(`BlobStorageConfig.java:66-71`)。

#### Google Cloud Storage(`storage=cloud-storage`)

典拠: `storage/objectstorage/cloudstorage/CloudStorageConfig.java`(プレフィックス `scalar.db.cloud_storage.`, `:19-21`)。
`contact_points` の先頭要素 = バケット名、`username`=GCP project id、`password`=サービスアカウントキー JSON。

| 設定項目 (プロパティ名) | 説明 | 既定値 | 備考 | Group |
|---|---|---|---|---|
| `scalar.db.cloud_storage.table_metadata.namespace` | テーブルメタデータ用名前空間 | `scalardb` | `CloudStorageConfig.java:22,45-49` | cloud-storage |
| `scalar.db.cloud_storage.upload_chunk_size_bytes` | アップロードのチャンクサイズ(バイト) | `(空)` | `CloudStorageConfig.java:24,58`。`Optional<Integer>` | cloud-storage |

> `password`(SA キー JSON)未設定で認証時は `OBJECT_STORAGE_CLOUD_STORAGE_SERVICE_ACCOUNT_KEY_NOT_FOUND` 例外(`:85-90`)。
> `scan_fetch_size` を既定以外にすると warn を出し無視(`CloudStorageConfig.java:51-56`)。

---

## 付録: 実効値がリテラル既定値と異なる項目

Docs や単純なリテラル参照だけでは誤解しやすい項目(いずれも本表では実効値で記載済み):

| 種類 | 項目 | リテラル既定 | 実効既定 | 出典 |
|---|---|---|---|---|
| JDBC プール timeout 系 | `connection_timeout_millis` / `idle_timeout_millis` / `max_lifetime_millis` / `keepalive_time_millis`(通常/metadata/admin) | `null` | `30000` / `600000` / `1800000` / `0` | JdbcConfig は `null`、実効値は HikariCP 側 |
| メタデータ namespace | `cosmos.table_metadata.database` / `dynamo.table_metadata.namespace` / `{s3,blob_storage,cloud_storage}.table_metadata.namespace` | `null` | `scalardb` | `DatabaseConfig.DEFAULT_SYSTEM_NAMESPACE_NAME` |
| Cosmos 一貫性 | `cosmos.consistency_level` | `null` | `STRONG` | `CosmosUtils.java:33` |
| Dynamo prefix | `dynamo.namespace.prefix` | `null` | `""` | `DynamoAdmin` の `.orElse("")` |
| Consensus 継承系 | `parallel_validation` / `parallel_rollback` / `async_rollback` | — | それぞれ `parallel_commit` / `parallel_commit` / `async_commit` を継承 | `ConsensusCommitConfig.java:140-148` |
| Coordinator ns | `consensus_commit.coordinator.namespace` | `null` | 実効名 `coordinator` | 公式Docs |
