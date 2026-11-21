# Onvyra

<p align="center">
  <img src="docs/icon.png" width="120" alt="Onvyra">
</p>

<p align="center">
  <b>Android AI 客户端 · 多模型对话 · 本地工作区 · 手机操控 · 阅读与媒体</b><br>
  <sub>An Android AI client for multi-model chat, a local workspace, device control, reading and media.</sub>
</p>

<p align="center">
  <a href="https://github.com/gegewu26-source/Onvyra-Releases/releases/latest">下载最新版</a>
  ·
  <a href="CHANGELOG.md">更新日志</a>
  ·
  <a href="LICENSE">AGPL-3.0</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  简体中文 ·
  <a href="README_ZH_TW.md">繁體中文</a> ·
  <a href="README_JA.md">日本語</a>
</p>

---

## 这是什么

Onvyra 是一款原生 Android 应用，用来和你已经在用的 AI 模型对话。会话、文件、阅读器和内置网页服务都跑在手机上：没有 Onvyra 账号，也没有 Onvyra 服务器保存你的聊天记录。

你自备模型服务——自己的 API Key、自建网关或兼容中转——应用提供的是好用的客户端：带上下文压缩的长对话、文件与文档处理、供模型读写的沙箱工作区、浏览器界面，以及阅读和影视功能。

## 功能一览

| | |
| --- | --- |
| **多模型对话** | 可添加任意数量的服务商，按会话选择模型、随时切换助手。长对话自动压缩上下文，历史记录仍可回查。 |
| **工具与搜索** | 内置网页搜索和网页读取、PDF 正文分页提取、MCP 服务、按任务加载的 Skills，以及模型可读写的工作区。 |
| **手机操控** | 可选，通过无线调试或 Root 操作已授权的 Android 设备，每个工具单独审批。默认关闭，不开启就不会执行。 |
| **浏览器访问** | 内置 Web 服务提供 React 界面，同一台设备上的电脑浏览器可以直接访问。 |
| **阅读与媒体** | 小说阅读器支持导入书源，支持影视搜索和聊天内播放；选定的模型具备能力时可生成图片和视频。 |
| **网盘** | 可选的网盘集成，支持已配置服务的分享链接解析。 |

## 下载安装

从 [Releases](https://github.com/gegewu26-source/Onvyra-Releases/releases/latest) 下载安装包。

| 文件 | 适用设备 |
| --- | --- |
| `Onvyra-<版本>-arm64-v8a.apk` | 大多数 Android 手机和平板（64 位 ARM） |
| `Onvyra-<版本>-x86_64.apk` | x86_64 设备或模拟器 |
| `Onvyra-<版本>-universal.apk` | 同时包含两种架构的单一安装包 |
| `Onvyra-<版本>-source.zip` | 本版本对应的源码 |

最低要求 **Android 8.0（API 26）**。请按设备架构选择安装包；universal 包通用但体积更大。

签名证书一致时，新版本可直接覆盖安装旧版本。每个版本对应的源码和构建说明都放在同一个 Release 页面。

## 从源码构建

准备 JDK 24、Android SDK Platform 37.2、NDK 29.0.14206865、CMake 3.22.1、Node.js 24 和 pnpm 12。

通过 `ANDROID_HOME` 或本机的 `local.properties` 指定 SDK 路径，然后在仓库根目录执行：

```bash
pnpm --dir web-ui install --frozen-lockfile
./gradlew :app:assembleDebug          # Linux / macOS
.\gradlew.bat :app:assembleDebug      # Windows
```

调试 APK 位于 `app/build/outputs/apk/debug/`。调试构建使用本地调试签名，无需私有 Firebase 配置。正式签名构建见[本地构建配置](docs/local-configuration.md)。

## 目录结构

```
app/                     主应用：界面、ViewModel、核心逻辑
ai/                      服务商抽象（OpenAI 兼容、Google、Anthropic 等）
common/                  通用工具
document/                PDF、DOCX、PPTX、EPUB 解析
highlight/               代码高亮
material3/               Material 颜色工具
mediagen/                图片与视频生成
netdisk-download-engine/ 网盘下载引擎
oauth/                   OAuth 支持
search/                  搜索服务
speech/                  TTS 与 ASR
ui/                      可复用 Compose 组件
web/                     内置 Ktor 服务与前端静态资源
web-ui/                  React 前端源码
workspace/               沙箱文件与命令执行工作区
android-control/         手机操控内核
```

## 开源版与个人版

开源版的四个可选视频站点适配器默认不带地址。个人构建可通过 `ONVYRA_PRIVATE_VIDEO_ENDPOINTS` 读取本地 JSON 文件中的站点地址。校验、赞赏、社群和更新功能在两个版本中都保留。

正式签名读取由 `ONVYRA_SIGNING_PROPERTIES` 指向的仓库外配置文件；密钥材料从不进入本仓库。

## 更新机制

每个版本都带有签名的更新清单。应用读取清单，用内置公钥校验签名，然后按当前设备的 ABI 提供对应安装包。每个资产的校验和随版本一同发布。

## 许可证与来源

Onvyra 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，采用 [GNU Affero 通用公共许可证第 3 版（AGPL-3.0）](LICENSE)分发。再分发修改版时，须按同样条款提供对应源码。如果修改版支持通过网络远程交互，第 13 条要求向这些用户提供获取对应源码的方式。

第三方组件沿用各自的许可和署名。参见[应用声明](app/src/main/assets/licenses/NOTICE.txt)、[安卓控制声明](android-control/NOTICE.md)、[网盘相关声明](THIRD_PARTY_NETDISK_NOTICES.txt)及[视频提取组件声明](app/src/main/assets/video_extractor/NOTICE.txt)。

---

<p align="center"><sub>维护者 <a href="https://github.com/gegewu26-source">gegewu26-source</a></sub></p>