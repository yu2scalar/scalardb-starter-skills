# ScalarDB 3.18.0 リファレンス — CRUD/トランザクション API と制限

Spring Boot 上の CRUD REST アプリ実装(`scalardb-generate-springboot-starter` Skill)の典拠。
操作の種類・条件・スキャン制限・トランザクションの分離レベル・実行時にエラーになる制約をまとめる。

## 典拠

- ソース: `scalardb` タグ `v3.18.0`(commit `1953f0131`)
- Docs: `docs-scalardb` ブランチ `3.18` / `docs/api-guide.mdx`, `docs/consensus-commit.mdx`, `docs/run-transactions-through-scalardb-core-library.mdx`
- 主な典拠: `api/CrudOperable.java`, `api/DistributedTransaction.java`, `api/DistributedStorage.java`,
  `api/*(Get/Scan/Put/Insert/Update/Upsert/Delete/Mutation/Condition*).java`,
  `common/checker/OperationChecker.java`, `transaction/consensuscommit/*`, 各 storage の `*OperationChecker.java`
- エラーコードは `DB-CORE-xxxx`(`common/CoreError.java`)

---

## 1. 抽象レイヤの選択

`scalar.db.transaction_manager`(→ [`10-configuration-core.md`](./10-configuration-core.md))で選ぶ。

| レイヤ | ACID/分離 | 用途 | 主なクラス |
|---|---|---|---|
| `DistributedTransaction`(consensus-commit) | ✅ 複数レコード/複数 DB 横断のトランザクション | 通常のアプリ | `DistributedTransactionManager` / `DistributedTransaction` |
| `TwoPhaseCommitTransaction`(2PC) | ✅ 参加者/コーディネータで協調 | マイクロサービス間 | `TwoPhaseCommitTransactionManager` |
| single-crud-operation | 単発操作のみ原子的 | 単一操作の軽量パス | `SingleCrudOperationTransactionManager` |
| `DistributedStorage`(非トランザクション) | ❌ ACID なし(単位内のみ原子的) | 最速の低レベルアクセス | `DistributedStorage` |

---

## 2. CRUD 操作

### 2.1 トランザクション API(`CrudOperable` / `TransactionCrudOperable`)

`TransactionCrudOperable extends CrudOperable<CrudException>`(`TransactionCrudOperable.java:10`)。

| メソッド | 意味 | 戻り値 | 典拠 |
|---|---|---|---|
| `get(Get)` | 1 件取得(`Get`/`GetWithIndex`) | `Optional<Result>` | `CrudOperable.java:35` |
| `scan(Scan)` | 複数取得(全件リスト化) | `List<Result>` | `CrudOperable.java:52` |
| `getScanner(Scan)` | 遅延イテレートの `Scanner` | `Scanner` | `CrudOperable.java:70` |
| `put(Put)` / `put(List)` | 挿入または更新 | void | `CrudOperable.java:83,97`(**@Deprecated 3.13.0**、`insert`/`upsert`/`update`/`mutate` 推奨) |
| `insert(Insert)` | 挿入のみ(既存で競合エラー) | void | `CrudOperable.java:111` |
| `upsert(Upsert)` | 存在すれば更新、なければ挿入 | void | `CrudOperable.java:121` |
| `update(Update)` | 更新のみ(**対象が無ければ no-op**) | void | `CrudOperable.java:132` |
| `delete(Delete)` / `delete(List)` | 削除 | void | `CrudOperable.java:143,157`(List 版は @Deprecated) |
| `mutate(List<? extends Mutation>)` | 複数ミューテーションをまとめて | void | `CrudOperable.java:166` |
| `batch(List<? extends Operation>)` | Get/Scan/ミューテーション混在バッチ | `List<BatchResult>` | `CrudOperable.java:177` |

- `Scanner`: `one()`/`all()`/`close()`(`AutoCloseable`, `Iterable<Result>`)。
- `BatchResult`: `getType()`(GET/SCAN/PUT/INSERT/UPSERT/UPDATE/DELETE)、`getGetResult()`/`getScanResult()`。

