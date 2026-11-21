# Onvyra

<p align="center">
  <img src="docs/icon.png" width="120" alt="Onvyra">
</p>

<p align="center">
  <b>Android AI 用戶端 · 多模型對話 · 本機工作區 · 手機操控 · 閱讀與媒體</b><br>
  <sub>An Android AI client for multi-model chat, a local workspace, device control, reading and media.</sub>
</p>

<p align="center">
  <a href="https://github.com/gegewu26-source/Onvyra-Releases/releases/latest">下載最新版</a>
  ·
  <a href="CHANGELOG.md">更新紀錄</a>
  ·
  <a href="LICENSE">AGPL-3.0</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README_ZH_CN.md">简体中文</a> ·
  繁體中文 ·
  <a href="README_JA.md">日本語</a>
</p>

---

## 這是什麼

Onvyra 是一款原生 Android 應用程式，用來和你已經在用的 AI 模型對話。對話、檔案、閱讀器與內建網頁服務都在手機上執行：沒有 Onvyra 帳號，也沒有 Onvyra 伺服器保存你的對話紀錄。

你自備模型服務——自己的 API Key、自建閘道或相容中轉——應用程式提供的是好用的用戶端：帶上下文壓縮的長對話、檔案與文件處理、供模型讀寫的沙箱工作區、瀏覽器介面，以及閱讀和影視功能。

## 功能一覽

| | |
| --- | --- |
| **多模型對話** | 可加入任意數量的服務供應商，依對話選擇模型、隨時切換助手。長對話自動壓縮上下文，歷史紀錄仍可回查。 |
| **工具與搜尋** | 內建網頁搜尋與網頁讀取、PDF 正文分頁擷取、MCP 服務、依任務載入的 Skills，以及模型可讀寫的工作區。 |
| **手機操控** | 選用功能，透過無線偵錯或 Root 操作已授權的 Android 裝置，每個工具單獨核准。預設關閉，未開啟就不會執行。 |
| **瀏覽器存取** | 內建 Web 服務提供 React 介面，同一台裝置上的電腦瀏覽器可直接存取。 |
| **閱讀與媒體** | 小說閱讀器支援匯入書源，支援影視搜尋與對話內播放；所選模型具備能力時可生成圖片與影片。 |
| **網盤** | 選用的網盤整合，支援已設定服務的分享連結解析。 |

## 下載安裝

從 [Releases](https://github.com/gegewu26-source/Onvyra-Releases/releases/latest) 下載安裝包。

| 檔案 | 適用裝置 |
| --- | --- |
| `Onvyra-<版本>-arm64-v8a.apk` | 大多數 Android 手機與平板（64 位元 ARM） |
| `Onvyra-<版本>-x86_64.apk` | x86_64 裝置或模擬器 |
| `Onvyra-<版本>-universal.apk` | 同時包含兩種架構的單一安裝包 |
| `Onvyra-<版本>-source.zip` | 本版本對應的原始碼 |

最低需求 **Android 8.0（API 26）**。請依裝置架構選擇安裝包；universal 包通用但體積較大。

簽章憑證一致時，新版本可直接覆寫安裝舊版本。每個版本對應的原始碼與建置說明都放在同一個 Release 頁面。

## 從原始碼建置

準備 JDK 24、Android SDK Platform 37.2、NDK 29.0.14206865、CMake 3.22.1、Node.js 24 與 pnpm 12。

透過 `ANDROID_HOME` 或本機的 `local.properties` 指定 SDK 路徑，然後在儲存庫根目錄執行：

```bash
pnpm --dir web-ui install --frozen-lockfile
./gradlew :app:assembleDebug          # Linux / macOS
.\gradlew.bat :app:assembleDebug      # Windows
```

偵錯 APK 位於 `app/build/outputs/apk/debug/`。偵錯建置使用本機偵錯簽章，無需私有 Firebase 設定。正式簽章建置見[本機建置設定](docs/local-configuration.md)。

## 目錄結構

```
app/                     主應用程式：介面、ViewModel、核心邏輯
ai/                      服務供應商抽象（OpenAI 相容、Google、Anthropic 等）
common/                  通用工具
document/                PDF、DOCX、PPTX、EPUB 解析
highlight/               程式碼高亮
material3/               Material 顏色工具
mediagen/                圖片與影片生成
netdisk-download-engine/ 網盤下載引擎
oauth/                   OAuth 支援
search/                  搜尋服務
speech/                  TTS 與 ASR
ui/                      可重用 Compose 元件
web/                     內建 Ktor 服務與前端靜態資源
web-ui/                  React 前端原始碼
workspace/               沙箱檔案與指令執行工作區
android-control/         手機操控核心
```

## 開源版與個人版

開源版的四個選用影片網站介接預設不含站點位址。個人建置可透過 `ONVYRA_PRIVATE_VIDEO_ENDPOINTS` 讀取本機 JSON 檔案中的站點位址。驗證、贊助、社群和更新功能在兩個版本中都保留。

正式簽章讀取由 `ONVYRA_SIGNING_PROPERTIES` 指向的儲存庫外設定檔；金鑰材料從不進入本儲存庫。

## 更新機制

每個版本都帶有簽章的更新清單。應用程式讀取清單，用內建公鑰驗證簽章，然後依目前裝置的 ABI 提供對應安裝包。每個資產的校驗和隨版本一同發布。

## 授權與來源

Onvyra 衍生自 [RikkaHub](https://github.com/rikkahub/rikkahub)，採用 [GNU Affero 通用公眾授權條款第 3 版（AGPL-3.0）](LICENSE)散布。再散布修改版本時，須依同樣條款提供對應原始碼。若修改版支援透過網路遠端互動，第 13 條要求向這些使用者提供取得對應原始碼的方式。

第三方元件沿用各自的授權與署名。請參閱[應用程式聲明](app/src/main/assets/licenses/NOTICE.txt)、[Android 控制聲明](android-control/NOTICE.md)、[網路硬碟相關聲明](THIRD_PARTY_NETDISK_NOTICES.txt)及[影片擷取元件聲明](app/src/main/assets/video_extractor/NOTICE.txt)。

---

<p align="center"><sub>維護者 <a href="https://github.com/gegewu26-source">gegewu26-source</a></sub></p>