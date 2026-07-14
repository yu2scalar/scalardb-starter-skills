# ScalarDB 3.18.0 リファレンス — データモデルとデータ型

スキーマ設計(`scalardb-generate-schema` Skill)の典拠。データモデルの構造、ScalarDB のデータ型、
各バックエンドへの型マッピング、キー/インデックスに使える型の制限をまとめる。

## 典拠

- ソース: `scalardb` タグ `v3.18.0`(commit `1953f0131`)
- Docs: `docs-scalardb` ブランチ `3.18` / `docs/data-modeling.mdx`, `docs/database-adapters.mdx`, `docs/api-guide.mdx`
- 主な典拠クラス: `io/DataType.java` ほか `io/*Column.java`、`api/TableMetadata.java`、
  各ストレージの `*Admin.java`、`storage/jdbc/RdbEngine*.java` / `RdbEngineTimeType*.java`
- 既存調査資料(3.18.0 で再検証済み): `scalardb_datetime_type_mapping.md`, `scalardb-column-ordering-analysis.md`,
  `scalardb-metadata-encoding-and-architecture-analysis.md`, `oracle_create_table_problem.md`

---

## 1. データモデルの構造

ScalarDB は Bigtable 由来の拡張キーバリューモデル(`data-modeling.mdx:17`)。
階層は **namespace ⊃ table ⊃ partition ⊃ record ⊃ column**。record は primary key
(partition key + 任意の clustering key)で一意に決まる(`data-modeling.mdx:51-71`)。

テーブル構造は `api/TableMetadata.java` が保持する:

| 概念 | フィールド | 型 | 反復順序 | 典拠 |
|---|---|---|---|---|
| column 一覧 | `columnNames` | `ImmutableLinkedHashSet<String>` | ✅ | `TableMetadata.java:23,38` |
| column → 型 | `columnDataTypes` | `ImmutableMap<String, DataType>` | — | `TableMetadata.java:24,39` |
| partition key | `partitionKeyNames` | `ImmutableLinkedHashSet<String>` | ✅ | `TableMetadata.java:25,41` |
| clustering key | `clusteringKeyNames` | `ImmutableLinkedHashSet<String>` | ✅ | `TableMetadata.java:26,43` |
| clustering order | `clusteringOrders` | `ImmutableMap<String, Order>` | — | `TableMetadata.java:27,44` |
| secondary index | `secondaryIndexNames` | `ImmutableSet<String>` | ❌ | `TableMetadata.java:28,45,201` |
| 暗号化列 | `encryptedColumnNames` | `ImmutableSet<String>` | ❌ | `TableMetadata.java:29,46,202` |

**反復順序の凡例:**

| 記号 | 意味 | 実データ構造(builder → 保持) |
|---|---|---|
| ✅ | 定義順を保持(反復すると定義順) | `LinkedHashSet` → `ImmutableLinkedHashSet` |
| ❌ | 順序を保持しない(反復順は不定) | `HashSet` → `ImmutableSet`(builder `:201-202`) |
| — | 非該当(`Map<列名, 値>` でキー参照して使うため反復順序に依存しない) | `HashMap`/`LinkedHashMap` → `ImmutableMap` |

> `secondaryIndexNames` と `encryptedColumnNames` はどちらも `HashSet` 由来の `ImmutableSet` で **順序非保持(❌)**。
> Map の 2 つ(`columnDataTypes` / `clusteringOrders`)はキー参照用のため反復順序は問わない(—)。
> 実務上重要なのは、**PK/CK/column 名(✅)は定義順が保たれる**が、**secondary index は集合で順序を持たない(❌)**という点(§6.3 で列順序の実挙動を扱う)。

- **clustering order**: 列ごとに `Order`(`ASC`/`DESC`, `api/Scan.java:496-498`)。`addClusteringKey(name)` は既定 ASC(`:332-335`)、`addClusteringKey(name, order)` で明示(`:344-348`)。
- **secondary index は単一列のみ**(`data-modeling.mdx:49`)。順序は保持されない。
- `build()` の整合性検証(`TableMetadata.java:435-477`): column が空 / partition key が空だと例外。PK・CK・index の各名が column 定義に無いと例外。**型に関する検証はここでは一切行わない**(型制限は各ストレージ Admin 側、§5)。

---

