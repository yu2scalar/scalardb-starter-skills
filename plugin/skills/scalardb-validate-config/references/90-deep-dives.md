# ScalarDB 3.18.0 リファレンス — 深掘り(Docs↔実装の乖離・落とし穴・設計理解)

各軸ファイル(10〜70)で蓄積した、**Docs だけでは判らない挙動・Docs と実装の乖離・要追加検証項目・設計上の "なぜ"** を集約する。
各項に「Skill 設計への含意」を付す。全て `v3.18.0`(Core/Cluster)/ `v3.18.0`(SQL)/ `3.18` Docs ブランチ典拠。

## 目次
- A. Docs / 既存資料と実装の乖離(ドキュメント修正提案の素)
- B. Docs だけでは判らない挙動(ソース必読の要点)
- C. 未確定・要追加検証(次セッション持ち越し)
- D. 設計深掘り(なぜそうなっているか)

---

## A. Docs / 既存資料と実装の乖離

いずれも 3.18.0 ソースで確認済み。**公式 Docs / 社内既存資料への修正提案**として使える。

| # | 乖離 | 実装(3.18.0) | 典拠 | Skill 設計への含意 |
|---|---|---|---|---|
| A1 | 既存資料 `scalardb-backend-feature-comparison.md`: DynamoDB の mutation 原子性を「PARTITION / 無制限」 | **STORAGE / 100** | `DynamoAdmin.java:161-166` | validate/analyze で「Dynamo 100 件上限」を正として警告。資料修正を提案 |
| A2 | 同資料: JDBC を「Database-dependent」 | 一律 **STORAGE** | `JdbcAdmin.java:1182-1186` | 同上 |
| A3 | Docs `database-adapters.mdx:109`: 「YugabyteDB は import 非対応」 | core では **ブロックされない**(`RdbEngineYugabyte` は override なし) | `RdbEngineSqlite.java:348-351` のみが例外 | **C4 として要追加調査**。断定せず「上位レイヤ制御の可能性」と扱う |
| A4 | Docs: `scalar.db.cluster.membership.type` の既定 `KUBERNETES` | 既定なし(未設定は `valueOf("")` で例外=**実質必須**) | `MembershipConfig.java:18,48` | generate-config で membership.type を必須質問にする |
| A5 | Docs: replication のスレッド数キー `...transaction_handler.threads` | 実ソースは `...transaction_applier.threads` | `LogApplierConfig.java:31-32` | validate は実ソース名を正とする。Docs 修正提案 |
| A6 | JavaDoc: encryption `self.key_type`=`AES256_GCM` / `vault.key_type`=`aes256-gcm96` | 実効は **`AES128_GCM` / `aes128-gcm96`** | `SelfConfig.java:16,41`, `VaultConfig.java:33,79` | 既定値の提示は実効値で |
| A7 | `scalar.db.cluster.hop_limit`(ClusterConfig)は定数のみ | コンストラクタ未消費(getter なし)。実際に効くのは `cluster.internal.hop_limit` とクライアント経路 | `ClusterConfig.java:38,90` | validate で「ClusterConfig の hop_limit 設定は無効」と指摘可 |
| A8 | Docs: `node.grpc.max_connection_age_millis`/`_grace` の既定 `Integer.MAX_VALUE` | コードはリテラル `null`(→ gRPC 既定=無限) | `ClusterNodeConfig.java:38-43` | 挙動は同じ(無限)だが、生成時は空でよいと案内 |
| A9 | Docs(Cosmos): 「BLOB は clustering key 不可」のみ言及 | **partition key の BLOB はコード上許可**(チェックなし) | `CosmosAdmin.java:110-117` | schema 生成で Cosmos partition key BLOB を機械的に禁止しない |
| A10 | 既存資料 `scalardb_datetime_type_mapping.md`: `TimestampTZColumn.of(...)` | 実装は **`ofStrict(...)`**(精度超過で例外)。行番号も 490-498 にドリフト | `RdbEngineMysql.java:496` | 型精度の説明は `ofStrict`(丸めない)前提で |

---

## B. Docs だけでは判らない挙動(ソース必読の要点)

