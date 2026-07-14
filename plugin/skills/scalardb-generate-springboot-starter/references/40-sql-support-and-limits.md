# ScalarDB 3.18.0 リファレンス — SQL サポート範囲と制限

ScalarDB SQL(`scalardb-sql`)の DDL/DML 対応範囲、文法が受理する構文、そして
**どの処理が backend DB に pushdown され、どこからが ScalarDB 側で加工されるか**(= データ量・OOM リスク)を整理する。

## 典拠

- ソース: `scalardb-sql` タグ `v3.18.0`(commit `23d9b547`)。文法 `direct-mode/src/main/antlr/SqlParser.g4` / `SqlLexer.g4`、
  実行計画 `direct-mode/.../DmlStatementPlanner.java`・`.../volcanoiterator/*Operator.java`、`core/.../sql/statement/*`、`core/.../sql/common/SqlError.java`
- Docs: `docs-scalardb` ブランチ `3.18`。**SQL 構文の正典は `docs/scalardb-sql/grammar.mdx`**(`sql-api-guide.mdx` は Java SQL API の解説のみで構文詳細は無い)
- 下層のトランザクション挙動は `30-crud-api-and-limits.md`、型は `20-data-model-and-types.md` を参照
- 既存調査資料(3.18.0 で再検証済み): `scalardb-sql-feature-comparison*.md`, `group_by_feature_comparison_3_17.md`, `query_pushdown_feasibility_analysis_3_17.md`

> **設計の大前提**: ScalarDB SQL は「標準 SQL のサブセット + ScalarDB 固有の運用構文」。SELECT は**単一テーブル + 明示 JOIN の平坦なクエリ**に限定され、
> サブクエリ・UNION・DISTINCT・CTE・ウィンドウ関数・CASE・算術式・IN 句を**持たない**。式は「リテラルかバインドマーカ」まで削られている(§4)。

---

## 1. サポートする文(文法トップレベル)

`SqlParser.g4` の `sql` 規則が受理する文のカテゴリ。

| カテゴリ | 文 |
|---|---|
| DDL | CREATE/DROP NAMESPACE, CREATE/DROP TABLE, TRUNCATE TABLE, CREATE/DROP INDEX, CREATE/DROP/TRUNCATE COORDINATOR TABLES, ALTER TABLE(ADD/DROP/RENAME COLUMN, ALTER COLUMN TYPE, RENAME TO) |
| DML | INSERT, UPSERT, UPDATE, DELETE, SELECT |
| DCL | CREATE/ALTER/DROP USER, CREATE/DROP ROLE, GRANT/REVOKE(権限・ロール) |
| ABAC | ポリシー/レベル/コンパートメント/グループの CREATE/DROP/ENABLE/DISABLE 等(Enterprise) |
| トランザクション制御 | BEGIN / START TRANSACTION [READ ONLY\|WRITE], JOIN, SUSPEND, RESUME, PREPARE, VALIDATE, COMMIT, ROLLBACK, ABORT |
| セッション/メタ | USE, SET MODE(TRANSACTION\|TWO_PHASE_COMMIT_TRANSACTION), SHOW(NAMESPACES/TABLES/USERS/GRANTS/ROLES/ABAC…), DESCRIBE |

---

## 2. DDL

典拠: `SqlParser.g4` の各 DDL 規則、`core/.../sql/statement/*`、Docs `grammar.mdx`。

### CREATE TABLE

```
CREATE TABLE [IF NOT EXISTS] [ns.]table ( 列 型 [PRIMARY KEY] [ENCRYPTED], ... , PRIMARY KEY(...) )
   [WITH CLUSTERING ORDER BY (col ASC|DESC, ...) [AND オプション] | WITH オプション]
```