## 2. ScalarDB データ型一覧(全 11 種)

典拠: `io/DataType.java:3-15` および各 `io/*Column.java`。

| DataType | Java 表現 | 値域 / 精度 | 典拠 |
|---|---|---|---|
| `BOOLEAN` | `Boolean` | true / false | `DataType.java:4`, `BooleanColumn.java:12` |
| `INT` | `Integer` | 32bit 符号付き | `DataType.java:5`, `IntColumn.java:12` |
| `BIGINT` | `Long` | 64bit 符号付き | `DataType.java:6`, `BigIntColumn.java:12` |
| `FLOAT` | `Float` | 32bit IEEE754(最小正常値 `Float.MIN_NORMAL`, `database-adapters.mdx:97`) | `DataType.java:7`, `FloatColumn.java:12` |
| `DOUBLE` | `Double` | 64bit IEEE754(最小正常値 `Double.MIN_NORMAL`) | `DataType.java:8`, `DoubleColumn.java:12` |
| `TEXT` | `String` | UTF 系文字列 | `DataType.java:9`, `TextColumn.java:13` |
| `BLOB` | `ByteBuffer` / `byte[]` | バイト列 | `DataType.java:10`, `BlobColumn.java:16` |
| `DATE` | `LocalDate` | `1000-01-01` 〜 `9999-12-31` | `DataType.java:11`, `DateColumn.java:18-22` |
| `TIME` | `LocalTime` | `00:00:00` 〜 `23:59:59.999999000`(マイクロ秒精度) | `DataType.java:12`, `TimeColumn.java:19-24` |
| `TIMESTAMP` | `LocalDateTime` | `1000-01-01 00:00` 〜 `9999-12-31 23:59:59.999`(ミリ秒精度、TZ なし) | `DataType.java:13`, `TimestampColumn.java:20-25` |
| `TIMESTAMPTZ` | `Instant` | 上記と同範囲を UTC 基準の `Instant` で(ミリ秒精度) | `DataType.java:14`, `TimestampTZColumn.java:22-28` |

> `TIME`/`TIMESTAMP`/`TIMESTAMPTZ` には精度を丸める `of()` と、精度超過で例外の `ofStrict()` の 2 系統がある(例 `TimeColumn.java:122,135`)。
> 日付時刻型は 3.16 で追加された比較的新しい機能。精度・値域はバックエンドの物理格納方法(§4)に影響する。

---

## 3. データ型 → バックエンド実型マッピング

### 3.1 NoSQL(Cassandra / Cosmos DB / DynamoDB)

**設計差**: Cassandra は DDL で実カラム型を作る(native 型に直接マップ)。Cosmos/Dynamo は実 DB に
スキーマ型を持たせず、値を汎用表現(Cosmos=JSON document、Dynamo=item attribute)で保存し、
ScalarDB 型は別メタデータに管理する(§6)。下表の Cosmos/Dynamo 列は「値の物理表現」であり DDL 列型ではない。

| ScalarDB | Cassandra(CQL) | Cosmos(JSON 値表現) | DynamoDB(attribute) |
|---|---|---|---|
| BOOLEAN | `boolean` | JSON boolean | `BOOL` |
| INT | `int` | JSON number | `N` |
| BIGINT | `bigint` | JSON number | `N` |
| FLOAT | `float` | JSON number | `N` |
| DOUBLE | `double` | JSON number | `N` |
| TEXT | `text` | JSON string | `S` |
| BLOB | `blob` | JSON string(**Base64**) | `B` |
| DATE | `date` | JSON number(int epoch day) | `N`(int epoch day) |
| TIME | `time` | JSON number(long nano-of-day) | `N`(long nano-of-day) |
| TIMESTAMP | **非対応(例外)** | JSON number(long packed, UTC) | `N`(long packed) |
| TIMESTAMPTZ | `timestamp` | JSON number(long packed, UTC) | `N`(long packed, UTC) |

典拠: `CassandraAdmin.java:574-593`、`cosmos/MapVisitor.java:36-96`、`dynamo/ValueBinder.java:50-175`、
時刻エンコードは共通ユーティリティ `util/TimeRelatedColumnEncodingUtils.java:20-111`。

