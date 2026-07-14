# ScalarDB 3.18.0 リファレンス — バックエンド機能マトリクス

スキーマ設計・バックエンド選択(`scalardb-generate-schema` / `scalardb-generate-config` Skill)の典拠。
ストレージ種別および JDBC エンジンごとの機能サポート差を 1 枚に集約する。

## 典拠

- ソース: `scalardb` タグ `v3.18.0`(commit `1953f0131`)。主に `api/StorageInfo.java`、各 `storage/*/*Admin.java`、`storage/jdbc/RdbEngine*.java`
- Docs: `docs-scalardb` ブランチ `3.18` / `docs/database-adapters.mdx`, `docs/requirements.mdx`, `docs/backup-restore.mdx`
- 既存調査資料(3.18.0 で再検証、下記「Docs/資料の要修正点」参照): `scalardb-backend-feature-comparison.md`, `native-db-features-vs-scalardb.md`, `plan-dynamodb-comparison-column.md`, `oracle_create_table_problem.md`, `mysql-jdbc-url-trailing-slash-report.md`
- 個別の型マッピングは `20-data-model-and-types.md`、操作制限は `30-crud-api-and-limits.md`、SQL は `40-sql-support-and-limits.md` を参照

> **Object Storage(S3/Blob/Cloud)は 3.18 で Private Preview**(`requirements.mdx:271-273`)。本番前提の選定では留意。

---

## 1. ストレージ種別 × 機能マトリクス

| 機能 | Cassandra | Cosmos DB | DynamoDB | JDBC | Object Storage | multi-storage |
|---|---|---|---|---|---|---|
| **mutation 原子性単位** | PARTITION | PARTITION | **STORAGE** | STORAGE | PARTITION | 委譲 |
| **1 バッチ最大 mutation 数** | 無制限 | 無制限 | **100** | 無制限 | 無制限 | 委譲 |
| secondary index | 可 | 可 | 可(GSI) | 可 | **不可** | 委譲 |
| cross-partition scan filtering | 可 | 可 | 可 | 可 | 可 | 委譲 |
| cross-partition scan ordering | ❌ | ❌ | ❌ | **✅(JDBCのみ)** | ❌ | 委譲 |
| TIMESTAMP(TZなし)型 | **❌**(TIMESTAMPTZ 代替) | 可 | 可 | 可 | 可 | 委譲 |
| import(既存テーブル取込) | ❌ | ❌ | ❌ | 可(SQLite除く) | ❌ | 委譲 |
| DROP COLUMN | ✅ | ❌ | ❌ | ✅ | ❌ | 委譲 |
| RENAME COLUMN | PK列のみ | ❌ | ❌ | ✅(DB差あり) | ❌ | 委譲 |
| ALTER COLUMN TYPE | ❌ | ❌ | ❌ | ✅(DB差あり) | ❌ | 委譲 |
| RENAME TABLE | ❌ | ❌ | ❌ | ✅(Spanner除く) | ❌ | 委譲 |
| Consensus Commit(1PC/2PC) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| encryption(Cluster機能) | storage 非依存(差なし) | | | | | |

典拠(原子性単位/最大数は `StorageInfoImpl` の引数): Cassandra `CassandraAdmin.java:48-53`、Cosmos `CosmosAdmin.java:70-75`、Dynamo `DynamoAdmin.java:161-166`、JDBC `JdbcAdmin.java:1182-1186`、Object Storage `ObjectStorageAdmin.java:37-42`、multi-storage `MultiStorageAdmin.java:271-278`、enum `StorageInfo.java:38-68`。
cross-partition ordering は非 JDBC で storage 構築時に例外(`Cassandra.java:52-54`, `Cosmos.java:54-56`, `Dynamo.java:58-60`, `ObjectStorage.java:38-40`)。TIMESTAMP は Cassandra が例外(`CassandraAdmin.java:87-88`)。import 不可は各 Admin(SQLite のみ JDBC 内で例外, `RdbEngineSqlite.java:348-350`)。