- 主キー定義は 3 形態: 単一列 `PRIMARY KEY (c1)` / `PRIMARY KEY (pk, ck1, ck2…)`(先頭が partition key)/ `PRIMARY KEY ((pk1,pk2…), ck1…)`(括弧内が複合 partition key)。
- **secondary index は CREATE TABLE には書けない**。別文 `CREATE INDEX ... ON t(col)`(**単一列のみ**)で作る。
- 列に `ENCRYPTED` を付けると暗号化列(Cluster encryption 有効時)。
- 型キーワードは 11 種のみ: `BOOLEAN/INT/BIGINT/FLOAT/DOUBLE/TEXT/BLOB/DATE/TIME/TIMESTAMP/TIMESTAMPTZ`(`DataType.java`, `SqlLexer.g4:732-780`)。`VARCHAR/DECIMAL/CHAR/NUMERIC/UUID/JSON` 等は無い。

### ALTER TABLE の制限(重要)

SQL 層は構文を受理し core-lib へ委譲するだけで、可否は下層で決まる(Docs `grammar.mdx` の note が典拠):

| ALTER | 制限 |
|---|---|
| ADD COLUMN | 大きなテーブルで時間がかかる警告のみ |
| ALTER COLUMN TYPE | **最も制限が強い**。PK/CK/index key 列は不可。非 JDBC・SQLite は不可。許可される変換は **INT→BIGINT / FLOAT→DOUBLE / 任意→TEXT** のみ(Oracle は INT→BIGINT のみ、Db2/TiDB は BLOB→TEXT 不可) |
| DROP COLUMN | PK/CK 列不可。Cassandra を除く非 JDBC は不可 |
| RENAME COLUMN | Cassandra を除く非 JDBC 不可。Cassandra は PK/CK 列のみ。Db2 は PK/CK/index key 不可 |
| RENAME TO | 非 JDBC は不可 |

→ backend 別の詳細は `20-data-model-and-types.md` §5 とも整合。

---

## 3. DML

### 3.1 INSERT / UPSERT / UPDATE / DELETE

| 文 | 構文 | 制約 |
|---|---|---|
| INSERT | `INSERT INTO t [(列…)] VALUES (式…)[, (式…)]…` | **WHERE なし**。全 primary key 列の指定必須。値は**リテラル/バインドマーカのみ**(式演算不可)。`INSERT ... SELECT` は無い |
| UPSERT | 同上(`UPSERT INTO`) | 全 primary key 列必須。存在すれば更新 |
| UPDATE | `UPDATE t SET 代入… [WHERE] [WITH …]` | WHERE 任意。SET の値もリテラル/バインドマーカ |
| DELETE | `DELETE FROM t [WHERE] [WITH …]` | WHERE 任意 |

- UPDATE/DELETE の WHERE は SELECT と同じスキャン方式で対象行を特定(§6)。全 PK 指定を強制せず、条件次第でクロスパーティションスキャンにフォールバック。

### 3.2 SELECT の句

```
SELECT projections FROM t [AS a] [JOIN …] [WHERE …] [GROUP BY …] [HAVING …] [ORDER BY …] [LIMIT …] [WITH …]
```

| 句 | 書ける範囲 |
|---|---|
| projection | `*` / `列 [AS 別名]` / `関数(...) [AS 別名]` |
| WHERE | 演算子 `= <> != < > <= >=`、`BETWEEN..AND`、`[NOT] LIKE [ESCAPE]`、`IS [NOT] NULL`。**DNF または CNF のみ**(自由ネスト不可、混在時は括弧必須)。述語対象は**列のみ**(関数は `FUNCTION_NOT_ALLOWED_IN_WHERE`=0096)。暗号化列は不可(0071) |
| JOIN | INNER / LEFT [OUTER] / RIGHT [OUTER] の**3 種のみ**(FULL/CROSS/NATURAL なし)。§3.3 |
| GROUP BY | **列参照の列挙のみ**(式・別名・序数不可) |
| HAVING | DNF/CNF。キーは列/集約関数/別名(projection に含まれる必要) |
| ORDER BY | 列 or 関数に `ASC`/`DESC`(省略時 ASC)。任意式は不可、OFFSET は無い |
| LIMIT | 数値リテラルまたはバインドマーカ(OFFSET/FETCH 無し) |