Docs を読むだけでは正確な挙動が判らず、運用でハマる項目。詳細は各軸ファイル、ここは索引+含意。

### B1. pushdown vs ScalarDB 側処理 と OOM(→ `40 §5`)
GROUP BY / 集約 / HAVING / JOIN / ORDER BY は **すべてクライアント側実行**。LIMIT も JOIN/集約時はクライアント側で、**backend は全件取得**。さらに Consensus Commit は storage への LIMIT を 0(無効)にして全件取得し、非キー条件は before-image OR に展開して広めに返す。
- 典拠: `DmlStatementExecutor.java:74-111`, `ConsensusCommitUtils.java:420-424`。
- **Skill 含意**: 「WHERE を付けたのに遅い/OOM」の一次容疑。generate-springboot-starter は大テーブルでの集約/JOIN/ORDER BY に警告コメントを出す。analyze-errorlog は OOM を AP-10/11 に紐づける。

### B2. Isolation と Consistency は別物(→ `30 §5.2, §3.4`)
Isolation=トランザクション(Consensus Commit)、Consistency=単一操作のレプリカ整合(非トランザクションのみ)。トランザクションでは Consistency は無視され常に LINEARIZABLE。
- **Skill 含意**: validate は「トランザクションアプリで Consistency を設定」を無効指定として指摘。Q&A は Isolation のみ聞く。

### B3. Consistency のバックエンド別マッピング(→ `30 §3.4`)
`SEQUENTIAL`→Cassandra QUORUM/Dynamo 強整合、`EVENTUAL`→Cassandra ONE/Dynamo 結果整合、`LINEARIZABLE`→Cassandra SERIAL/Paxos。Cosmos は operation 単位で無効(クライアント設定依存)、JDBC は無効。
- **Skill 含意**: 非トランザクション利用時のみ意味を持つ、と明示。

### B4. 日付時刻の物理格納(→ `20 §4`)
Oracle/Db2/Spanner の TIME は固定日付付き、SQL Server/Db2 は文字列渡し、SQLite は数値エンコード、MySQL は `DATETIME(3)` に UTC 壁時計値。生 SQL/別 TZ セッションからは値がずれて見える。
- **Skill 含意**: 「ScalarDB 経由の round-trip は一貫、外部直接アクセスは注意」と案内。TIMESTAMPTZ を勧める。

### B5. JDBC プール timeout の実効値(→ `10 §4.4`)
`connection_timeout`/`idle_timeout`/`max_lifetime`/`keepalive` は JdbcConfig ではリテラル `null`、実効値は HikariCP 側(30000/600000/1800000/0)。
- **Skill 含意**: 生成時に空でよい。実効値の説明はプール側。

### B6. secondary index の等値専用・単一列・before-image 干渉(→ `30 §4.3`, `20 §6.4`)
index key は単一列等値のみ。範囲は clustering key。トランザクション経由では非主キー条件が `(col OR before_col)` に化ける。3.18 は `before_*` 列に自動 index。
- **Skill 含意**: generate-schema は「range なら clustering key、等値ピンポイントのみ index」に誘導(A4/AP-1〜3)。

---

## C. 未確定・要追加検証(次セッション持ち越し)

3.18.0 ソースで**断定できなかった**項目。Skill では「未確定」として扱い、必要なら実測する。