### 2.2 Put系 と Insert/Update/Upsert の違い(存在前提)

| 操作 | 対象が存在 | 存在しない | condition | consistency | 典拠 |
|---|---|---|---|---|---|
| `Put`(条件なし) | 更新(blind write) | 挿入 | ✅(`MutationCondition`) | ✅ | `Put.java:40-57` |
| `Insert` | **競合エラー** | 挿入 | ❌(`UnsupportedOperationException`) | ❌ | `Insert.java:16,41-62` |
| `Upsert` | 更新 | 挿入 | ❌ | ❌ | `Upsert.java:16,37-` |
| `Update` | 更新 | **no-op** | ✅ | ❌ | `Update.java:16,22-32` |

**`condition` / `consistency` 列の凡例**: その操作クラスが当該設定の getter/setter を**API として持つか**を示す(効果の有無ではない)。

| 記号 | 意味 |
|---|---|
| ✅ | `withCondition`/`withConsistency`(および getter)が動作し、値を保持できる |
| ❌ | 呼ぶと `UnsupportedOperationException` を投げる(その操作は当該設定を持てない) |

> **重要**: `consistency` は ✅ でも、**トランザクション CRUD では無視され常に `LINEARIZABLE`**(§3.4)。したがって `Put` の consistency ✅ が
> 実際に効くのは非トランザクションの `DistributedStorage` 経由のときのみ。`Insert`/`Upsert`/`Update` は 3.13 以降のトランザクション前提の
> 新 API のため、レガシーな Consistency ノブを意図的に露出していない(`Insert.java:53-61` 等で `UnsupportedOperationException`)。

> **`Put` の deprecated 範囲(誤解しやすい点)**: deprecated なのは `CrudOperable` の専用メソッド
> **`put(Put)` / `put(List<Put>)`(@Deprecated 3.13.0、4.0.0 で削除)** だけで、**`Put` クラス自体・`PutIf`/`PutIfExists`/`PutIfNotExists`
> 条件クラス・`ConditionBuilder.putIf*` は deprecated ではない**(`Put.java:46` にアノテーションなし、`PutIf.java:19` ほか)。
> `put()` メソッド廃止後(4.0.0〜)も、Put は `mutate(List)` に渡して実行できる(`Put extends Mutation`)。
> 新規コードでは意味の明確な `insert`/`upsert`/`update` を推奨し、条件付き blind write が必要な場合のみ `mutate()` 経由で `Put` を使う。

- `Insert`/`Upsert` は condition も consistency も持てない(getter/setter が `UnsupportedOperationException`)。
- 条件付き `Put`/`Update` は事前読み込み(明示 Get/Scan または implicit pre-read)が必要、`Delete` は implicit pre-read 常時有効(Docs `api-guide.mdx:1129,1274,1305`。ソース javadoc には pre-read 要件の明記なし)。
- `Mutation.condition`/`getCondition`/`withCondition` は @Deprecated 3.13.0(ただし `MutationCondition` を使う条件指定は builder 経由で現役)。

### 2.3 非トランザクション API(`DistributedStorage`)

トランザクション版と異なり **`insert`/`upsert`/`update`/`getScanner`/`batch` は無い**(`DistributedStorage.java:70`)。

| メソッド | 備考 | 典拠 |
|---|---|---|
| `get(Get)` | `Optional<Result>` | `DistributedStorage.java:131` |
| `scan(Scan)` | `Scanner` を直接返す(List でない) | `DistributedStorage.java:148` |
| `put(Put)` / `put(List)` | 複数 Put が複数パーティションを跨ぐと `MultiPartitionException` | `DistributedStorage.java:157-167` |
| `delete(Delete)` / `delete(List)` | | `DistributedStorage.java:175-183` |
| `mutate(List)` | 原子性は `StorageInfo.getMutationAtomicityUnit()` 単位内のみ、上限 `getMaxAtomicMutationsCount()`、実行順序は無保証 | `DistributedStorage.java:185-202` |

