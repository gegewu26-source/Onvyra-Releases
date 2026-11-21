---
name: android-release
description: Build, sign, and prepare Android APK and AAB for release. Handles version bumping, building, zipalign (APK), and signing with JKS keystore. Outputs DecentPaste_x.x.x.apk for GitHub releases and signed AAB for Play Console.
compatibility: 需 Android 构建工具及用户签名密钥
routing-category: Android构建开发
routing-summary: 需要构建、对齐、签名和准备发布 APK/AAB 时用；输入 Android 工程、版本与签名配置。侧重产物发布，非第三方 APK 修改；需构建工具及用户签名密钥。
---

> Onvyra 使用环境：先检查当前工作区或已连接的 MCP 是否提供本文需要的工具。内置技能提供文档、参考和附属脚本，不会自动安装 SDK、桌面软件、设备端服务或 MCP。只调用当前实际可用的工具；缺少依赖时明确说明，不要声称已经执行或验证。

# Android Release

Build signed APK (GitHub) and AAB (Play Console).

## 1. Gather Info

Ask user for: version (x.x.x), JKS keystore path, keystore alias.
**Never ask for password** - user enters it during signing.

## 2. Update Versions

Use `/bump-version` workflow to update all 4 config files.

## 3. Build

```bash
cd decentpaste-app && yarn tauri android build && cd ..
```

Outputs (from project root):
- APK: `decentpaste-app/src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release-unsigned.apk`
- AAB: `decentpaste-app/src-tauri/gen/android/app/build/outputs/bundle/universalRelease/app-universal-release.aab`

## 4. Sign APK

**Zipalign BEFORE signing** (Claude runs):
```bash
zipalign -v 4 <unsigned-apk> DecentPaste_VERSION_aligned.apk
```

Signing (user runs - contains password):
```bash
apksigner sign --ks <keystore> --ks-key-alias <alias> --out DecentPaste_VERSION.apk DecentPaste_VERSION_aligned.apk
```

After user confirms, delete aligned file.

## 5. Sign AAB

**Use `jarsigner` not `apksigner`** - AABs are JAR-like bundles. No zipalign needed.

User runs:
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore <keystore> <aab-path> <alias>
cp <aab-path> DecentPaste_VERSION.aab
```

## 6. Verify & Report

```bash
apksigner verify --verbose DecentPaste_VERSION.apk
jarsigner -verify -verbose DecentPaste_VERSION.aab
```

Report: files created, versions updated, next steps (commit, tag, upload).