### 3.3 JOIN の制約

- **等値結合のみ**: `ON 列 = 列`(不等号・OR・リテラル比較不可、複数述語は AND のみ)。
- 結合述語は結合先の**全 primary key 列 または 単一の secondary index 列**を含む必要(`PRIMARY_KEY_COLUMNS_OR_INDEX_COLUMN_NOT_SPECIFIED_IN_JOIN_PREDICATES`=0067)。
- **RIGHT OUTER JOIN は最初の JOIN にしか置けない**(0063)。
- アルゴリズムは **Index Nested-Loop Join のみ**(`DmlStatementPlanner.java:1094-1123`)。
- **JOIN 時、WHERE / ORDER BY は FROM のベーステーブル列しか使えない**(結合先列は `COLUMN_NOT_ALLOWED`=0042)。projection と GROUP BY は結合先列可。

### 3.4 集約関数

- 登録されているのは **`COUNT` / `SUM` / `MIN` / `MAX` / `AVG` の 5 種のみ**(`FunctionRegistry.java:33-37`)。文法は任意名 `func(...)` を受理するが、未登録名は実行時に `UNKNOWN_FUNCTION`(0083)。
- **`DISTINCT` は文法に無い** → `COUNT(DISTINCT x)` はパース不可。
- `*` 引数は `COUNT` のみ。型: COUNT→BIGINT、SUM(数値のみ)→BIGINT/DOUBLE、AVG→DOUBLE、MIN/MAX→同型(BLOB 不可)。
- GROUP BY 無しで集約関数だけ使うと「全行を 1 グループ」の暗黙集約。SELECT の非集約列は GROUP BY に含める必要(`COLUMN_NOT_IN_GROUP_BY`=0082)。

---

## 4. 文法が「持たない」もの(標準 SQL 比)

`SqlParser.g4`/`SqlLexer.g4` 全体を精査し、規則が**存在しないこと**を確認した(パースエラーになる):

| 機能 | 状態 |
|---|---|
| サブクエリ / CTE(WITH) | ❌ 無い(FROM は単一 tableRef + JOIN のみ) |
| UNION / INTERSECT / EXCEPT | ❌ 無い |
| DISTINCT | ❌ 無い |
| ウィンドウ関数(OVER/PARTITION BY) | ❌ 無い |
| CASE 式 | ❌ 無い |
| 算術式(`+ - * /`)・式演算・列同士比較 | ❌ 無い(WHERE 右辺は `literal`/`bindMarker` のみ。列=列は JOIN の ON だけ) |
| IN (...) 述語 | ❌ 無い |
| EXISTS 述語 | ❌ 無い |
| OFFSET / FETCH | ❌ 無い(ページングは LIMIT のみ) |
| FULL OUTER / CROSS JOIN / USING 句 | ❌ 無い |
| VIEW / SEQUENCE / TRIGGER / PROCEDURE | ❌ 無い |
| INSERT ... SELECT | ❌ 無い(VALUES 固定) |
| CAST / 型変換関数 / NULLS FIRST / COLLATE | ❌ 無い |

**リテラル**: 文字列 `'...'`(`''` でエスケープ、ダブルクォート不可)、整数/小数(指数表記なし)、`TRUE`/`FALSE`、**BLOB `X'hex'`**(3.18 で追加)、`NULL`、バインドマーカ `?` / `:name`。日付時刻専用リテラル(`DATE '...'`)は無く、文字列/バインドマーカで渡す。

---

## 5. pushdown vs ScalarDB 側処理(データ量・OOM リスク)★重要

**どの処理を backend DB がやり、どこからを ScalarDB(クライアント/volcano iterator)がやるか**を理解しないと、
軽いつもりのクエリで全件を DB から吸い上げ、OOM や DB 過負荷を起こす。典拠: `DmlStatementExecutor.java:74-111`, `DmlStatementPlanner.java:195-210,587-589`, `ScanOperator.java`, `ConsensusCommitUtils.java`。

