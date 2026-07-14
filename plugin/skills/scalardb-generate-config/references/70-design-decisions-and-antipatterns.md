# ScalarDB 3.18.0 リファレンス — 設計意思決定フローとアンチパターン集

このファイルは他の軸(10〜60)の事実を、**Skill が「意思決定の瞬間に能動的に誘導・警告する」ための素**に
再構成したもの。`scalardb-generate-schema` / `scalardb-validate-config` / `scalardb-generate-config` が、
以下のフローを **Q&A** や **検査ルール** に変換して使うことを想定する。

> **なぜこの軸が要るか**: リファレンスを「置いておくだけ」では初心者は救えない(読まない・読んでも判断できない)。
> 頻度が高く・コストが大きいハマりどころ(range×index、cross-partition scan の性能、Isolation vs Consistency、キー設計)を、
> **コードを書く前の設計時点で潰す**ための能動的ガイドが必要。各判断は他ファイルのソース典拠に紐づく。

## 典拠と状態

- 典拠: 各項は `20`〜`60`(いずれも `v3.18.0` ソース裏取り済み)を参照。
- **本ファイルの状態**: 全軸(20〜60)反映済み。アンチパターンは AP-1〜14。

---

## Part A. 設計意思決定フロー(Skill が Q&A 化する)

各フローは「ユーザに聞く質問 → 分岐 → 推奨/警告」の形。Skill はこれをそのまま対話に使える。

### A1. バックエンド(storage)の選択

```
Q: どの DB を使いますか / 使える環境ですか?
├─ リレーショナル DB が使える(MySQL/PostgreSQL/Oracle/SQL Server/Db2 等)
│    → JDBC 推奨。cross-partition scan の ordering が使える唯一のバックエンド(30 §4.2)。
│      range・複雑な条件が多いアプリに向く。
├─ マネージド NoSQL 前提(AWS/Azure/GCP)
│    → DynamoDB / Cosmos DB / Cassandra。ただし NoSQL 共通の制約に注意(下記警告)。
└─ とりあえず試す / 単一ノード
     → JDBC(SQLite/PostgreSQL)か Cassandra が始めやすい。
```
警告として Skill が出すべき点:
- **DynamoDB/Cosmos/Cassandra は cross-partition scan で ordering 不可**(JDBC のみ可, `30 §4.2`)。
- **Cassandra は `TIMESTAMP`(TZ なし)型が使えない**(`20 §3.1`)。
- **BLOB をキーに使う制約が backend で違う**(Cosmos/Dynamo は clustering key に BLOB 不可、Oracle/Db2 は BLOB キー不可, `20 §5`)。
- Object Storage(S3/Blob/Cloud)は **secondary index を一切張れない**(`20 §5`)。3.18 では **Private Preview**。
- 詳細な機能差(原子性単位・secondary index・import・ALTER・backup 方式・対応バージョン)は [`50-backend-feature-matrix.md`](./50-backend-feature-matrix.md) を参照。
- 選定の要点: **DynamoDB は 1 バッチ 100 mutation 上限**、**SQLite は開発用途のみ(import/並行不可)**、**TiDB は SERIALIZABLE 非対応**、**Spanner は rename 不可**、**Cassandra は TIMESTAMP 不可**、**NoSQL は運用でバックアップ pause 必須**(`50 §1,§3`)。

### A2. トランザクション方式の選択

```
Q: 何を保証したいですか?
├─ 複数レコード/複数テーブルをまとめて整合的に更新したい
│    → DistributedTransaction(consensus-commit)。既定で最も一般的(30 §1)。
├─ 複数のサービス/アプリをまたいで整合性を取りたい(マイクロサービス)
│    → TwoPhaseCommitTransaction(2PC)。ただし group commit と併用不可(30 §5.3)。
├─ 1 レコードの単発操作しかしない(高スループット重視)
│    → single-crud-operation。begin 不要で軽量だが複数操作は不可(30 §5.4)。
└─ トランザクション保証は不要、最速の生アクセスでよい
     → DistributedStorage(非トランザクション)。ACID なし、単一パーティション内のみ原子的(30 §1,§2.3)。
```
警告:
- **迷ったら consensus-commit**。single-crud / storage は「制約を理解した上での最適化」で、初心者の既定にしない。
- 2PC を選んだら group commit(`scalar.db.consensus_commit.coordinator.group_commit.enabled`)を有効にしない(起動失敗, `30 §5.3`)。

