# iOS / iPadOS 版（Phase 5）

Android版で確立した業務仕様を、Swift + Vision Framework + SwiftUI で実装したもの。

## 状態

| 部分 | 状態 |
|------|------|
| `SloCore/`（正規化・抽出・Envelope・監査ログ） | 実装済み。Android/Web と同じ共通ベクタでテストを用意済み |
| `SecureLocalOcr/`（SwiftUIアプリ） | 実装済み。**実機・シミュレータでの動作確認は未実施** |

> 本リポジトリの開発環境は Linux のため、Swift のビルドとテストは実行していない。
> macOS 上で下記の手順を実行し、結果を `振り返り.html` に追記すること。
> これは既知の未完了事項であり、Android MVP の合格条件には含めていない（原則9）。

## 共有コアのテスト

```bash
cd ios/SloCore
swift test
```

`protocol/testdata/` の共通ベクタを読み、Kotlin実装・JavaScript実装と同じ結果になることを確認する。
3実装が一致することが INV-6（取込元と登録先で判定が一致する）の担保になる。

## アプリのビルド

`.xcodeproj` はリポジトリに置かない（生成物であり、署名設定が混入しやすいため）。
[XcodeGen](https://github.com/yonaskolb/XcodeGen) で生成する。

```bash
brew install xcodegen
cd ios
xcodegen generate
open SecureLocalOcr.xcodeproj
```

署名は各自の開発者アカウントで設定する。カメラを使うため実機が必要。

## Android版との対応

| 役割 | Android | iOS |
|------|---------|-----|
| カメラ | CameraX + 自前の画像補正 | VisionKit `VNDocumentCameraViewController`（傾き補正はOSが実施） |
| OCR | ML Kit 日本語（モデル同梱） | Vision `VNRecognizeTextRequest` + `requiresOnDeviceRecognition = true` |
| 認証 | BiometricPrompt | LocalAuthentication |
| Web連携 | WebView + `SLOHost` JSインターフェース | WKWebView + `WKScriptMessageHandler` |
| 保存 | アプリ専用領域・バックアップ除外 | Application Support・`isExcludedFromBackup` |
| 業務ロジック | `:core`（Kotlin） | `SloCore`（Swift） |

ページ側（`web/public/slo/slo-bridge.js`）は両OSで共通。ネイティブ側の受け口の作り方だけが異なる。

## iOS特有の注意点

- **`requiresOnDeviceRecognition = true` は必須。** false のままだとサーバ側処理へ
  フォールバックし得るため、原則3（OCRは端末内で完結させる）に反する。
- **スクリーンショットの完全抑止はiOSでは行えない。** Android の `FLAG_SECURE` に相当する
  APIが無いため、`.privacySensitive()` とアプリスイッチャー対策に留まる。
  運用は MDM（管理対象端末）側の制限と併用すること。詳細は `docs/security-design.html`。
- 写真ライブラリの権限説明を **意図的に Info.plist へ書いていない**。
  実装が写真へ触れないことを構成上も明示するため。