| # | 項目 | 現状 | 残作業 |
|---|---|---|---|
| C1 | before_* index の**実際の効き**(DB プランナが index を使うか) | scalardb-sql の planner は「等値のみ key 化」を確認(`PredicatesMap.java:140-145`)。3.18 で `before_*` 自動 index 化も確認。だが**実 DB での EXPLAIN 実測は未実施** | PostgreSQL 等で `EXPLAIN` 実測して index 使用を確認 |
| C2 | NoSQL の**列順不定**(非キー列) | コードは `HashMap` 詰め替え(3.17 系調査)。3.18 でも継続かの厳密確認と実測は未 | Dynamo/Cosmos で `SELECT *` 列順を実測 |
| C3 | Oracle CREATE TABLE 問題(カタログテーブルの `ROWDEPENDENCIES` 欠落) | 3.18.0 ソースで**健在を確認**(`TableMetadataService`/`VirtualTableMetadataService` の素 DDL)。ただし ORA-08177 の実再現 E2E は未実施 | Oracle で SERIALIZABLE プール + Schema Loader カタログ大量 INSERT を再現 |
| C4 | YugabyteDB import(A3) | core にブロックロジック無し。Docs は非対応と記載 | schema-loader/data-loader の上位レイヤを確認 or Docs 乖離と確定 |
| C5 | piggyback-begin(cluster)`DB-CLUSTER-10059/10061` | client 設定は取得済(`11 §2`)。エラーコード自体は cluster ソースで**未裏取り** | `scalardb-cluster` の worktree で resume/join/getId 制限を確認(config は取得済、error クラスは未読) |

> **Skill 含意**: C 項は Skill の助言で「(要検証)」と明示するか、断定を避ける。特に C1/C2 は性能/正当性に関わるため、starter の生成コードでは安全側(列名明示 INSERT、range はキー設計)に倒す。

---

## D. 設計深掘り(なぜそうなっているか)

「なぜこの制限があるか」を理解すると、Skill の助言と設計判断がぶれない。

### D1. secondary index が等値専用な物理的理由(→ `30 §4.3`)
DynamoDB GSI は `KeyType.HASH` のみで作成(RANGE キーなし, `DynamoAdmin.java:365-370`)、Cassandra は native 2i(SASI ではない, `CassandraAdmin.java:174-177`)で、いずれもソート構造でなく等値専用。JDBC の B-tree は範囲可能だが、ScalarDB は**全バックエンド共通の最小公倍数**に合わせ API を等値専用に統一。
- **含意**: 「range が要るなら clustering key」という指針の根拠。多バックエンド抽象の代償として説明できる。

### D2. トランザクションメタデータ列がユーザーテーブルに同居(→ `20 §6.1`)
`tx_id/tx_state/tx_version/tx_prepared_at/tx_committed_at` と非主キー列ごとの `before_*` 列が物理的に追加される。ScalarDB 経由でないと正しく解釈できない。
- **含意**: 「生 SQL で直接いじらない」「メタデータ列を projection/condition に指定しない(`DB-CORE` エラー)」を案内。

### D3. NoSQL のキー連結エンコードと TEXT 文字制約(→ `20 §6.2`)
DynamoDB は複合キーをバイナリ連結、Cosmos はコロン連結。ゆえに TEXT に ` `(Dynamo 終端)や `:`(Cosmos 区切り)を含められない。DB ネイティブの複合キー機能は使わない。
- **含意**: schema/データ投入 Skill が「キー列 TEXT の禁止文字」を検証(`DB-CORE-10076` 系)。

### D4. Consensus Commit は下層 DB に依存せず ACID を実現(→ `30 §1`)
下層は `DistributedStorage`(単一パーティション原子性 + linearizable conditional write)しか要求せず、Coordinator テーブルと OCC で ACID を上に構築。だから多様な DB で同じ保証が出せる。
- **含意**: 「なぜ NoSQL でもトランザクションが効くのか」の説明。原子性単位の差(`50 §1`)もこの層で吸収される。

### D5. Oracle SERIALIZABLE ゲートの catch-22(→ `50 §4`, C3)
Oracle は virtual table 一貫読取に SERIALIZABLE 必須(`RdbEngineOracle.java:563-566`)。一方 SERIALIZABLE 化するとカタログテーブル(対策欠落)の大量 INSERT が ORA-08177 で失敗しうる。
- **含意**: Oracle + transaction-metadata-decoupling は注意領域。generate-config で Oracle 選択時に警告。

---

## メタ: このファイルの育て方

- 各軸ファイル(10〜70)で新たに Docs↔実装乖離やハマりどころを見つけたら、ここへ 1 行追記(表 A/B、または C/D)。
- C 項(未確定)は実測できたら B/D へ昇格し、C から消す。
- リリース更新時(`scalardb-reference/<新版>/`)は、A の乖離が解消されたか、C の未確定が確定したかを再チェックする。