- **Cassandra は `TIMESTAMP`(TZ なし)非対応**: `createTable`/`addNewColumnToTable` が検出すると `UnsupportedOperationException`(`CassandraAdmin.java:85-90,315-318`)。逆に Cassandra native `timestamp` は ScalarDB `TIMESTAMPTZ` にマップ(`:559-560`)。
- Cosmos/Dynamo は全 11 型を受容。packed 値は `<epochSecond><millis3桁>`、負値は補数化で順序保持(`TimeRelatedColumnEncodingUtils.java:81-111`)。
- Docs の型マッピング表(`database-adapters.mdx`)と実装は整合。

### 3.2 JDBC 非キー列の型マッピング(`getDataTypeForEngine`)

継承関係: **MariaDB / TiDB は MySQL と同一**(型マッピングは未オーバーライド)、**YugabyteDB は PostgreSQL と同一**、Spanner は PostgreSQL の一部オーバーライド。

| ScalarDB | MySQL/MariaDB/TiDB | PostgreSQL/YugabyteDB | Oracle | SQL Server | SQLite | Db2 | Spanner(PG) |
|---|---|---|---|---|---|---|---|
| BOOLEAN | `BOOLEAN` | `BOOLEAN` | `NUMBER(1)` | `BIT` | `BOOLEAN` | `BOOLEAN` | `boolean` |
| INT | `INT` | `INT` | `NUMBER(10)` | `INT` | `INT` | `INT` | `bigint`※ |
| BIGINT | `BIGINT` | `BIGINT` | `NUMBER(19)` | `BIGINT` | `BIGINT` | `BIGINT` | `bigint` |
| FLOAT | `REAL` | `REAL` | `BINARY_FLOAT` | `FLOAT(24)` | `FLOAT` | `REAL` | `real` |
| DOUBLE | `DOUBLE` | `DOUBLE PRECISION` | `BINARY_DOUBLE` | `FLOAT` | `DOUBLE` | `DOUBLE` | `double precision` |
| TEXT | `LONGTEXT` | `TEXT` | `VARCHAR2(4000)` | `VARCHAR(8000)` | `TEXT` | `VARCHAR(32672)` | `text` |
| BLOB | `LONGBLOB` | `BYTEA` | `BLOB` | `VARBINARY(8000)` | `BLOB` | `BLOB(2G)` | `bytea` |
| DATE | `DATE` | `DATE` | `DATE` | `DATE` | `INT`※ | `DATE` | `date` |
| TIME | `TIME(6)` | `TIME` | `TIMESTAMP(6)`※ | `TIME(6)` | `BIGINT`※ | `TIMESTAMP(6)`※ | `timestamp with time zone`※ |
| TIMESTAMP | `DATETIME(3)` | `TIMESTAMP` | `TIMESTAMP(3)` | `DATETIME2(3)` | `BIGINT`※ | `TIMESTAMP(3)` | `timestamp with time zone`※ |
| TIMESTAMPTZ | `DATETIME(3)` | `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP(3) WITH TIME ZONE` | `DATETIMEOFFSET(3)` | `BIGINT`※ | `TIMESTAMP(3)` | `timestamp with time zone` |

典拠: `RdbEngineMysql.java:262-281`, `RdbEnginePostgresql.java:234-254`, `RdbEngineOracle.java:264-284`,
`RdbEngineSqlServer.java:249-269`, `RdbEngineSqlite.java:111-127`, `RdbEngineDb2.java:70-89`, `RdbEngineSpanner.java:54-74`。
公式 Docs `database-adapters.mdx:59-71` と一致。

※ Spanner INT→`bigint`(4byte int が無い)。SQLite の日付時刻→数値エンコード(§4)。Oracle/Db2 TIME→固定日付付き TIMESTAMP(§4)。Spanner TIME/TIMESTAMP→TZ 付き timestamp(NTZ 型無し)。

### 3.3 JDBC キー列・セカンダリインデックス列での型変更(`getDataTypeForKey`)

`null` を返す = 非キーと同じ型。`size` 既定 = **128**、最小 = **64**(`JdbcConfig.java:105,108`)。プロパティ `scalar.db.jdbc.{mysql,oracle,db2}.variable_key_column_size` で調整。

