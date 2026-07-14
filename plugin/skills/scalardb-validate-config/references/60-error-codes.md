# ScalarDB 3.18.0 リファレンス — エラーコード / status code カタログ

エラーログ解析(`scalardb-analyze-errorlog` Skill)の典拠。エラーコードの**体系**、
**カテゴリ→取るべき対処**、例外クラスの分類、そして starter で頻出するコードの厳選カタログをまとめる。

## 典拠

- ソース: `scalardb` タグ `v3.18.0`。`common/CoreError.java`(component `DB-CORE`)、`common/error/{ScalarDbError,Category}.java`。
  SQL は `scalardb-sql` の `sql/common/SqlError.java`(`DB-SQL`)、Cluster は `scalardb-cluster` の各 `*Error.java`。
- 全コードの正典(full catalog)は公式 Docs の status-code ページ(下記)。**Skill は該当コードをここから直接引ける**:
  - `docs/scalardb-core-status-codes.mdx`(DB-CORE)
  - `docs/scalardb-sql/scalardb-sql-status-codes.mdx`(DB-SQL)
  - `docs/scalardb-cluster/scalardb-cluster-status-codes.mdx`(DB-CLUSTER)
  - 同 `scalardb-auth` / `scalardb-abac` / `scalardb-encryption` / `scalardb-embedding-store` / `scalardb-remote-replication`-status-codes.mdx
  - `docs/scalardb-data-loader-status-codes.mdx` / `scalardb-schema-loader-status-codes.mdx` / `scalardb-graphql/*`

---

## 1. コード体系(まずこれを読む)

コード形式: **`<component>-<categoryId><id>`**(`ScalarDbError.buildCode()`, `ScalarDbError.java:48-49`)。

- `<component>`: モジュール識別子(`DB-CORE` / `DB-SQL` / `DB-CLUSTER` / `DB-AUTH` / `DB-ABAC` / `DB-ENCRYPTION` / `DB-EMBEDDING-STORE` / `DB-REPLICATION` / `DB-DATA-LOADER` / `DB-SCHEMA-LOADER` / `DB-GRAPHQL`)。
- `<categoryId>`: **1 桁のカテゴリ番号**(`Category.java:6-9`)。
- `<id>`: 4 桁の連番。

例: `DB-CORE-10000` = `DB-CORE` + カテゴリ `1`(USER) + id `0000`(= "Only a single-column index is supported")。

> **注**: 本リファレンスの他ファイル(30/40 等)では 4 桁 id 単独で `DB-CORE-0006` のように引用している箇所がある。
> 実際の表示コードはカテゴリ数字が付いた `DB-CORE-10006`(USER)。id とカテゴリを合わせて読む。

### カテゴリ → 取るべき対処(errorlog 解析の核心)

| カテゴリ | 数字 | 意味 | **取るべき対処** |
|---|---|---|---|
| USER_ERROR | `1` (`x-1xxxx`) | 呼び出し側の誤り(不正な操作・設定・スキーマ) | **決定的。リトライ無意味。コード/設定/スキーマを修正する** |
| CONCURRENCY_ERROR | `2` (`x-2xxxx`) | 一時的な競合(衝突・デッドロック等) | **トランザクションを最初からやり直す(リトライ)** |
| INTERNAL_ERROR | `3` (`x-3xxxx`) | ScalarDB 内部/環境の異常 | **調査・報告。設定やインフラ(接続・権限)を点検** |
| UNKNOWN_TRANSACTION_STATUS_ERROR | `4` (`x-4xxxx`) | **commit の成否が不明** | **「ロールバックされた」と仮定してはいけない**。`getState(txId)` で確認するか、冪等に再実行(lazy recovery) |

典拠: `Category.java:6-9`(`USER_ERROR("1")`/`CONCURRENCY_ERROR("2")`/`INTERNAL_ERROR("3")`/`UNKNOWN_TRANSACTION_STATUS_ERROR("4")`)、Docs 各 status-code ページの "Error code classes"。

> **これが Skill の一次判定**: コードのカテゴリ数字だけで「直す/リトライする/状態確認する」の初動が決まる。

---

## 2. 例外クラス → カテゴリ / ハンドリング

トランザクション API が投げる主な例外(`transaction/*.java`)と対処:

| 例外 | 分類 | 対処 |
|---|---|---|
| `CrudConflictException` | CONCURRENCY | CRUD 中の競合。トランザクションをやり直す |
| `CommitConflictException` | CONCURRENCY | commit 時の競合。やり直す |
| `PreparationConflictException` | CONCURRENCY | prepare 時の競合(主に 2PC)。やり直す |
| `ValidationConflictException` | CONCURRENCY | SERIALIZABLE 検証で競合検出。やり直す |
| `UnknownTransactionStatusException` | UNKNOWN | **commit 成否不明**。状態確認 or 冪等再実行(§1) |
| `UnsatisfiedConditionException` | USER 相当 | `PutIf`/`DeleteIf`/`UpdateIf` の条件不成立 or 対象非存在。ロジックを見直す |
| `CrudException` / `CommitException` / `PreparationException` / `ValidationException` | 種々 | メッセージのコードでカテゴリ判定 |
| `TransactionNotFoundException` | USER 相当 | 期限切れ/未知の txId で resume/join。新規に begin |

- **リトライして良いのは CONCURRENCY 系のみ**。USER 系(`UnsatisfiedConditionException` 等)をリトライしても同じ結果。
- `UnknownTransactionStatusException` は **リトライ前に状態確認**(二重適用を避ける)。

---

## 3. starter 頻出コード カタログ(DB-CORE, USER = `DB-CORE-1xxxx`)

`generate-schema` / CRUD 実装でよく踏むもの。原因と対処、関連アンチパターン(→ `70`)を併記。全て `CoreError.java` 典拠。

| コード | メッセージ要旨 | 原因 / 対処 | 関連 |
|---|---|---|---|
| `DB-CORE-10000` | Only a single-column index is supported | secondary index の複数列指定。index は単一列等値のみ | `30 §4.3`, AP-3 |
| `DB-CORE-10001` | index key が非 index 列 | index でない列で index 検索。index を張るか条件を変える | `30 §4.3` |
| `DB-CORE-10005` | limit cannot be negative | LIMIT に負値 | — |
| `DB-CORE-10006` | Cross-partition scan is not enabled | ScanAll を投げたが `cross_partition_scan.enabled=false` | `10`, `30 §4.2`, AP-4 |
| `DB-CORE-10007` | cross-partition scan ordering 未有効/非対応 | ordering は JDBC のみ+要有効化 | `30 §4.2`, `50` |
| `DB-CORE-10008` | cross-partition scan filtering 未有効 | `filtering.enabled=false` で条件指定 | `10`, `30 §4.2` |
| `DB-CORE-10009` | projection にない列 | 存在しない列を projection 指定 | `30 §6.1` |
| `DB-CORE-10015` | 条件(condition)不正 | 型/値不一致、非対応演算子 | `30 §3` |
| `DB-CORE-10016` | table does not exist | スキーマ未作成/名前誤り。schema loader で作成 | `20` |
| `DB-CORE-10017` | Put の列値不正 | 型不一致等 | `20 §2` |
| `DB-CORE-10018` | empty mutations | 空の mutate | `30 §2` |
| `DB-CORE-10019` | multi-partition mutation | 単一 mutate/バッチで複数パーティションを跨いだ | `30 §6.1`, AP-7 |
| `DB-CORE-10020` / `10021` | partition key / clustering key 不正 | キー未指定・部分指定(scan は PK 完全指定必須) | `30 §4.1` |
| `DB-CORE-10076` | Cosmos: BLOB 条件は 4 演算子のみ | Cosmos の BLOB 列条件制限 | `30 §6.3`, `50` |
| `DB-CORE-10082` | Dynamo: BOOLEAN 条件は 4 演算子のみ | Dynamo の BOOLEAN 制限 | `30 §6.3` |
| `DB-CORE-10108` | Dynamo: batch size exceeded | **1 バッチ 100 mutation 超**。分割する | `30 §6.3`, `50 §1`, AP-7 |
| `DB-CORE-10126` | unsupported mutation type | Put/Delete 以外の mutation | `30 §6.1` |
| `DB-CORE-10211` | read-only transaction で mutation 不可 | read-only tx で書き込み | `30 §6.2` |
| `DB-CORE-10212`〜`10215` | atomicity unit(RECORD/TABLE/NAMESPACE/STORAGE)超えの mutation | storage の原子性単位を跨いだ | `30 §6.1`, `50 §1` |
| `DB-CORE-10260` / `10261` | SERIALIZABLE で before-image index 無しの index get/scan 不可 | `repairTable()` で before-image index を作る or 分離レベル変更 | `30 §6.2`, `20 §6.4` |
| `DB-CORE-10262` | SERIALIZABLE の cross-partition scan で index 列条件不可 | 条件/分離レベルを見直す | `30 §6.2` |
| `DB-CORE-10279` | Object Storage: BLOB がサイズ上限超 | 1.5GiB 上限 | `50 §4` |