| 句 / 処理 | 実行場所 | データ量リスク |
|---|---|---|
| WHERE(**キー等値**条件) | **pushdown**(scan 種別が GET/SCAN/SCAN_WITH_INDEX に絞られる, §6) | 低 |
| WHERE(**非キー / 範囲 / LIKE / != / IS NULL**) | 一部フィルタとして storage へ、多くは **SCAN_ALL(全走査)+ フィルタ** | **高** |
| JOIN | **クライアント側**(Index Nested-Loop Join) | 中〜高(結合先を都度 lookup) |
| GROUP BY / 集約(COUNT等) | **クライアント側**(`AggregationOperator`) | **高**(集約対象の全行をクライアントに取得 → OOM 懸念) |
| HAVING | **クライアント側**(`HavingOperator`) | 集約と同じ |
| ORDER BY | **クライアント側**(`OrderingOperator`) | 中〜高(ソート対象を全取得) |
| LIMIT(JOIN/GROUP BY/集約が**無い**) | pushdown(SQL プラン上) | 低。ただし下記 CC 注意 |
| LIMIT(JOIN/GROUP BY/集約が**有る**) | **クライアント側**(`clientSideLimit`)。**backend には LIMIT が渡らず全件取得**してからメモリで切る | **極めて高** |
| DB 固有関数 / 式評価 | 非対応(集約 5 種以外の関数は使えない) | — |

**さらに Consensus Commit 経由の重要な注意**(`30` と連動):
- 非キーの WHERE 条件は before-image 列との OR に展開され、storage は「現在値一致 + before-image 一致の PREPARED 行」を**広めに返し**、最終フィルタは ScalarDB がクライアント側で行う(`ConsensusCommitUtils.java`, `CrudHandler`)。→ **「WHERE を付けたのに速くならない / 全件読む」の根因**。
- **CC は storage への LIMIT を 0(無効)にして全件取得**する(before-image 展開・recovery で行が除外され得るため, `ConsensusCommitUtils.java:420-424`)。SQL で SCAN/GET に LIMIT を積んでも、backend 取得は LIMIT 無しになる。

> **初心者への指針(Skill が警告すべき)**: 「集約・JOIN・ORDER BY・非キー WHERE は "全部クライアントに持ってくる" 可能性がある」と考える。
> 大きなテーブルでこれらを使うなら、まずキー設計で対象行数を絞れているかを確認する。**LIMIT は保険にならない**(上記)。
> → `70-design-decisions-and-antipatterns.md` AP-4(cross-partition scan の常用)と直結。

---

## 6. WHERE → scan 種別の決定ロジック

`DmlStatementPlanner.createScanInfo()`(`:602-633`)が WHERE から scan 種別を選ぶ。**等値条件だけが key/index に昇格**する(`PredicatesMap.containsSameEqualityConditionOnlyOnce`, `:140-145`)。

| 条件 | 選ばれる scan | 速度 |
|---|---|---|
| partition key 全列 `=` + clustering key 全列 `=` | `GET` | 最速(1 件) |
| partition key 全列 `=`(+ clustering 範囲/順序) | `SCAN`(単一パーティション) | 速い |
| ORDER BY 無し + index 列 `=`(単一列) | `SCAN_WITH_INDEX` | 中 |
| 上記いずれも不成立(範囲のみ / 非キー / `OR` で別値 / 複合 index / index+ORDER BY 等) | **`SCAN_ALL`**(全走査 + フィルタ) | **遅い** |

- **clustering key の範囲**は「partition key を `=` で完全指定して `SCAN` になった時だけ」境界として効く(`ScanOperator.java:357-465`)。
- `WHERE pk=1 OR pk=2`(値が異なる)は「一意指定」と見なされず `SCAN_ALL`。
- index 列 + ORDER BY 併用は `SCAN_WITH_INDEX` 無効化 → `SCAN_ALL`。

---

## 7. SQL 特有のハマりどころ(初心者向け)