### A3. 分離レベル(Isolation)の選択

```
Q: 同時実行の正しさをどこまで求めますか?(→ トランザクション使用時のみ。Consistency とは別物, 30 §5.2)
├─ 厳密な直列化が必要(在庫・残高など競合が致命的)
│    → SERIALIZABLE。検証フェーズで anti-dependency を検出(コスト高)。
├─ 一般的な業務アプリ(既定でよい)
│    → SNAPSHOT(既定)。read skew / write skew は起こり得ることを理解して使う。
└─ 読み取り主体で最新性より速度優先
     → READ_COMMITTED。非最新のコミット済みを返し得る(30 §5.2)。
```
警告:
- **Isolation(トランザクション)と Consistency(単一操作・非トランザクション)を混同しない**(`30 §5.2` の対比表)。トランザクションを使う限り意識するのは Isolation だけ。
- SERIALIZABLE では **before-image index の無い secondary index get/scan が拒否**される(`30 §6.2`)。index を使うテーブルは `repairTable()` 済みか確認。

### A4. スキーマ設計(アクセスパターン起点)— 最重要

RDB 感覚の「まずテーブル、index は後で」を**やめさせる**。先に「どう読むか」を確定してキーを決める。

```
Q1: このデータを主にどう検索しますか?(1 テーブルにつき主要アクセスパターンを列挙)
Q2: 各パターンについて —
    ├─ 単一キーでピンポイント取得(1 件)
    │    → partition key(+ clustering key)で表現。
    ├─ ある範囲でまとめて取得(例: 日付範囲、ID 範囲、ソートして先頭 N 件)
    │    → その列を clustering key にする(範囲・ソートは clustering key のみ, 30 §4.1/§4.3)。
    │      partition key は「範囲の外側の絞り込み軸」に置く(範囲検索は partition key 完全指定が前提)。
    ├─ partition key 以外の 1 列で等値検索(範囲でない)
    │    → その列に secondary index(等値専用・単一列, 30 §4.3)。
    └─ partition を跨いで全体を条件検索
         → cross-partition scan(ScanAll)。ただし OLTP の常用は避ける(下記アンチパターン AP-4)。
Q3: 範囲検索したい列を secondary index にしようとしていないか?
    → ✋ 警告: secondary index は範囲不可・等値専用。範囲は clustering key で(30 §4.3 の「なぜ」)。
Q4: 複数の列条件で速く引きたい?
    → ✋ 警告: 1 検索で index に使えるのは 1 列だけ。複合条件はキー設計(複合 partition/clustering key)で。
```
Skill が確定すべき出力: partition key(複数可・順序保持)、clustering key(順序 + ASC/DESC)、secondary index(等値したい単一列のみ)、各列の型(`20 §2`、backend 制約 `20 §5`)。

- **SQL で同じ設計を表現する場合**: CREATE TABLE の `PRIMARY KEY((pk…), ck…)` + `WITH CLUSTERING ORDER BY` でキーを、`CREATE INDEX`(単一列)で index を定義。範囲検索は clustering key、等値のみ index、という振り分けは SQL でも同じ(→ [`40-sql-support-and-limits.md`](./40-sql-support-and-limits.md) §2,§6)。JOIN/集約/ORDER BY を使う設計は pushdown されずクライアント側になる点(OOM)も考慮(`40 §5`)。

---

## Part B. アンチパターン集

各項: **症状 / なぜダメか / 正しい代替 / 典拠**。Skill は「ユーザの入力がこの形なら警告」に使う。

### AP-1. 範囲検索したい列を secondary index にする
- **症状**: 「`created_at` で範囲検索したいから index を張る」。
- **なぜダメ**: secondary index は等値専用・範囲不可(`ScanWithIndex.withStart/withEnd` は例外)。NoSQL の index はソート構造でない(Dynamo GSI は HASH のみ、Cassandra 2i は等値専用)。
- **正しい代替**: `created_at` を **clustering key** にする(範囲・ソートは clustering key のみ)。
- 典拠: `30 §4.3`、`20 §5`。

