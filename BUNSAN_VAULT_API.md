# BUNSAN_VAULT_API.md — 外部アプリを分散片の保管先にする契約

契約バージョン **v1.0**

BunsanApp（分散保管）が作った片ファイルを、他のアプリのアプリ内ストレージへ保存できるようにするための取り決め。
最初の実装対象は **KakuregaApp（カクレガ）**。

この文書は BunsanApp リポジトリと KakuregaApp リポジトリの両方に置く。
**実装の所有者はカクレガ側**（提供する側）。BunsanApp 側はこの契約を読むだけで、
カクレガ専用のコードは一切持たない。

---

## 1. 何を実現するか

BunsanApp の「片の保存先を選ぶ」画面（`ACTION_CREATE_DOCUMENT`）に、
カクレガの収納が**普通の保存先として並ぶ**ようにする。

```
BunsanApp: 片を作る → 保存先を選ぶ
                      ↓ SAFのピッカー
                      ├ ダウンロード
                      ├ Google Drive
                      └ カクレガ            ← これを増やす
                        └ 資料室の金庫（PIN錠）
```

**BunsanApp 側は改修不要。** SAF の標準機能なので、カクレガが `DocumentsProvider` を
実装した時点で、既存のアプリからそのまま見えるようになる。

---

## 2. カクレガ側で実装するもの

### 2.1 マニフェスト

```xml
<provider
    android:name=".VaultDocumentsProvider"
    android:authorities="com.appathy.kakurega.documents"
    android:exported="true"
    android:grantUriPermissions="true"
    android:permission="android.permission.MANAGE_DOCUMENTS">
    <intent-filter>
        <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
    </intent-filter>
</provider>
```

- `android:permission` は**要求する権限ではなく、呼び出し側に課す条件**。
  システムの DocumentsUI だけがバインドできる。カクレガが新たに権限を要求するわけではない
- `android.provider.DocumentsProvider` はフレームワーク標準（API 19以降）。
  **androidx 依存は増えない**

### 2.2 実装するメソッド

| メソッド | 必須 | 内容 |
|---|---|---|
| `queryRoots` | ○ | ルート1件。`FLAG_SUPPORTS_CREATE` を立てる |
| `queryChildDocuments` | ○ | 公開収納の一覧、および収納内のファイル一覧 |
| `queryDocument` | ○ | 単体のメタデータ |
| `createDocument` | ○ | 収納内に空ファイルを作る |
| `openDocument` | ○ | 書き込み（`w`）と読み出し（`r`） |
| `deleteDocument` | 任意 | あると BunsanApp から片を消せる |

### 2.3 document_id の設計

```
root                     ルート
slot:<slotId>            収納（ディレクトリとして見せる）
file:<fileId>            片ファイル
```

`fileId` は既存の `Db.files` の主キーをそのまま使う。実体は `filesDir/vault/` の
ランダム名ファイルなので、パスは外に出さない。

### 2.4 返す列

**Root**: `ROOT_ID` / `DOCUMENT_ID`（`root`）/ `TITLE`（"カクレガ"）/ `FLAGS`（`FLAG_SUPPORTS_CREATE`）/ `ICON` / `MIME_TYPES`（`*/*`）

**Document（収納）**: `DOCUMENT_ID` / `DISPLAY_NAME` / `MIME_TYPE`（`Document.MIME_TYPE_DIR`）/ `FLAGS`（`FLAG_DIR_SUPPORTS_CREATE`）

**Document（ファイル）**: `DOCUMENT_ID` / `DISPLAY_NAME` / `MIME_TYPE`（`application/octet-stream`）/ `SIZE` / `LAST_MODIFIED` / `FLAGS`（`FLAG_SUPPORTS_DELETE` など）

---

## 3. 公開範囲の規則（重要）

### 3.1 隠しスポットは絶対に出さない

カクレガの隠しスポットを SAF ピッカーに並べると、**隠し場所の一覧を外に配ることになる**。
アプリの前提そのものが壊れる。

### 3.2 収納ごとの明示的な公開フラグ

`SlotDef` に **`shareVault: Boolean`（既定 false）** を追加し、
**これが true の収納だけ**を `queryChildDocuments` で返す。
増築モードの収納編集画面に「分散片の保管先として公開する」トグルを置く。

### 3.3 錠のかかった収納の扱い

| 操作 | 施錠中 | 解錠中 |
|---|---|---|
| 収納をピッカーに表示 | する | する |
| 中身の一覧（`queryChildDocuments`） | **空を返す**（ファイル名を漏らさない） | 通常どおり |
| 新規作成（`createDocument`） | **許可する** | 許可する |
| 読み出し（`openDocument` の `r`） | **拒否する** | 許可する |

**「郵便受け」の意味づけ**にする。入れることはできるが、開けるには錠を解く必要がある。

一覧が空を返すため同名衝突を検出できない。**衝突時は自動で連番を付けて別名にする**こと。

### 3.4 表示名に錠の種類を含める

```
資料室の金庫（PIN錠）
床下の箱（二台目端末）
本棚のうしろ（錠なし）
```

