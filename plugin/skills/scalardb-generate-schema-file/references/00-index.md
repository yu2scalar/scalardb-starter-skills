# ScalarDB 3.18.0 リファレンス — index

本ディレクトリは ScalarDB **3.18.0** における設定・データモデル・API/SQL の可否と制限を、
ソースコードを典拠にまとめた `scalardb-starter-skills` 用リファレンスである。

## 典拠バージョンと一次情報

| 対象 | 参照 | commit |
|---|---|---|
| Core (`scalardb`) | タグ `v3.18.0` | `1953f0131` |
| Cluster (`scalardb-cluster`) | タグ `v3.18.0` | `36cf410a` |
| 公式 Docs (`docs-scalardb`) | ブランチ `3.18` | `834dc2af` |

- 公式 Docs (Core 設定): `docs/configurations.mdx`, `docs/database-configurations.mdx`
- 公式 Docs (Cluster 設定): `docs/scalardb-cluster/scalardb-cluster-configurations.mdx`
- 公式 Docs (API): `docs/api-guide.mdx`, `docs/scalardb-sql/sql-api-guide.mdx`
- 公式 Docs (データモデル): `docs/data-modeling.mdx`, `docs/database-adapters.mdx`
- ソース典拠は working tree(master/main = 3.18 より先)ではなく、上記タグ/ブランチから取得する。
  引用の worktree: `scratchpad/worktrees/{scalardb-3.18.0, scalardb-cluster-3.18.0, docs-3.18}`。

> 注: 手元の各リポジトリの working tree は master/main(Core は 4.0.0-SNAPSHOT)であり、
> 3.18.0 より新しい。本リファレンスの `ファイル:行` 引用は必ず `v3.18.0` タグ /
> `3.18` ブランチ側の内容と一致していること。

## 軸ファイル一覧

| ファイル | 軸 | 状態 |
|---|---|---|
| `10-configuration-core.md` | Core 設定プロパティ(common / transaction manager / storage 別 / consensus commit) | ✅ 完了 |
| `11-configuration-cluster.md` | Cluster 設定プロパティ(node / gRPC / auth / ABAC / encryption / replication / embedding) | ✅ 完了 |
| `20-data-model-and-types.md` | データモデル・データ型・backend 別型マッピング | ✅ 完了 |
| `30-crud-api-and-limits.md` | CRUD/Transaction API と制限(scan / index / 分離レベル / 2PC) | ✅ 完了 |
| `40-sql-support-and-limits.md` | SQL DDL/DML の可否・制限・型対応・pushdown/OOM リスク | ✅ 完了 |
| `50-backend-feature-matrix.md` | ストレージ別 機能/制限マトリクス | ✅ 完了 |
| `60-error-codes.md` | status/error code カタログ(体系・カテゴリ→対処・頻出コード) | ✅ 完了 |
| `70-design-decisions-and-antipatterns.md` | 設計意思決定フロー + アンチパターン集(Skill が Q&A 化する素) | ✅ 完了(AP-1〜14) |
| `90-deep-dives.md` | Docs↔実装乖離・落とし穴・設計深掘り + Skill 設計への含意 | ✅ 完了 |

## 列の定義(設定プロパティ表・共通)

| 列 | 意味 |
|---|---|
| 設定項目 (プロパティ名) | プロパティの完全修飾名 |
| 説明 | プロパティの目的・挙動 |
| 既定値 | 未設定時に実効的に適用される値。`(空)` は無効/未指定相当。 |
| 備考 | 関連プロパティ・前提条件・特殊挙動・非推奨情報。`[公式 Docs 未掲載]` はソースのみで確認できる項目。 |
| Group | 適用条件(`base` / `option` / storage 名 / `consensus-commit` / `cluster` / `auth` / `tls` 等) |

## 対象範囲

- Core (OSS) + ScalarDB SQL + ScalarDB Cluster(gRPC / auth / ABAC / encryption / replication)。
- **Analytics(Spark 連携等)は範囲外**(将来の軸拡張余地として残す)。

## 関連

- 作業計画: [`../../plan-scalardb-reference-structure.md`](../../plan-scalardb-reference-structure.md)
- 横断 index: [`../README.md`](../README.md)