| エンジン | TEXT キー | BLOB キー | 備考 | 典拠 |
|---|---|---|---|---|
| MySQL/MariaDB/TiDB | `VARCHAR(size)` | `VARBINARY(size)` | 非キーの `LONGTEXT`/`LONGBLOB` はキー不可のため縮小 | `RdbEngineMysql.java:289-298` |
| PostgreSQL/YugabyteDB | `VARCHAR(10485760)` | 変更なし(`BYTEA`) | | `RdbEnginePostgresql.java:262-269` |
| Oracle | `VARCHAR2(size)` | **例外(BLOB キー/index 不可)** | `JDBC_ORACLE_INDEX_OR_KEY_ON_BLOB_COLUMN_NOT_SUPPORTED` | `RdbEngineOracle.java:292-302,470-477` |
| SQL Server | 変更なし(`VARCHAR(8000)`) | 変更なし(`VARBINARY(8000)`) | | `RdbEngineSqlServer.java:277-280` |
| SQLite | 変更なし | 変更なし | | `RdbEngineSqlite.java:135-137` |
| Db2 | `VARCHAR(size) NOT NULL`(PK) | **例外(BLOB キー/index 不可)** | PK 列は全型に ` NOT NULL` 付与 | `RdbEngineDb2.java:403-428` |
| Spanner | 変更なし(`text`) | 変更なし | 親 PostgreSQL と異なり VARCHAR 化しない | `RdbEngineSpanner.java:81-84` |

公式 Docs `database-adapters.mdx:75-80` と整合。

---

## 4. 日付時刻型の物理格納方法(`RdbEngineTimeType*`)

JDBC の日付時刻はエンジンごとに格納方法が大きく異なる。`[公式Docs未掲載]`(Docs は最終 SQL 型のみ、格納メカニズムや `time_column.default_date_component` 系プロパティには触れていない)。

| エンジン | DATE | TIME | TIMESTAMP | TIMESTAMPTZ | 特記 |
|---|---|---|---|---|---|
| MySQL/MariaDB/TiDB | `LocalDate` | `LocalTime` | `LocalDateTime` | **`OffsetDateTime.toLocalDateTime()`** | TZ 無しの `DATETIME(3)` に UTC 壁時計値を格納。読取は `LocalDateTime`→`toInstant(UTC)`。生 SQL/別 TZ セッションからは見え方が変わりうる(`RdbEngineTimeTypeMysql.java:10-30`) |
| PostgreSQL/YugabyteDB | `LocalDate` | `LocalTime` | `LocalDateTime` | `OffsetDateTime` | native TZ 対応(`RdbEngineTimeTypePostgresql.java:10-28`) |
| Oracle | `LocalDate` | **固定日付付き `LocalDateTime`** | `LocalDateTime` | `OffsetDateTime` | TIME を `TIMESTAMP` に固定日付付きで格納。既定日付 `scalar.db.jdbc.oracle.time_column.default_date_component`(既定 `1970-01-01`)(`RdbEngineTimeTypeOracle.java:21-24`) |
| SQL Server | **`String`(BASIC_ISO_DATE)** | `LocalTime` | **`String`(ISO_DATE_TIME)** | **`microsoft.sql.DateTimeOffset`** | DATE/TIMESTAMP は文字列渡し(ユリウス→グレゴリオ移行の 10 日ズレ回避)(`RdbEngineTimeTypeSqlServer.java:13-37`) |
| SQLite | **`Integer`(epoch day)** | **`Long`(nano)** | **`Long`** | **`Long`** | 全て数値エンコードで INT/BIGINT に格納。生 SQL では人間可読でない(`RdbEngineTimeTypeSqlite.java:12-30`) |
| Db2 | **`String`** | **固定日付付き `LocalDateTime`** | **`String`** | **`String`** | DATE/TIMESTAMP/TIMESTAMPTZ は文字列リテラル、TIME は固定日付付き TIMESTAMP。既定日付 `scalar.db.jdbc.db2.time_column.default_date_component`。読取は getString して手動パース(10 日ズレ回避)(`RdbEngineTimeTypeDb2.java:59-77`, `RdbEngineDb2.java:445-481`) |
| Spanner | `LocalDate` | **固定日付付き TZ timestamp** | **`LocalDateTime.atOffset(UTC)`** | `OffsetDateTime` | 既定日付 `scalar.db.jdbc.spanner.time_column.default_date_component`(`RdbEngineTimeTypeSpanner.java:41-60`) |