### AP-2. 「とりあえず作って index は後で足す」(RDB 習慣)
- **症状**: アクセスパターンを決めずにテーブルを作り、遅くなってから index を追加。
- **なぜダメ**: ScalarDB(特に NoSQL)は index の万能性が低く(等値・単一列・トランザクションでの before-image 変換)、後付けでは救えないことが多い。
- **正しい代替**: **アクセスパターン起点**でキー設計(A4)。range はキー、等値ピンポイントのみ index。
- 典拠: `30 §4.3`。

### AP-3. 複数条件を index で高速化しようとする
- **症状**: 「`status` と `income` 両方に index を張れば複合検索が速い」。
- **なぜダメ**: 1 検索で index 検索キーになるのは 1 列だけ。2 列目以降は where フィルタになり、トランザクション経由では `(col OR before_col)` に化けて index が効きにくい。
- **正しい代替**: 主要な複合条件は複合 partition/clustering key で表現。どうしても必要なら手動インデックステーブル(AP-8)。
- 典拠: `30 §4.3` ハマりどころ 3〜4。

### AP-4. cross-partition scan を OLTP の常用パスにする
- **症状**: partition key を指定せず ScanAll + filtering を通常の検索に多用。
- **なぜダメ**: index range ではなく全体スキャン + フィルタ。件数増で線形に遅くなる。非 JDBC では ordering 不可、SERIALIZABLE 保証も弱まる(`30 §4.2`)。既定で `filtering`/`ordering` は無効(明示的に有効化が要る)。
- **正しい代替**: アクセスパターンに合わせた partition/clustering key。分析系の範囲集計は ScalarDB Analytics へ(範囲外)。
- 典拠: `30 §4.2`、`10`(cross_partition_scan 設定)。

### AP-5. Isolation と Consistency の混同
- **症状**: 「Consistency を LINEARIZABLE にすればトランザクションが厳密になる」と思う。
- **なぜダメ**: 別レイヤ。トランザクション CRUD では Consistency は無視され常に LINEARIZABLE。同時実行の正しさは Isolation で決める。
- **正しい代替**: トランザクションでは Isolation(SNAPSHOT/SERIALIZABLE/READ_COMMITTED)を設定。Consistency は非トランザクション `DistributedStorage` のときだけ意味を持つ。
- 典拠: `30 §5.2` 対比表、`30 §3.4`。

### AP-6. NoSQL で列名を省略した INSERT
- **症状**: `INSERT ... VALUES (v1, v2, ...)` と値だけで挿入。
- **なぜダメ**: NoSQL(Cosmos/Dynamo)は非キー列の順序が不定(`20 §6.3`)。値の割り当てがずれ得る。
- **正しい代替**: **列名を明示**して挿入。
- 典拠: `20 §6.3`。

### AP-7. 単一トランザクション/バッチで別パーティションをまたいで mutate する(storage 前提の誤解)
- **症状**: `DistributedStorage.mutate`/`put(List)` で複数パーティションをまとめて原子的に更新できると思う。
- **なぜダメ**: 非トランザクションの原子性は storage の atomicity unit(RECORD/PARTITION/…)内のみ。跨ぐと `MultiPartitionException` 等で拒否。Dynamo は 1 バッチ 100 件上限。
- **正しい代替**: 複数パーティション/テーブルの原子性が要るなら **DistributedTransaction** を使う。
- 典拠: `30 §2.3`、`30 §6.1/§6.3`。

### AP-8. 「手動インデックステーブル」を非トランザクションで二重書き
- **症状**: 検索用の別テーブルを作り、本体と別々に(非トランザクションで)書く。
- **なぜダメ**: 本体と検索テーブルの整合性が壊れ得る。
- **正しい代替**: 手動インデックステーブルは有効な手段だが、**必ず同一 DistributedTransaction 内で本体と一緒に書く**(ScalarDB が整合性を保証)。
- 典拠: `30 §1`(トランザクションの原子性)。

### AP-9. Insert/Upsert に条件や Consistency を付けようとする
- **症状**: `insert(...).withCondition(...)` のように書く。
- **なぜダメ**: `Insert`/`Upsert` は condition も consistency も持てない(呼ぶと `UnsupportedOperationException`)。
- **正しい代替**: 条件付きにしたいなら `Put`(+ `PutIf*`)または `Update`(+ `UpdateIf*`)を使う。
- 典拠: `30 §2.2`。