BunsanApp 側で独立性グループを人間が割り当てるときの判断材料になる。
**カクレガが独立性グループを決めるわけではない。**（4節）

---

## 4. 独立性グループの扱い（BunsanApp 側の判断）

BunsanApp の診断は、同時に危殆化する保管先を1つの束（独立性グループ）として数える。

**原則: 同じ端末の中はすべて同じグループ。** カクレガの収納も、既定では
BunsanApp 自身と同じ「この端末」グループに属する。したがって
**端末内に置ける片は最大 k-1 個**（3-of-5 なら2個まで）。

### 例外規則

カクレガの錠オントロジーでいう**所在②以上（端末外に秘密がある）**の錠がかかった収納は、
端末そのものとは別の束と見なしてよい。

| 錠 | 所在 | 別グループとして登録してよいか |
|---|---|---|
| 錠なし・隠し場所のみ | ① 同一端末DB | **不可**。端末グループのまま |
| 暗証番号（PIN） | ② 記憶 | 可 |
| 二台目の端末（LANペア） | ④ 別端末 | 可 |
| 鍵アイテム | ① 同一端末DB | **不可** |

理由: 端末を取られただけでは開かない錠は、端末の危殆化と同時には破られない。
一方、鍵アイテムはカクレガのDB内にあるので端末と運命を共にする。

**この割り当ては利用者が BunsanApp 側で手動で行う。** カクレガから機械的に伝える口は作らない
（作ると、その口が錠の情報を漏らす経路になる）。3.4 の表示名がその判断材料になる。

---

## 5. 循環の禁止（必ず実装する）

カクレガ v1.3 は**分散片を解錠要素として使える**。ここに同じセットの片を保存すると、

> 開けるには片が要る / 片はその中にある

という循環で**永久に開かなくなる**。

### v1.0 での規則（静的・単純）

**解錠条件に「分散片」を含む収納は、`shareVault` を true にできない。**
トグル自体を無効化し、理由を表示する。

```
この収納は分散片で施錠されています。
分散片の保管先にすると、開けるために必要な片が
中に閉じ込められる可能性があります。
```

セットIDを見て「別のセットの片なら許可する」という精密化は将来の課題とする。
**v1.0 では一律で禁止**。安全側に倒す。

---

## 6. 片ファイルの識別

カクレガは v1.3 で既に BNSN ヘッダを読んでいる。同じ形式。

```
オフセット  長さ   内容
0          4     マジック "BNSN" (0x42 0x4E 0x53 0x4E)
4          4     ヘッダ長 L（符号なし32ビット・ビッグエンディアン）
8          L     ヘッダJSON（UTF-8）
8+L        残り   本体
```

ヘッダJSONのうち、カクレガが使ってよいのは **`id`（セットID）と `x`（片番号）だけ**。
`name` や `hash` は STEALTH プロファイルでは存在しない。読めたとしても使わない。

完全な仕様は BunsanApp リポジトリの `FORMAT_SPEC.md`。

### やらないこと

**カクレガは復元を実装しない。** 片を集めて元ファイルに戻すのは BunsanApp の役目。
二重実装すると、片方の修正がもう片方に反映されない事故が起きる。
カクレガがヘッダを読むのは、解錠判定（異なる `x` が k 個そろったか）と
循環検知のためだけ。

---

## 7. BunsanApp 側で足すもの

| 項目 | 内容 |
|---|---|
| 保管先種別に `EXTERNAL_APP` を追加 | 「別のアプリの中」。既定グループは「この端末」 |
| 同一端末警告 | `EXTERNAL_APP` を選んだとき「この保管先は端末内です。端末を失うと同時に失われます」を表示 |
| グループ手動割り当て | 4節の例外規則を画面上で案内する |

カクレガ固有のコードは書かない。`EXTERNAL_APP` は SAF 経由で見えるあらゆるアプリに使える。

---

## 8. 実装の順序（提案）

1. `SlotDef.shareVault` の追加と、増築モードのトグル（5節の禁止条件つき）
2. `VaultDocumentsProvider` の `queryRoots` / `queryChildDocuments` / `queryDocument`
3. `createDocument` / `openDocument`（3.3 の施錠時の挙動を含む）
4. 同名衝突の自動連番
5. `deleteDocument`

**1 と 5 だけでも先に入れる価値がある**（1 は循環禁止、5 は片の移動に必要）。

### 動作確認の手順

1. カクレガで収納を1つ作り、`shareVault` を on にする
2. BunsanApp で小さいファイルを 2-of-3 に分散する
3. 片の保存先選択で、カクレガが一覧に出ること
4. その収納に1片を保存し、カクレガ側で見えること
5. 収納に PIN 錠をかけ、施錠状態で一覧が空になり、新規保存はできること
6. BunsanApp の復元で、その片を読み出せること（解錠後）

---

## 9. 契約の変更手順

この文書を変えるときは、**カクレガ側とBunsanApp側の両方の写しを同時に更新**する。
片方だけ更新された状態を作らない。契約バージョンを上げ、変更点を末尾に追記する。

### 変更履歴

- v1.0 初版
