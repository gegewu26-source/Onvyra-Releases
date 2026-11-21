# Onvyra

<p align="center">
  <img src="docs/icon.png" width="120" alt="Onvyra">
</p>

<p align="center">
  <b>Android AI クライアント · マルチモデル対話 · ローカルワークスペース · 端末操作 · 読書とメディア</b><br>
  <sub>An Android AI client for multi-model chat, a local workspace, device control, reading and media.</sub>
</p>

<p align="center">
  <a href="https://github.com/gegewu26-source/Onvyra-Releases/releases/latest">最新版をダウンロード</a>
  ·
  <a href="CHANGELOG.md">変更履歴</a>
  ·
  <a href="LICENSE">AGPL-3.0</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README_ZH_CN.md">简体中文</a> ·
  <a href="README_ZH_TW.md">繁體中文</a> ·
  日本語
</p>

---

## Onvyra とは

Onvyra は、すでに利用している AI モデルと対話するためのネイティブ Android アプリです。会話、ファイル、リーダー、内蔵 Web サーバーはすべて端末上で動作します。Onvyra のアカウントは不要で、会話を保存する Onvyra のサーバーもありません。

モデルサービスはご自身で用意します。自分の API キー、自前のゲートウェイ、互換プロキシのいずれでも構いません。アプリはそこに使いやすいクライアントを提供します。コンテキスト圧縮付きの長い会話、ファイルと文書の処理、モデルが読み書きできるサンドボックスワークスペース、ブラウザー画面、そして読書とメディアの機能です。

## 主な機能

| | |
| --- | --- |
| **マルチモデル対話** | プロバイダーをいくつでも追加でき、会話ごとにモデルを選び、アシスタントを自由に切り替えられます。長い会話はコンテキストを圧縮し、履歴は引き続き参照できます。 |
| **ツールと検索** | 内蔵の Web 検索とページ読み取り、ページ分割付き PDF 抽出、MCP サーバー、タスク単位のスキル、モデルが読み書きできるファイルワークスペース。 |
| **端末操作** | オプション機能です。ワイヤレスデバッグまたは Root 経由で、許可した Android 端末を操作します。ツールごとに承認が必要で、既定では無効です。 |
| **ブラウザーからの利用** | 内蔵 Web サーバーが React 画面を配信します。同じネットワーク上の PC ブラウザーからそのまま利用できます。 |
| **読書とメディア** | 書源をインポートできる小説リーダー、動画検索と会話内再生、選択中のモデルが対応していれば画像・動画の生成。 |
| **ネットディスク** | 任意のクラウドストレージ連携と、設定したサービスの共有リンク解析。 |

## ダウンロード

[Releases](https://github.com/gegewu26-source/Onvyra-Releases/releases/latest) から APK を取得してください。

| ファイル | 対象 |
| --- | --- |
| `Onvyra-<バージョン>-arm64-v8a.apk` | ほとんどの Android スマートフォンとタブレット（64 ビット ARM） |
| `Onvyra-<バージョン>-x86_64.apk` | x86_64 端末およびエミュレーター |
| `Onvyra-<バージョン>-universal.apk` | 両方の ABI を含む単一パッケージ |
| `Onvyra-<バージョン>-source.zip` | このビルドに対応するソースコード |

**Android 8.0（API 26）** 以降が必要です。端末のアーキテクチャーに合う APK を選んでください。universal はどの端末でも動きますが、サイズが大きくなります。

署名証明書が同じであれば、新しいバージョンは旧バージョンに上書きインストールできます。各リリースのソースコードとビルド手順は同じリリースページに添付されています。

## ソースからビルド

JDK 24、Android SDK Platform 37.2、NDK 29.0.14206865、CMake 3.22.1、Node.js 24、pnpm 12 を用意します。

`ANDROID_HOME` またはローカルの `local.properties` で SDK の場所を指定し、リポジトリのルートで実行します。

```bash
pnpm --dir web-ui install --frozen-lockfile
./gradlew :app:assembleDebug          # Linux / macOS
.\gradlew.bat :app:assembleDebug      # Windows
```

デバッグ APK は `app/build/outputs/apk/debug/` に生成されます。デバッグビルドはローカルのデバッグ鍵を使い、非公開の Firebase 設定は不要です。署名付きリリースの手順は[ローカルビルド設定](docs/local-configuration.md)を参照してください。

## ディレクトリ構成

```
app/                     メインアプリ：UI、ViewModel、コアロジック
ai/                      プロバイダー抽象化（OpenAI 互換、Google、Anthropic など）
common/                  共通ユーティリティ
document/                PDF、DOCX、PPTX、EPUB の解析
highlight/               シンタックスハイライト
material3/               Material カラーユーティリティ
mediagen/                画像・動画生成
netdisk-download-engine/ クラウドストレージのダウンロードエンジン
oauth/                   OAuth サポート
search/                  検索プロバイダー
speech/                  TTS と ASR
ui/                      再利用可能な Compose コンポーネント
web/                     内蔵 Ktor サーバーとフロントエンド静的資源
web-ui/                  React フロントエンドのソース
workspace/               サンドボックスのファイル・シェルワークスペース
android-control/         端末操作カーネル
```

## 公開版と個人用ビルド

公開版では、4 つのオプション動画サイト連携に既定のアドレスは設定されていません。個人用ビルドでは、サイトのアドレスを記載したローカル JSON ファイルを `ONVYRA_PRIVATE_VIDEO_ENDPOINTS` で指定できます。認証、寄付、コミュニティ、更新の各機能はどちらのビルドにも含まれます。

リリース署名は `ONVYRA_SIGNING_PROPERTIES` が指すリポジトリ外の設定ファイルを読みます。鍵素材がこのリポジトリに含まれることはありません。

## 更新の仕組み

各リリースには署名済みの更新マニフェストが付属します。アプリはそれを取得し、バイナリに埋め込まれた公開鍵で署名を検証したうえで、端末の ABI に合う APK を提示します。各アセットのチェックサムはリリースごとに公開されます。

## ライセンスとクレジット

Onvyra は [RikkaHub](https://github.com/rikkahub/rikkahub) をベースにしており、[GNU Affero General Public License バージョン 3（AGPL-3.0）](LICENSE) で配布されています。変更版を再配布するときは、同じ条件で対応するソースコードを提供してください。変更版がネットワーク越しの利用に対応する場合、第 13 条により、その利用者にも対応するソースコードの入手手段を提供する必要があります。

第三者のコンポーネントにはそれぞれのライセンスと著作権表示が適用されます。[アプリの通知](app/src/main/assets/licenses/NOTICE.txt)、[Android 操作の通知](android-control/NOTICE.md)、[クラウドストレージの通知](THIRD_PARTY_NETDISK_NOTICES.txt)、[動画抽出コンポーネントの通知](app/src/main/assets/video_extractor/NOTICE.txt)を参照してください。

---

<p align="center"><sub>メンテナー <a href="https://github.com/gegewu26-source">gegewu26-source</a></sub></p>