- **原子性単位の意味**: `STORAGE`=どのパーティションを跨いでも 1 バッチをまとめて原子的、`PARTITION`=単一パーティション内のみ原子的。Consensus Commit は上位でこの差を吸収するが、Cassandra/Cosmos/ObjectStorage では単一 storage 呼び出しの原子境界がパーティション単位。
- **encryption** は ScalarDB Cluster の Enterprise 機能(カラム暗号化)で **storage backend 非依存**(→ `11-configuration-cluster.md` §5)。storage 種別による差はない。

### キーに使える型の制限(BLOB 中心、`20 §5` と整合)

| | partition key | clustering key | secondary index |
|---|---|---|---|
| Cassandra | 制限なし(BLOB可) | BLOB可 | — |
| Cosmos | 可 | **BLOB不可** | 可 |
| DynamoDB | **BLOB は複合PKの最終列のみ可** | **BLOB不可** | **BOOLEAN不可** |
| JDBC | Oracle/Db2 は **BLOB不可** / Yugabyte は **FLOAT・DOUBLE不可** / Spanner は **FLOAT不可** | 同左 | 同左 |
| Object Storage | 主キー文字列に `/` `!` 不可、BLOB 1.5GiB 上限、BIGINT ±2^53 | — | index 自体なし |

### namespace の実現方法

| storage | 実現方法 |
|---|---|
| Cassandra | ネイティブ keyspace(1:1) |
| Cosmos | namespace=database, table=container |
| DynamoDB | ネイティブ概念なし → テーブル名を `namespace.table` に連結(prefix 可) |
| JDBC | MySQL/MariaDB/TiDB=database、PostgreSQL系/SQLServer/Db2/Spanner=schema、Oracle=user兼schema、**SQLite=テーブル名 prefix `ns$table`** |
| Object Storage | ネイティブなし → 単一バケット内の階層キー `<ns>/<table>/<pk>`(論理分離のみ) |

---

## 2. JDBC エンジン × capability マトリクス

`RdbEngineStrategy` の capability メソッド値(全て `v3.18.0` ソース裏取り)。略号: My=MySQL, Ma=MariaDB, Ti=TiDB, Pg=PostgreSQL, Yb=YugabyteDB, Or=Oracle, Ss=SQL Server, Li=SQLite, Db2, Sp=Spanner。
継承: **MariaDB/TiDB は MySQL 継承、YugabyteDB/Spanner は PostgreSQL 継承**。

| capability | My | Ma | Ti | Pg | Yb | Or | Ss | Li | Db2 | Sp |
|---|---|---|---|---|---|---|---|---|---|---|
| 最上位 isolation | SER | SER | **REP.READ** | SER | SER | SER | SER | SER | SER | SER |
| virtual table 一貫読取の最小 isolation | REP.READ | 〃 | 〃 | REP.READ | 〃 | **SERIALIZABLE** | REP.READ | **READ COMM.** | REP.READ | REP.READ |
| ALTER COLUMN TYPE | 無制限 | 〃 | **BLOB→TEXT不可** | 無制限 | 〃 | **INT→BIGINTのみ** | 無制限 | **全て不可** | **BLOB→TEXT不可** | **BLOB→TEXTのみ** |
| RENAME TABLE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** |
| RENAME COLUMN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **PK/CK/index不可** | **❌** |
| import | ✅ | ✅ | ✅ | ✅ | ✅※ | ✅ | ✅ | **❌** | ✅ | ✅ |
| BLOB を key/index に | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | **❌** | ✅ |
| cross-partition ordering の BLOB 列 | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | **❌** | ✅ |
| Get/Scan 条件に BLOB 列 | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** | ✅ | ✅ | ✅ | ✅ |
| DROP COLUMN 前に明示 index drop | 否 | 否 | 否 | 否 | 否 | 否 | **要** | **要** | 否 | **要** |