1. **範囲条件は key/index 検索にならない** — `pk > 10`・index 列への `>=`/BETWEEN/LIKE/`!=` は全て `SCAN_ALL`+フィルタ。key/index に昇格するのは `=` のみ(`PredicatesMap.java:140-145`)。
2. **clustering 範囲は partition key を `=` で完全指定した時だけ効く**。していないと `SCAN_ALL`。
3. **index scan と ORDER BY は併用不可**(`SCAN_WITH_INDEX` が無効化, `DmlStatementPlanner.java:805-810`)。
4. **`OR` で partition key に別値を並べると全件スキャン**。
5. **複合 index は使えない(単一列 index のみ)**。
6. **JOIN 条件は `=` のみ + 結合先の全 PK か単一 index 列が必須**(0064〜0067)。
7. **JOIN 時、WHERE/ORDER BY は FROM 表の列のみ**(結合先列は 0042)。
8. **RIGHT OUTER JOIN は最初の JOIN のみ**(0063)。
9. **JOIN/GROUP BY/集約があると LIMIT はクライアント側** — backend は全件取得(§5)。大テーブルで危険。
10. **GROUP BY/集約/HAVING/JOIN/ORDER BY はすべてクライアント側実行**(DB へ pushdown されない, §5)。
11. **非キー WHERE はフィルタでしかなく、CC 経由では storage が広めに返し LIMIT も無効化**(§5)。
12. **GROUP BY/集約は列参照のみ・DISTINCT/式/別名/序数不可、SUM/AVG は数値限定、MIN/MAX は BLOB 不可**。
13. **IN 句・サブクエリ・UNION・CTE・ウィンドウ関数・CASE・算術式は文法自体に無い**(パースエラー)。

---

## 8. その他(DCL / ABAC / トランザクション制御 / 型)

- **DCL**: `CREATE/ALTER/DROP USER`、`CREATE/DROP ROLE`、`GRANT/REVOKE`(権限 SELECT/INSERT/UPDATE/DELETE/CREATE/DROP/TRUNCATE/ALTER/GRANT OPTION/ALL、ロール)。認証方式は 3.18 で `USERPASS`/OIDC に対応(→ `11-configuration-cluster.md` §3、`docs/scalardb-cluster/scalardb-auth-with-sql.mdx`)。
- **ABAC**(Enterprise): ポリシー/レベル/コンパートメント/グループの管理 SQL(→ `11` §4)。
- **トランザクション制御**: `BEGIN`/`START TRANSACTION [READ ONLY|WRITE]`、2PC 用 `JOIN`/`SUSPEND`/`RESUME`/`PREPARE`/`VALIDATE`、`COMMIT`/`ROLLBACK`/`ABORT`、`SET MODE`。意味は `30-crud-api-and-limits.md` §5 と同じ。
- **型**: 11 種(§2)。SQL 上の値はリテラル/バインドマーカで渡す。型↔backend マッピングは `20-data-model-and-types.md` §3。

---

## 9. 3.17 → 3.18 の差分(SQL、記録)

機能可否マトリクスは 3.17 → 3.18 で**変化なし**。内部構造・新構文の追加のみ:
- 集約情報の解決を planning フェーズへ移動(バグ 2 件修正、#934)。ORDER BY/HAVING を独立 operator 化(#938)。
- **BLOB リテラル `X'hex'` 追加**(#1001)、TIMESTAMPTZ リテラルの `Z` 前空白が任意化(#974)。
- `BEGIN`/`START TRANSACTION` に WITH 属性・ABAC タグ(#1066, #1090)、`CREATE/ALTER USER` に認証方式(`USERPASS`/OIDC、#1064/#1093)。
- JOIN lookup の batch 化(#1042 等)で INLJ の N+1 が緩和。
> 既存資料(`*_3_17.md` 等)の可否記述は 3.18.0 でも実質正しいが、**引用行番号とモジュールパス(`scalardb-sql/direct-mode/…` は誤り、正は `direct-mode/…`)は要更新**。