---

## 3. 条件式(condition)

### 3.1 ミューテーション条件(`MutationCondition`)

`ConditionBuilder`(`ConditionBuilder.java:25`)で生成。

| 条件 | 適用操作 | 意味 | 典拠 |
|---|---|---|---|
| `PutIf` | Put | 条件が全成立時のみ | `ConditionBuilder.java:33,43` |
| `PutIfExists` | Put | 対象が存在する時のみ | `:52` |
| `PutIfNotExists` | Put | 対象が存在しない時のみ | `:61` |
| `DeleteIf` | Delete | 条件成立時のみ | `:71,81` |
| `DeleteIfExists` | Delete | 存在する時のみ | `:90` |
| `UpdateIf` | Update | 条件成立時のみ | `:100,110` |
| `UpdateIfExists` | Update | 存在する時のみ | `:119` |

- `PutIf`/`DeleteIf`/`UpdateIf` の式は **AND 結合のみ**(OR は無い)。
- ミューテーション条件では **`LIKE`/`NOT_LIKE` は使用不可**(ビルダーが `IllegalArgumentException`, `ConditionBuilder.java:1186-1193` ほか)。
- 条件不成立/対象非存在時は `UnsatisfiedConditionException`。

### 3.2 演算子(`ConditionalExpression.Operator`)

`EQ, NE, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL, LIKE, NOT_LIKE`(`ConditionalExpression.java:385-396`)。全 ScalarDB 型に対応する比較メソッドと、全型の null 判定、TEXT 専用の LIKE を提供。