典拠: 最小/最上位 isolation `RdbEngineOracle.java:563-566`・`RdbEngineTidb.java:28-31` ほか、判定利用 `JdbcAdmin.java:1170-1191`。ALTER type `RdbEngineOracle.java:518-524`/`RdbEngineSqlite.java:397-400`/`RdbEngineTidb.java:19-25`/`RdbEngineDb2.java:566-572`/`RdbEngineSpanner.java:143-149`。rename `RdbEngineSpanner.java:137-158`/`RdbEngineDb2.java:556-563`。import `RdbEngineSqlite.java:348-351`。BLOB `RdbEngineOracle.java:292-302,470-509`/`RdbEngineDb2.java:403-428,586-598`。explicit index drop `RdbEngineSqlServer.java:157-159`/`RdbEngineSqlite.java:309-311`/`RdbEngineSpanner.java:288-290`。

※ **要注意(Docs↔コード矛盾)**: Docs `database-adapters.mdx:109` は「YugabyteDB は import 非対応」と記載するが、3.18.0 core では `RdbEngineYugabyte` が `throwIfImportNotSupported` を override せず(PostgreSQL 継承の no-op)、core ではブロックされない。上位(schema-loader/data-loader)での制御か Docs 乖離かは要追加調査。
> FLOAT/DOUBLE を key に使えない制限(Yugabyte/Spanner)は capability メソッドではなく別経路(Docs `:108,123`)。

### conflict(リトライ対象)エラーコード

My/Ma/Ti: 1213/1205、Pg/Yb: 40001/40P01、Or: 8177/60/8176、Ss: 1205、Li: 5/6、Db2: -911、Sp: ABORTED(各 `RdbEngine*.isConflict`)。

### 対応バージョン(`requirements.mdx`)

Oracle 23ai/21c/19c、Db2 12.1/11.5(LUW のみ)、MySQL 8.4/8.0、PostgreSQL 17–13、Aurora MySQL 3/2、Aurora PostgreSQL 17–13、MariaDB 11.4/10.11、TiDB 8.5/7.5/6.5(3.17〜)、AlloyDB 16/15(3.17〜)、SQL Server 2022/2019/2017、SQLite 3、YugabyteDB 2(3.13〜)、**Spanner(PostgreSQL dialect)は 3.18 で新規対応**。

---

## 3. バックアップ / リストア(`backup-restore.mdx`)

判定: 「単一 DB かつトランザクション対応 DB(=JDBC)」→ **pause 不要**、それ以外(NoSQL/複数 DB)→ **明示的 pause 必須**(Scalar Admin / Cluster の drain)。

| storage | 方式 | pause | 注意点 |
|---|---|---|---|
| JDBC(RDS/Aurora) | 自動バックアップ/PITR | 不要 | |
| JDBC(MySQL/PG/Db2/SQLite) | `mysqldump --single-transaction` / `pg_dump` / `backup` / `.backup` | 不要 | |
| DynamoDB | PITR(Schema Loader が既定有効化)→別テーブル復元 | **必須** | 1 テーブルずつ復元(復元→元削除→rename)。PITR/auto-scaling 設定はリセットされ再設定要 |
| Cosmos DB | 継続バックアップ(PITR) | **必須** | 復元後 **整合性レベルを STRONG に再設定** + **stored procedure を `--repair-all` で再インストール** |
| Cassandra | snapshot | **必須**(整合復元時) | RF≥3 なら単一ノード障害は repair で回復。cluster-wide snapshot は pause/停止コピー |
| Object Storage | AWS/Azure Backup / Storage Transfer | **必須** | 別バケット復元時は `scalar.db.contact_points` を更新 |

PITR 共通: NTP でクロックドリフト最小化、pause は十分な時間(例 5 秒)取り mid-time を復元点に。