- 既定の `TIMESTAMPTZ` エンコードは `atOffset(ZoneOffset.UTC)`(`RdbEngineStrategy.java:236-239`)。読み書きは `ofStrict()` 系を使用(精度超過は例外)。
- **落とし穴(MySQL)**: `DATETIME(3)` に UTC 壁時計値を保存するため、MySQL 列を手動で `TIMESTAMP` 型に変えたり、別 TZ の生 SQL からアクセスすると値がずれて見える。ScalarDB 経由の round-trip は一貫。

---

## 5. キー / セカンダリインデックスに使える型の制限(バックエンド別)

コア層(`TableMetadata.build()`)は型制限を課さない。制限は各ストレージ Admin の `checkMetadata` 等で enforce される。

| バックエンド | partition key | clustering key | secondary index | 典拠 |
|---|---|---|---|---|
| Cassandra | 制限なし(BLOB 可) | 制限なし(BLOB 可) | 制限なし | `CassandraAdmin.java:439-457`。ただし `TIMESTAMP`(TZ 無し)型自体が全用途で不可(`:86-88,315-317`) |
| Cosmos DB | 制限チェックなし(BLOB もコード上は可) | **BLOB 不可** | チェックなし | `CosmosAdmin.java:110-117`(`COSMOS_CLUSTERING_KEY_BLOB_TYPE_NOT_SUPPORTED`) |
| DynamoDB | **BLOB は複合 PK の最終列のみ可** | **BLOB 不可** | **BOOLEAN 不可** | `DynamoAdmin.java:283-311,822-825`。index 許可型は BOOLEAN を除く 10 型(`SECONDARY_INDEX_DATATYPE_MAP`, `:126-138`) |
| JDBC: Oracle | BLOB 不可 | BLOB 不可 | BLOB 不可 | `RdbEngineOracle.java:296-298,471-473` |
| JDBC: Db2 | BLOB 不可 | BLOB 不可 | BLOB 不可 | `RdbEngineDb2.java:409-410,422-424` |
| JDBC: その他(MySQL/PG/SQLServer/SQLite) | ソース側制限なし | ソース側制限なし | ソース側制限なし | `getDataTypeForKey` の default は `null`。FLOAT/DOUBLE 等の不可(Docs `:108,123`)は**下層 RDB 自身の制約** |
| Object Storage(S3/Blob/Cloud) | — | — | **型を問わず一切非対応** | `ObjectStorageAdmin.java:178,185,486`(`OBJECT_STORAGE_INDEX_NOT_SUPPORTED`) |

> **注**: Docs `database-adapters.mdx` の「FLOAT/DOUBLE をキー/インデックスに使えない」等の記述の一部は、ScalarDB のソースチェックではなく下層 RDB 側の制約に由来する(`getDataTypeForKey` は `null`=許可を返す)。

### スキーマ変更(alter)の対応状況(要点)

- Cassandra: rename は primary key 列のみ、alter column type / rename table / import 非対応。
- Cosmos/Dynamo: drop/rename column、alter column type、rename table、import 非対応。
- TiDB: BLOB→TEXT の ALTER 不可。Spanner: table/column rename 不可、ALTER COLUMN TYPE は BLOB→TEXT のみ。Oracle: ALTER COLUMN TYPE は INT→BIGINT のみ。
- SQLite: 既存テーブル import 非対応(`getDataTypeForScalarDbInternal` が `AssertionError`, `RdbEngineSqlite.java:171-178`)。namespace/table 名にセパレータ不可。

---

## 6. データモデル関連の落とし穴(→ 90-deep-dives で 3.18.0 再検証・詳細化予定)

既存調査(3.17 以前 / 一部 4.0-SNAPSHOT 典拠)からの繰り越し。**行番号はドリフトの可能性があるためクラス/メソッド名ベースで扱う**。設計レベルで安定と判断したもののみ本節に記載し、バージョン依存が疑われる挙動は `90-deep-dives.md` で 3.18.0 再検証する。

### 6.1 トランザクションメタデータ列がユーザーテーブルに同居する