### AP-10. 集約 / JOIN / ORDER BY / 非キー WHERE を大きなテーブルに投げる(OOM 直結)
- **症状**: `SELECT COUNT(*) ... WHERE 非キー列 ...` や JOIN・ORDER BY を、行数の多いテーブルに素で投げる。
- **なぜダメ**: GROUP BY / 集約 / HAVING / JOIN / ORDER BY は **すべてクライアント側実行**で DB に pushdown されない。対象行を全部クライアントに取得してから処理するため OOM/過負荷になる。
- **正しい代替**: まずキー設計で対象行数を絞る。集計は事前集約テーブル or ScalarDB Analytics(範囲外)。安易な全件集約をしない。
- 典拠: `40 §5`(`DmlStatementExecutor.java:74-111`)。

### AP-11. LIMIT を「重いクエリの保険」にする
- **症状**: 「LIMIT 10 を付けたから全件は読まないだろう」と考える。
- **なぜダメ**: JOIN/GROUP BY/集約があると LIMIT はクライアント側適用で **backend は全件取得**。さらに Consensus Commit は storage への LIMIT を無効化(0)して全件取得する。
- **正しい代替**: LIMIT に頼らず、キー(partition/clustering)で物理的に取得範囲を絞る。
- 典拠: `40 §5`(`ConsensusCommitUtils.java:420-424`, `DmlStatementPlanner.java:204-210`)。

### AP-12. 範囲 / OR / 非等値で key・index が効くと思い込む
- **症状**: `WHERE pk > 100` や `WHERE pk=1 OR pk=2`、index 列への範囲で速いと期待。
- **なぜダメ**: key/index に昇格するのは **等値(`=`)のみ**。範囲・OR(別値)・BETWEEN・LIKE・`!=` は `SCAN_ALL`(全走査)+フィルタに落ちる。
- **正しい代替**: 範囲は clustering key(partition key を `=` 完全指定)で。等値ピンポイントのみ index。
- 典拠: `40 §6`(`PredicatesMap.java:140-145`)、`30 §4.3`。

### AP-13. 非対応 SQL 構文を書く
- **症状**: サブクエリ / UNION / DISTINCT / CASE / IN / OFFSET / 算術式 / ウィンドウ関数を使う。
- **なぜダメ**: これらは文法自体に無くパースエラー。
- **正しい代替**: アプリ側で分解する、または対応構文(平坦な SELECT + 明示 JOIN + 5 集約関数)に書き換える。
- 典拠: `40 §4`。

### AP-14. 要件と backend 制約の不一致(選定ミス)
- **症状**: 「本番で SQLite」「TiDB で SERIALIZABLE 前提」「DynamoDB で 1 トランザクション 100 件超の mutation」「Cassandra で TIMESTAMP 列」「範囲/ソート主体なのに Object Storage」など。
- **なぜダメ**: いずれも backend 固有の制約に抵触(SQLite は開発用途・import 不可、TiDB は SERIALIZABLE 非対応、DynamoDB は 1 バッチ 100 上限、Cassandra は TIMESTAMP 不可、Object Storage は secondary index 不可)。
- **正しい代替**: 要件(分離レベル・トランザクション件数・型・index・range・運用/バックアップ)を先に確定し、`50` のマトリクスと突き合わせて backend を選ぶ。generate-config が Q&A で誘導。
- 典拠: `50-backend-feature-matrix.md` §1,§2,§4。

---

## Part C. Skill への接続(実装メモ)

- **generate-schema**: Part A4 を Q&A 化。ユーザ回答から partition/clustering key・index・型を決定し、AP-1〜AP-3・AP-6 を入力バリデーションとして警告。
- **generate-config**: Part A1〜A3 を Q&A 化。backend・トランザクション方式・Isolation を決め、`10/11` のプロパティに落とす。
- **validate-config**: AP-5(Isolation/Consistency 混同)、A2 の group commit×2PC 併用、SERIALIZABLE×index 前提などを検査ルール化。
- **analyze-errorlog**: `60` のエラーコードと本ファイルのアンチパターンを紐づけ、エラー → 該当アンチパターン → 対処を提示。