---

## 4. 各 storage の代表的な落とし穴

- **Cassandra**: TIMESTAMP 不可(TIMESTAMPTZ 代替)。BLOB は 1 mutation 既定 16MB 上限。**`PutIf`+`IS NULL` を非存在レコードに行っても `NoMutationException` が出ず暗黙成功**(他アダプタと挙動が違う、Docs `:268`)。LWT 使用の性能影響。非同期レプリカから読まない。
- **Cosmos DB**: レコード 2MB 上限、BIGINT ±2^53、primary key に `/ \ ? #` 不可・partition key に `:` 不可、BLOB 条件は 4 演算子のみ、整合性 Strong/Bounded・single-region write 必須。復元後 STRONG 再設定 + stored procedure 再インストール。
- **DynamoDB**: **原子性単位 STORAGE / 1 バッチ 100 mutation**(既存資料の「PARTITION/無制限」は誤り)。item 400KB、BLOB は複合 PK 最終列のみ、BOOLEAN index 不可・条件 4 演算子のみ、single primary region、schema evolution DDL 全不可。
- **JDBC**: DB 差が大きい(BLOB key: Oracle/Db2 不可、FLOAT/DOUBLE key: Yugabyte 不可、FLOAT key: Spanner 不可)。ALTER type は DB 依存。**SQLite は import/virtual table 不可・並行アクセス非対応(開発用途)**、**TiDB は SERIALIZABLE 非対応**、**Spanner は rename 不可**。
- **Object Storage(Private Preview)**: **secondary index 一切不可**。1 partition=1 object で clustering key 多用は object 肥大化。primary key に `/` `!` 不可、BLOB 1.5GiB、BIGINT ±2^53。namespace は論理分離のみ。import/drop/rename/alter type/rename table 不可(add column のみ)。
- **multi-storage**: 各 backend の制約をそのまま継承(原子性単位も委譲)。namespace 単位ルーティング。ネスト不可。

### Oracle の CREATE TABLE 問題(`oracle_create_table_problem.md` 再検証、→ `90` で詳細)

ユーザーテーブルには ORA-08177 対策(`ROWDEPENDENCIES` `RdbEngineOracle.java:76` + `INITRANS 3 MAXTRANS 255` `:90`)が付くが、**カタログテーブル(`metadata`/`virtual_tables`)には対策が欠落**(`TableMetadataService`/`VirtualTableMetadataService` の素の DDL)。Oracle は virtual table 一貫読取に **SERIALIZABLE 必須**(`:563-566`)。プール全体 SERIALIZABLE 化時に Schema Loader のカタログ INSERT が ORA-08177 で失敗しうる。3.18.0 でも健在。

---

## 5. Docs / 既存資料の要修正点(検出)

1. **`scalardb-backend-feature-comparison.md` の DynamoDB「PARTITION / 無制限」は誤り** → 実装は **STORAGE / 100**(`DynamoAdmin.java:161-166`)。最重要。
2. 同資料の JDBC「Database-dependent」→ 実装は一律 **STORAGE**(`JdbcAdmin.java:1182-1186`)。
3. 同資料は **Object Storage(第6の storage)が未掲載**(作成時期が古い)。追記が必要。
4. **Docs `database-adapters.mdx:109` の YugabyteDB import 非対応がコードと不一致**(core ではブロックされない)。要追加調査。
5. `oracle_create_table_problem.md` の Oracle 関連行番号を 3.18.0 に更新(最小 isolation 549-552→**563-566**、`getStorageInfo` 1010-1013→**1170-1191**、`isConflict` 236-240→**239-243** で ORA-08176 追加)。
6. `mysql-jdbc-url-trailing-slash-report.md` の結論は 3.18.0 でも有効(ドライバは MariaDB Connector/J、`permitMysqlScheme` 自動付与、末尾 `/` はコード上無関係で Docs 表記統一問題)。