> Cassandra の TIMESTAMP 非対応・Oracle/Db2 の BLOB キー不可なども USER カテゴリのコードで返る(`CoreError.java`、`50` の各制限参照)。

### CONCURRENCY(`DB-CORE-2xxxx`)/ UNKNOWN(`DB-CORE-4xxxx`)

- `DB-CORE-2xxxx`: 競合・CAS 失敗・ロールバック系(例 `NO_MUTATION_APPLIED`=`20000`)。→ **トランザクションをやり直す**。アプリは begin〜commit を関数化してリトライループで囲むのが定石。
- `DB-CORE-4xxxx`: commit 成否不明。→ **状態確認 or 冪等再実行**。`UnknownTransactionStatusException` に対応。
- `DB-CORE-3xxxx`: 内部/環境(接続断・権限・想定外)。→ 設定・インフラを点検。

---

## 4. SQL エラー(`DB-SQL-1xxxx`, USER)

`scalardb-sql` の `SqlError.java` 典拠。JOIN / GROUP BY 周りの頻出コード(→ `40`)。

| コード(id) | 要旨 | 関連 |
|---|---|---|
| `DB-SQL-1` + `0041` | 曖昧な列名(JOIN で要テーブル修飾) | `40 §3.3` |
| `DB-SQL-1` + `0042` | WHERE/ORDER BY に結合先テーブル列(不可) | `40 §3.3` |
| `DB-SQL-1` + `0063` | RIGHT OUTER JOIN が最初の JOIN でない | `40 §3.3`, AP(SQL) |
| `DB-SQL-1` + `0064`〜`0067` | JOIN 述語不正(未指定/型不一致/列重複/PK・index 未指定) | `40 §3.3` |
| `DB-SQL-1` + `0071` | 暗号化列を WHERE/ORDER BY/JOIN に指定 | `40 §3.2` |
| `DB-SQL-1` + `0081`〜`0088` | 集約/GROUP BY 関連(引数数・不明関数・非集約列・HAVING/ordering の projection 要件) | `40 §3.4` |
| `DB-SQL-1` + `0096` | WHERE に関数(不可) | `40 §3.2` |
| `DB-SQL-1` + `0097` | GROUP BY 無しの ORDER BY に関数(不可) | `40 §3.2` |
| `DB-SQL-1` + `0083` | 未登録の集約関数(COUNT/SUM/MIN/MAX/AVG 以外) | `40 §3.4` |

> SQL パースエラー(サブクエリ/UNION/DISTINCT/IN/CASE/算術式 等の非対応構文)は文法エラーとして返る(`40 §4`)。

---

## 5. Cluster / auth / ABAC / encryption / replication エラー

Cluster 系は各モジュールの `*Error.java`(`scalardb-cluster`)と、対応する Docs status-code ページが正典:

| component | 定義クラス | Docs |
|---|---|---|
| `DB-CLUSTER` | `common/ClusterError.java`, `common/ProtoError.java` | `scalardb-cluster-status-codes.mdx` |
| `DB-AUTH` | `auth/AuthError.java` | `scalardb-auth-status-codes.mdx` |
| `DB-ABAC` | `abac/AbacError.java` | `scalardb-abac-status-codes.mdx` |
| `DB-ENCRYPTION` | `encryption/EncryptionError.java` | `scalardb-encryption-status-codes.mdx` |
| `DB-EMBEDDING-STORE` | `embedding/common/EmbeddingError.java` | `scalardb-embedding-store-status-codes.mdx` |
| `DB-REPLICATION` | `replication/common/ReplicationError.java` | `scalardb-remote-replication-status-codes.mdx` |

- 例(`30`/`11` で既出): `DB-CLUSTER-10059`(resume 不可)、`DB-CLUSTER-10061`(begin 前の getId 不可)= piggyback-begin 関連(cluster ソースで要確認、`30 §7`)。
- カテゴリ数字の読み方(§1)は全 component 共通。

---

## 6. Skill(analyze-errorlog)への接続

1. **コードを分解**: `<component>-<categoryId><id>` に分ける。
2. **カテゴリ数字で初動判定**(§1): 1=修正 / 2=リトライ / 3=調査 / 4=状態確認。
3. **component + id で意味を引く**: まず本ファイルの §3〜§5、無ければ Docs の該当 status-code ページ。
4. **関連アンチパターンを提示**: §3〜§4 の「関連」列(→ `70`)で「なぜ起きたか・正しい代替」を案内。
5. 例外クラス名しか無いログ(コード無し)は §2 で分類。