- `LikeExpression`(TEXT 専用): エスケープ文字は既定 `\`(単一文字/空文字のみ、空文字で無効化)。ワイルドカードは `_`(1 文字)/`%`(任意長)。パターン検証あり(`LikeExpression.java:37-77`)。

### 3.3 Selection(Get/Scan)のフィルタ条件

`AndConditionSet`/`OrConditionSet` は **Selection の `where()` 用**(ミューテーション条件ではない)。

- 正規形制約: `where` チェーンは「`ConditionalExpression` / `OrConditionSet` の AND 結合(CNF)」**または**「`ConditionalExpression` / `AndConditionSet` の OR 結合(DNF)」のいずれか。AND/OR の任意ネストは不可。内部は DNF(`Selection.Conjunction` の集合)で保持(`Selection.java:101-155`, Docs `api-guide.mdx:820,982`)。
- Get の `where` で条件不一致なら `Optional.empty()`。

### 3.4 Consistency(非トランザクション用)

enum: `SEQUENTIAL`(既定) / `EVENTUAL` / `LINEARIZABLE`(`Consistency.java:8-26`, `Operation.java:41`)。

- **トランザクション CRUD では無視され、常に LINEARIZABLE**(`CrudOperable.java:11-14`, Docs `api-guide.mdx:720`)。Consistency が効くのは `DistributedStorage`(非トランザクション)経由のみ。

**具体的な効果(バックエンド別マッピング)**: Consistency はストレージ層の読み書き整合性レベルを指定する。実際の動作はバックエンドごとに異なる。

| ScalarDB Consistency | Cassandra | DynamoDB | Cosmos DB | JDBC |
|---|---|---|---|---|
| `SEQUENTIAL`(既定) | CQL `QUORUM` | 強整合読み取り(`consistentRead=true`) | operation 単位では効かず、クライアント設定 `scalar.db.cosmos.consistency_level`(既定 `STRONG`)に従う | 効果なし |
| `EVENTUAL` | CQL `ONE`(=古い値が返りうる) | 結果整合読み取り(`consistentRead=false`) | 同上 | 効果なし |
| `LINEARIZABLE` | 読み取り: `SERIAL` / 条件付き書き込み: QUORUM + Paxos(`SERIAL`) | 強整合読み取り(`consistentRead=true`) | 同上 | 効果なし |

典拠: Cassandra `StatementHandler.java:103-120`、DynamoDB `SelectStatementHandler.java:117-118,187,210`、Cosmos `CosmosUtils.java:28-33`(クライアント設定で決定)、JDBC は Consistency を参照しない。**[公式Docs未掲載]**(このバックエンド別マッピングはソースのみで確認できる)。

実用的な指針: `EVENTUAL` は速いが古い値が返りうる、`SEQUENTIAL`(既定)は強整合読み取り、`LINEARIZABLE` は最強(Cassandra は Paxos/SERIAL を使用)。Cosmos は operation 単位の指定が無効でクライアント設定依存、JDBC は無効。

---

## 4. Scan の種類と制限

| 種類 | クラス | 対象 | 用途 |
|---|---|---|---|
| 通常 Scan | `Scan` | 単一 partition 内、clustering key 範囲 | partition key 指定の範囲取得 |
| ScanWithIndex | `ScanWithIndex` | secondary index(等値) | index 列で検索 |
| ScanAll(cross-partition) | `ScanAll` | テーブル全体 | partition key 無指定の全件/条件検索 |

検証は `common/checker/OperationChecker.java`。

### 4.1 通常 Scan の制約

| 項目 | 仕様 | 典拠 |
|---|---|---|
| partition key | **完全指定必須**(部分不可) | `OperationChecker.java:135,461-465,486-491` |
| clustering key 範囲 | start/end 任意(省略/片側可)、inclusive/exclusive | `Scan.java:34-37,138-223` |
| clustering key 部分指定 | 可(`allowPartial=true`) | `OperationChecker.java:216-238` |
| start/end 境界整合 | start と end は同じ size かつ最終列以外が全て等値(同一 prefix + 最終列で範囲)。違反で例外 | `OperationChecker.java:189-214`。**[公式Docs未掲載]** |
| ordering | clustering key 定義順の prefix に一致。全順方向 or 全逆方向(混在不可) | `OperationChecker.java:240-273`。混在不可は **[公式Docs未掲載]** |
| limit | `<0` は例外、`0` は無制限 | `OperationChecker.java:139-141` |
| projections | メタデータに存在する列のみ | `OperationChecker.java:179-187` |

### 4.2 cross-partition scan(ScanAll)

`scalar.db.cross_partition_scan.{enabled,filtering.enabled,ordering.enabled}`(既定 `true`/`false`/`false`)と連動。

- `enabled=false` で ScanAll → 拒否(`DB-CORE-0006`)。ordering 未有効で ordering 指定 → 拒否(`0007`)。filtering 未有効で条件指定 → 拒否(`0008`)。(`OperationChecker.java:146-177`)
- 操作単位の attribute で config を上書き可(Docs `api-guide.mdx:1094-1113`)。
- **ordering は JDBC のみ対応**: 非 JDBC storage は `ordering.enabled=true` を storage 生成時に拒否(`Cassandra.java:52-55`, `Cosmos.java:54`, `Dynamo.java:58`, `ObjectStorage.java:38`, Docs `api-guide.mdx:1069`)。ScanAll の ordering は任意の列を指定可(clustering key に限らない)。
- **非 JDBC の cross-partition scan は SERIALIZABLE 指定でも SNAPSHOT 相当で実行され得る**(Docs `api-guide.mdx:1030`)。

### 4.3 ScanWithIndex(secondary index 検索)

#### 仕様

| 項目 | 仕様 | 典拠 |
|---|---|---|
| index 列 | **等値(単一 index key)のみ**、範囲不可 | `ScanWithIndex.java:78-116` |
| index は単一列のみ | index key の size が 1 でなければ例外(`INDEX_ONLY_SINGLE_COLUMN_INDEX_SUPPORTED`) | `OperationChecker.java:100-105` |
| indexed 列であること | index key 列が secondary index でなければ例外 | `OperationChecker.java:107-112` |
| clustering key 範囲 | **指定不可** | `OperationChecker.java:123-126` |
| ordering | **指定不可** | `ScanWithIndex.java:124-128`, `OperationChecker.java:128-131` |
| where() 追加条件 | 可(ただし index 検索キーではなくフィルタ扱い、下記) | `ScanBuilder.java:517-527,634-644` |
| limit | 可 | `ScanWithIndex.java:136` |

#### ハマりどころ(secondary index は特に誤解が多い)

1. **1 回の検索で index として使えるのは 1 列だけ**。テーブルに複数の secondary index を張っても、
   ScanWithIndex の index key は単一 `Key`(1 列)しか渡せない(`ScanBuilder.indexKey(Key)`, `ScanBuilder.java:95-97`)。
   複数の index 列を同時に「index 検索キー」にすることは API 上不可能(`OperationChecker.java:100-105`)。
2. **index key は等値のみ・範囲不可**。`>` `<` 等は index key に指定できない(`ScanWithIndex.java:78-116`)。
   `withStart()`/`withEnd()` は ScanWithIndex では常に `UnsupportedOperationException`(`ScanWithIndex.java:78-128`)。
   範囲で絞りたい場合は `where()` に回すが、それは **index 検索キーではなくフィルタ**になる(次項)。
   - **対比(混同注意)**: 範囲(range)スキャンが使えるのは **clustering key** のみ(通常 `Scan` の `withStart()`/`withEnd()`、partition key 完全指定が前提)。**secondary index は範囲不可・等値専用**。「範囲で速く引きたい」要件は secondary index ではなく clustering key 設計で満たす(→ [`20-data-model-and-types.md`](./20-data-model-and-types.md))。
   - **なぜ範囲不可か(物理構造の理由)**: clustering key は**ソートして格納**される(DynamoDB は `KeyType.RANGE`, `DynamoAdmin.java:349-350`／Cassandra は clustering column の on-disk ソート／JDBC は PK 順序)ので range が効く。一方 secondary index は**ソート構造ではない**: DynamoDB GSI は **`KeyType.HASH` のみで作成**(RANGE キーなし, `DynamoAdmin.java:365-370`)、Cassandra は native 2i(SASI ではない, `CassandraAdmin.java:174-177`)で、いずれも等値専用。値 → 主キーの対応がハッシュ分散で順序を持たないため範囲不可。JDBC の B-tree index は本来 range 可能だが、ScalarDB は **全バックエンド共通の最小公倍数**に合わせて API を等値専用に統一している。
3. **where() の追加条件は「フィルタ」であって index 検索キーにならない**。index key 列以外(たとえ別の
   index 列でも)を `where()` に置くと `conjunction` 扱いになり、storage 側 WHERE の後段フィルタになる
   (`ScanBuilder.java:634-644`, `SimpleSelectQuery.java:87-109`)。**「2 つ目の index も条件に入れれば速くなる」は誤解**。
4. **トランザクション経由だと非主キー列の条件が before-image 列との OR に化ける**(最重要)。
   Consensus Commit は storage へ scan を投げる前に、非主キーの条件を `(col op x) OR (before_col op x)` に
   書き換える(`ConsensusCommitUtils.java:485,561-626`, 生成 SQL は `SimpleSelectQuery.java:100-108`)。
   OR に化けた条件は物理 index のアクセスパスとして使われにくく、フィルタ/スキャンに落ちやすい。
   - **index key 列(等値, partition key スロット)は変換されず `col=?` のまま**なので、index 検索キー 1 列は生きる(`ConsensusCommitUtils.java:479-490`)。
   - **`ScanAll`(cross-partition)だけは index 列条件を変換しない**という非対称がある(`convertIndexedColumns = !(scan instanceof ScanAll)`, `ConsensusCommitUtils.java:485`)。同じ条件でも Scan 種別で storage クエリ形が変わる。
5. **3.18.0 の緩和点(旧情報との差)**: 3.18.0 は `before_*` 列に **自動で secondary index を張る**
   (`isIndexEventuallyConsistentReadEnabled=false` の場合, `ConsensusCommitUtils.java:106-111`)。
   旧世代の「`before_income` に index が無いから必ず seq scan / 手動で index を張る」という前提は変化しており、
   両 index が揃えば DB が bitmap OR を選べる余地がある。ただし **実際に index が使われるかは下層 DB のプランナ判断**で、
   ScalarDB core からは「index 列を 2 つ同時に index 検索キーとして渡すことはない」ところまでしか断定できない。
   > SQL プランナ層(`scalardb-sql` 別リポジトリの `ScanOperator` 等)の「等値のみ index key 化」ロジックは
   > 本 core worktree に無く 3.18.0 で未検証。SQL 経由の詳細は `40-sql-support-and-limits.md` で扱う。
6. **旧テーブルの注意**: `before_*` index が欠落したテーブルは起動時に警告が出る(`ConsensusCommitUtils.java:710-751`)。
   SERIALIZABLE では before-image index の無い index get/scan は拒否(§6.2, `DB-CORE-0260/0261`)、
   SNAPSHOT/READ_COMMITTED では結果整合になり得る。`repairTable()` で index を補える。

> **設計指針**: secondary index は「単一列の等値検索を 1 つ」だけ高速化する道具と割り切る。複数条件での高速化を
> 期待するなら、アクセスパターンに合わせた partition key / clustering key 設計を優先する(→ [`20-data-model-and-types.md`](./20-data-model-and-types.md))。

---

## 5. トランザクション・セマンティクス

### 5.1 ライフサイクル

- **Manager**(`DistributedTransactionManager`): `begin()`/`begin(txId)`/`beginReadOnly(...)`/`start(...)`(begin の別名)、`join(txId)`(=`resume`)、`resume(txId)`、`getState(txId)`、`rollback`/`abort`、`close`。`start(Isolation/Strategy)` は @Deprecated。
- Manager は `TransactionManagerCrudOperable` も継承 → CRUD を tx なしで単発実行すると内部で自動 begin→commit。
- **Transaction**(`DistributedTransaction`): CRUD(§2)+ `getId()`/`commit()`/`rollback()`/`abort()`。`commit()` は `CommitConflictException`/`CommitException`/`UnknownTransactionStatusException` を送出しうる。
- **`TransactionState`**: `PREPARED(1)`/`DELETED(2)`/`COMMITTED(3)`/`ABORTED(4)`/`UNKNOWN(5)`(`TransactionState.java:5-10`。数値 ID は [公式Docs未掲載])。Coordinator テーブルが単一の真実源。

### 5.2 分離レベル(Isolation)

> **Isolation と Consistency は別物(初心者が最も混同する点)**: Isolation(本節)は**トランザクション**の分離レベルで、
> **複数トランザクション間**の可視性を制御する(Consensus Commit プロトコル層)。一方 Consistency(§3.4)は**単一操作**の
> ストレージ層の読み書き整合性(下層 DB のレプリカ整合性)で、**非トランザクションの `DistributedStorage` でのみ有効**。
> Consensus Commit は内部で常に `LINEARIZABLE` の storage 操作を部品として使い、その上に Isolation を構築するため、
> **トランザクション内では Consistency は無視され常に LINEARIZABLE**(`CrudOperable.java:11-14`)。→ トランザクションを使う限り意識するのは Isolation だけ。

| 観点 | Isolation level(§5.2) | Consistency(§3.4) |
|---|---|---|
| 対象 | トランザクション全体 | 単一操作 |
| enum | `SNAPSHOT` / `SERIALIZABLE` / `READ_COMMITTED` | `SEQUENTIAL` / `EVENTUAL` / `LINEARIZABLE` |
| レイヤ | Consensus Commit プロトコル | 下層 DB のレプリカ整合性 |
| 制御対象 | 複数トランザクション間の可視性(read/write skew 等) | 1 操作がレプリカから最新を読むか |
| 有効な場面 | `DistributedTransaction` 使用時 | `DistributedStorage`(非トランザクション)のみ。tx 内では無視 |

> **注**: `api.Isolation`(`SNAPSHOT`/`SERIALIZABLE`)は enum 全体が @Deprecated(3.5.0)。実際に効くのは
> `consensuscommit.Isolation`(`READ_COMMITTED`/`SNAPSHOT`/`SERIALIZABLE`)で、`scalar.db.consensus_commit.isolation_level` の既定は `SNAPSHOT`。

| レベル | 読み取り可視性 | 検証(validation) | 備考 |
|---|---|---|---|
| **SNAPSHOT**(既定) | スナップショット読み取り(初回読取を read set にキャッシュ)。未コミットは最新へリカバリ(`RETURN_LATEST_RESULT_AND_RECOVER`) | なし | read skew / write skew / read-only anomaly が起きうる(グローバルスナップショット未生成) |
| **SERIALIZABLE** | SNAPSHOT と同じ + | **あり**(EXTRA_READ)。commit 時に read/scan/scanner set を再読込し anti-dependency 検出で abort | 最強。`ValidationConflictException` |
| **READ_COMMITTED** | スナップショット読み取り**なし**。毎回ストレージを読みコミット済み(before-image)を返す。**非最新のコミット済みを返しうる** | なし | 最速。read-only 時は `RETURN_COMMITTED_RESULT_AND_NOT_RECOVER`(リカバリせず before-image 返却) |

典拠: `TransactionContext.java:47-79`(`isSnapshotReadRequired`/`isValidationRequired`)、`CrudHandler.java:105-119,239-256`、`CommitHandler.java:335-349`、`Snapshot.java:557-614`、Docs `consensus-commit.mdx:150-158`。

- SERIALIZABLE では before-image index を持たないテーブルへの secondary index get/scan を拒否(§6.2)。
- 旧 `serializable_strategy` は deprecated、SERIALIZABLE は常に EXTRA_READ。

### 5.3 2PC(`TwoPhaseCommitTransaction`)

- 流れ: `prepare()` → `validate()`(SERIALIZABLE で必須) → `commit()`。1PC が `commit()` に内包する各フェーズを明示的に公開(`TwoPhaseCommitTransaction.java:82-114`)。
- コーディネータが `start(txId)`、参加者が同一 txId で `join(txId)`/`resume(txId)`。
- **group commit と併用不可**: 2PC + Coordinator group commit は `IllegalArgumentException`(`CONSENSUS_COMMIT_GROUP_COMMIT_WITH_TWO_PHASE_COMMIT_INTERFACE_NOT_ALLOWED`, `TwoPhaseConsensusCommitManager.java:190-196`)。
- クライアント側 piggyback-begin / write-buffering は Cluster 経由時に `resume()`/`join()` を制限する(→ [`11-configuration-cluster.md`](./11-configuration-cluster.md) §2)。

### 5.4 single-crud-operation の制約

`begin`/`beginReadOnly`/`resume`/`getState`/`rollback(txId)` はすべて `UnsupportedOperationException`。
`mutate`/`batch` は **1 件のみ**(複数で `MULTIPLE_MUTATIONS_NOT_SUPPORTED`/`MULTIPLE_OPERATIONS_NOT_SUPPORTED`、空で `EMPTY_MUTATIONS_SPECIFIED`)。
各 CRUD を `DistributedStorage` に `LINEARIZABLE` で直接発行(insert=`putIfNotExists`、update=`putIf`/`putIfExists`、コミットフェーズなし)。典拠: `SingleCrudOperationTransactionManager.java:70-350`。

---

## 6. 実行時にエラーになる操作制限(OperationChecker 群)

### 6.1 共通(`common/checker/OperationChecker.java`、いずれも `IllegalArgumentException`)

| 制約 | エラーコード | 典拠 |
|---|---|---|
| partition key 未指定/不正(完全一致必須) | `DB-CORE-0020` | `:461-466,486-519` |
| clustering key 不正 | `DB-CORE-0021` | `:468-484` |
| projection にない列 | `DB-CORE-0009` | `:179-187` |
| 空 mutation リスト | `DB-CORE-0018` | `:353-355` |
| mutation 型が Put/Delete 以外 | `DB-CORE-0126` | `:449-454` |
| **mutation が storage の atomicity unit を跨ぐ** | `0212`(RECORD)/`0019`(PARTITION)/`0213`(TABLE)/`0214`(NAMESPACE)/`0215`(STORAGE) | `:383-447` |
| 条件の型/値不一致 | `DB-CORE-0015` | `:285-350` |
| Put の列値不正 | `DB-CORE-0017` | `:330-337` |
| limit 負値 | `DB-CORE-0005` | `:139-141,157-160` |
| cross-partition scan 無効時の各違反 | `DB-CORE-0006/0007/0008` | `:146-177` |

> **1 トランザクション/バッチあたりの mutation 件数上限は core 共通層には存在しない**(件数制限は Dynamo の 100 のみ)。ただし atomicity unit を跨ぐ mutation は上表のとおり拒否される。

### 6.2 Consensus Commit 層(`ConsensusCommitOperationChecker.java`)

- tx メタデータ列(`tx_id`/`tx_state` 等)を projection/condition/ordering/mutation に指定禁止(`include_metadata.enabled=false` 時)。
- Put の condition は PutIf/PutIfExists/PutIfNotExists のみ、Delete は DeleteIf/DeleteIfExists のみ。
- **SERIALIZABLE で before-image index の無い secondary index get/scan を禁止**(`DB-CORE-0260`/`0261`。`repairTable()` で before-image index を作れば可)。
- SERIALIZABLE の cross-partition scan で index 列への条件禁止(`DB-CORE-0262`)。
- read-only transaction での mutation は `DB-CORE-0211`。

### 6.3 backend 固有

| backend | 制約 | エラー/典拠 |
|---|---|---|
| Cosmos | 主キー TEXT に `: / \ # ?` 不可 / BIGINT ±2^53 範囲 / BLOB の condition は EQ・NE・IS_NULL・IS_NOT_NULL のみ | `CosmosOperationChecker.java:37-189`(`DB-CORE-0076` 等) |
| DynamoDB | BOOLEAN の condition は EQ・NE・IS_NULL・IS_NOT_NULL のみ / index 列を空値に不可 / **1 バッチ 100 mutation 上限** | `DynamoOperationChecker.java:31-66`、`BatchHandler.java:71-73`(`DB-CORE-0082`/`0108`) |
| JDBC | cross-partition scan ordering の BLOB 列不可 / filtering 対象列のエンジン非対応判定 | `JdbcOperationChecker.java:26-35` |
| Object Storage | 主キー TEXT に区切り文字不可 / BIGINT ±2^53 範囲 / BLOB サイズ上限超で拒否 | `ObjectStorageOperationChecker.java:33-194`(`DB-CORE-0279` 等) |

