# KakuregaApp HANDOFF

## これは何
カクレガ ― 探索型ファイル保管アプリ。仮想の家（1部屋）をタップで探索し、家具ごとにファイルを隠す。仕様は docs/SPEC.md、錠システムの設計原理は docs/ONTOLOGY.md。

## 現状（v1.2 = Phase 2＋資料室）
- 依存ゼロ・プログラマティックUI（Compose不使用）・パーミッション宣言ゼロ
- 家の定義は filesDir/house.json（House.kt、org.jsonで読み書き。scenes[]/slots[]、初回は5家具のリビングをseed）
- SceneView はモデル駆動。scene.image があれば背景画像をcover配置、なければ従来の自前Canvas描画（art フィールドで家具の絵を選ぶ）。床下は隠しスポット（長押しヒントでのみ枠が出る）。件数バッジ表示
- 増築モード: メニューから切替。空き領域を指でなぞる→収納/移動口を作る、既存枠タップ→名前変更・隠し切替・拡縮・削除。部屋の一覧から追加・画像割当・改名・開始部屋設定・扉作成・削除
- 部屋画像は filesDir/scenes/ にUUID名で保存（.nomedia同梱）。差し替え時は旧画像を削除
- 同梱画像は `asset:ファイル名` 形式で参照し assets/ から読む（差し替え時もassetは消さない）
- 資料室シーン（assets/room_atelier.jpg）を同梱。25箇所のうち15が隠しスポット。House.migrate() が version<2 のとき追加し、既存の部屋・収納・ファイルには触れない。リビングとは扉で往復
- house.json は version フィールドで管理。家を書き足すときは migrate() に版を足す（既存データを壊さないこと）
- ファイルの「別の場所へ移す」を追加（Db.moveFile）
- スロット画面: しまう(ACTION_OPEN_DOCUMENT, 複数可) / 見る / 取り出す(ACTION_CREATE_DOCUMENT) / 燃やす
- vault: filesDir/vault/ にランダム名（拡張子なし）+ .nomedia。台帳は SQLite (Db.kt, files テーブル)
- 内蔵ビューア: 画像(inSampleSizeで縮小デコード) / テキスト(先頭200KB) / 音声・動画(MediaPlayer+fd, 動画はSurfaceView)。ビューア表示中のみ FLAG_SECURE
- 画面遷移は単一Activity内のView入れ替え(scene/slot/viewer/scenes)。戻るキー対応
- SlotDef に lockPreset フィールドを用意済み（Phase 3の錠システム接続点。現在は全て "none"）

## 次にやること
- Phase 3: 錠システム（条件木、プリセット6種、診断エンジン＝純粋関数＋selfTest。ONTOLOGY.md §5〜9参照）
- Phase 4: LANペア解錠（NSD + HMACチャレンジレスポンス）

## ビルドの注意
- ビルドは GitHub Actions のみ（手元コンパイル不可の体制）
- build.yml は workflow_dispatch 専用・upload-artifact 禁止（Artifacts無料枠枯渇対策）。配布ビルドはタグ契機の release.yml（カタログ管理システムが注入）が担当
- settings.gradle の Maven Central ミラーは429対策。消さない
- .github/workflows/release.yml と ci/ はカタログ管理システムの持ち物。削除・追跡解除禁止
- workflow の run 行が `"` で始まる場合は必ず `run: |` を使う（YAMLパース事故防止）