Consensus Commit を使うと、ユーザー定義列に加えて `tx_id / tx_state / tx_version / tx_prepared_at / tx_committed_at`
と、非主キー列ごとの `before_*` 列が同じテーブルに物理的に追加される(`ConsensusCommitUtils` / `Attribute.BEFORE_PREFIX="before_"`)。
ScalarDB 経由でないと正しく解釈できない。→ 列名の網羅は 90 で 3.18.0 確認。

### 6.2 NoSQL のキーは単一属性に連結エンコードされる

DynamoDB は複合キーをバイナリ連結して `concatenatedPartitionKey`/`concatenatedClusteringKey` 属性に、
Cosmos DB はコロン区切り文字列に連結(`ConcatenationVisitor` の `String.join(":", …)`)。DB ネイティブの複合キー機能は使わない。

- 由来する **TEXT 値の文字制約**: DynamoDB は ` `(0x00 を終端記号に使用)、Cosmos は `:`(区切り文字)を TEXT に含められない。ユーザーには「ScalarDB の仕様」に見える。→ 90 で現行 Docs と突合。
- DynamoDB はソート順保持のため INT/BIGINT の符号ビット反転等を行う(レンジスキャン成立のため)。連結バイト列は検索/比較専用で、値のデコードは個別属性から行う。

### 6.3 列の順序保証はバックエンドで異なる

- **PK / CK の順序は全バックエンドで保持**(TableMetadata は `LinkedHashSet`、永続化も List/LinkedHashSet/ordinal_position)。
- **非キー通常列の順序は JDBC のみ保持**(`ordinal_position`)。Cassandra はドライバがアルファベット順、Dynamo/Cosmos は `HashMap` 詰め替えで順序不定。結果 `SELECT *` の列順が定義順と一致しないことがある。→ 90 で 3.18.0 再検証(HashMap→LinkedHashMap 化の可能性)。
- **運用ガイド**: NoSQL では列順が不定なため、`INSERT` は列名を明示する(値のみの INSERT は危険)。

### 6.4 secondary index と before image 列の落とし穴(3.18.0 検証済み)

非主キー列の条件は Consensus Commit により `(col op x) OR (before_col op x)` に書き換えられる
(`ConsensusCommitUtils.java:485,561-626`)。このため where() に置いた index 列条件は物理 index のアクセスパスとして
使われにくい。**index 検索キー(等値・単一列)だけは変換されず生きる**。`ScanAll` のみ index 列条件を変換しない非対称あり。

- **3.18.0 の緩和**: `before_*` 列に**自動で secondary index が張られる**ようになった
  (`isIndexEventuallyConsistentReadEnabled=false` 時, `ConsensusCommitUtils.java:106-111`)。旧資料
  (`scalardb-before-column-index-issue.md`, 3.15 系)の「手動で `before_income` に index を張る回避策」は自動化されている。
- 詳細と初心者向けの整理は [`30-crud-api-and-limits.md`](./30-crud-api-and-limits.md) §4.3「ScanWithIndex のハマりどころ」を参照。
- SQL プランナ層(`scalardb-sql` 別リポジトリ)の index 選択ロジックは本 worktree に無く未検証 → `90-deep-dives.md` で `scalardb-sql` 取得のうえ扱う。

### 6.5 Oracle CREATE TABLE 問題(要 3.18.0 再検証)

ユーザーテーブルには ORA-08177(SERIALIZABLE 偽陽性)対策の `ROWDEPENDENCIES` + `INITRANS 3 MAXTRANS 255` が付くが、
カタログテーブル(`scalardb.metadata` / `scalardb.virtual_tables`)にはこの対策が欠落。Oracle をプール全体 SERIALIZABLE に
した際、Schema Loader のカタログ大量 INSERT が ORA-08177 で失敗しうる。→ `90-deep-dives.md` で扱う(元資料: `oracle_create_table_problem.md`)。

### 6.6 既存資料と 3.18.0 の差異(記録)

- `scalardb_datetime_type_mapping.md` のコード引用は `TimestampTZColumn.of(...)` だが 3.18.0 実装は `ofStrict(...)`。行番号も 490-498(旧 475-483)にドリフト。既存資料は MySQL/PostgreSQL のみだが、3.18.0 では Oracle/SQLServer/SQLite/Db2/Spanner の固有挙動(§4)がある。
- 3.18.0 の `TableMetadata` は `encryptedColumnNames` を持つ(既存資料に無い新要素)。Object Storage アダプタも既存資料の対象外。
