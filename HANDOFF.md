# KakuregaApp HANDOFF

## これは何
カクレガ ― 探索型ファイル保管アプリ。仮想の家（1部屋）をタップで探索し、家具ごとにファイルを隠す。仕様は docs/SPEC.md、錠システムの設計原理は docs/ONTOLOGY.md。

## 現状（v1.0 = Phase 1）
- 依存ゼロ・プログラマティックUI（Compose不使用）・パーミッション宣言ゼロ
- SceneView: 部屋を自前Canvas描画。家具5種（本棚/ステレオ/テレビ台/引き出し/床下）。床下は隠しスポット（長押しヒントでのみ枠が出る）。件数バッジ表示
- スロット画面: しまう(ACTION_OPEN_DOCUMENT, 複数可) / 見る / 取り出す(ACTION_CREATE_DOCUMENT) / 燃やす
- vault: filesDir/vault/ にランダム名（拡張子なし）+ .nomedia。台帳は SQLite (Db.kt, files テーブル)
- 内蔵ビューア: 画像(inSampleSizeで縮小デコード) / テキスト(先頭200KB) / 音声・動画(MediaPlayer+fd, 動画はSurfaceView)。ビューア表示中のみ FLAG_SECURE
- 画面遷移は単一Activity内のView入れ替え(scene/slot/viewer)。戻るキー対応

## 次にやること
- Phase 2: シーン複数化、house.json化、増築モード（画像割当＋ホットスポット自作）
- Phase 3: 錠システム（条件木、プリセット6種、診断エンジン＝純粋関数＋selfTest。ONTOLOGY.md §5〜9参照）
- Phase 4: LANペア解錠（NSD + HMACチャレンジレスポンス）

## ビルドの注意
- ビルドは GitHub Actions のみ（手元コンパイル不可の体制）
- build.yml は workflow_dispatch 専用・upload-artifact 禁止（Artifacts無料枠枯渇対策）。配布ビルドはタグ契機の release.yml（カタログ管理システムが注入）が担当
- settings.gradle の Maven Central ミラーは429対策。消さない
- .github/workflows/release.yml と ci/ はカタログ管理システムの持ち物。削除・追跡解除禁止
- workflow の run 行が `"` で始まる場合は必ず `run: |` を使う（YAMLパース事故防止）