---

## 7. 落とし穴(→ 90-deep-dives で詳細化)

1. **atomicity unit 跨ぎ mutation の拒否**: 跨げる範囲が storage 依存(RECORD/PARTITION/TABLE/NAMESPACE/STORAGE)で変わる(`OperationChecker.java:383-424`)。
2. **SERIALIZABLE で secondary index get/scan 禁止**(before-image index 必須、`repairTable()` が必要, `ConsensusCommitOperationChecker.java:92-172`)。§20 §6.4 の before_* index 性能問題と関連。
3. **Consistency はトランザクション CRUD で無視**され常に LINEARIZABLE。非トランザクションの `DistributedStorage` でのみ意味を持つ。
4. **READ_COMMITTED は非最新のコミット済みを返しうる**(read-only 最適化で before-image をそのまま返す)。
5. **recovery 期限 15000ms**(`RecoveryHandler.java:24` `TRANSACTION_LIFETIME_MILLIS=15000`)。`before_tx_state=PREPARED` が発生しない防御機構は 3.18.0 でも健在(既存資料 `before-tx-state-prepared-investigation.md` を再検証、行番号は要更新)。
6. **piggyback-begin / write-buffering**(Cluster クライアント)は `resume()`/`join()` を制限(→ 11)。BFF パターン非互換の可能性 — cluster ソースで 3.18.0 再検証(`piggyback-begin-restriction.md`)。
