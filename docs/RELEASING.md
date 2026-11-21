# 发布流程 · Release procedure

本文档记录一次对外发布要做的全部步骤，供以后更新时照做。签名私钥、口令和更新签名私钥都在仓库之外单独保管，任何时候都不进入源码、APK 或 Git。

## 术语

- **versionName**：展示用版本号，格式 `主版本.次版本-reader.N`，例如 `2.7.2-reader.72`。
- **versionCode**：整数，必须比上一版大，Android 用它判断升级。
- **tag**：Release 标签，建议与 versionName 一致并加 `v` 前缀，例如 `v2.7.2-reader.72`。
- **asset**：Release 附件，包含三个 APK 和一个源码 zip。

## 1. 准备构建环境

| 组件 | 版本要求 |
| --- | --- |
| JDK | 24 |
| Android SDK Platform | 37.2 |
| NDK | 29.0.14206865 |
| CMake | 3.22.1 |
| Node.js | 24 |
| pnpm | 12 |

SDK 路径通过 `ANDROID_HOME` 或本机 `local.properties` 指定。

## 2. 改版本号

编辑 `app/build.gradle.kts` 的 `defaultConfig`：

```kotlin
versionCode = 264
versionName = "2.7.3-reader.73"
```

同时更新 `CHANGELOG.md` 顶部条目。发布说明模板见仓库根目录 `RELEASE_NOTES.md`。

## 3. 安装前端依赖并构建

```bash
pnpm --dir web-ui install --frozen-lockfile
export ONVYRA_SIGNING_PROPERTIES=/path/to/signing.properties   # 仓库外，含密钥路径与口令
./gradlew :app:assembleRelease --no-configuration-cache -x lintVitalRelease
```

Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。成品在 `app/build/outputs/apk/release/`：

- `app-arm64-v8a-release.apk`
- `app-x86_64-release.apk`
- `app-universal-release.apk`

构建会执行 `validateReleaseTrust`，签名缺失、误用 debug 证书或信任锚不匹配都会直接失败。

## 4. 重命名与校验

```bash
for abi in arm64-v8a x86_64 universal; do
  cp "app/build/outputs/apk/release/app-${abi}-release.apk" \
     "Onvyra-${VERSION}-${abi}.apk"
done
sha256sum Onvyra-*.apk
apksigner verify --print-certs Onvyra-arm64-v8a.apk
```

签名证书 SHA-256 必须是 `a5a82c664ec01e51904a743234fbf8ebc985cdd432e972b5b8d473b567b31c27`。证书变了，老用户就无法覆盖升级。

## 5. 打源码包

源码包要能对应上二进制，排除构建产物、依赖和本地配置：

```bash
zip -r "Onvyra-${VERSION}-source.zip" . \
  -x "*/build/*" "*/.gradle/*" "*/node_modules/*" "*/.git/*" \
     "*/.cxx/*" "*/local.properties" "*/outputs/*"
```

`local.properties`、`signing.properties`、`*.jks`、`*.p12`、`*.pem` 已在 `.gitignore` 中排除，打包含前再确认一次。

## 6. 生成签名更新清单

应用内的版本检查读取仓库 `main` 分支的 `onvyra-updates/latest.json`，内容是用更新签名私钥签名的信封。仓库里提供了生成脚本：

```bash
python -I tools/build_update_manifest.py \
  --key /path/to/update-signing-rsa3072.pem \
  --tag "v${VERSION}" \
  --version-code 264 \
  --version-name "2.7.3-reader.73" \
  --title "Onvyra 2.7.3-reader.73" \
  --summary "本版说明摘要" \
  --changes changes.json \
  --asset arm64-v8a:Onvyra-2.7.3-reader.73-arm64-v8a.apk \
  --asset x86_64:Onvyra-2.7.3-reader.73-x86_64.apk \
  --asset universal:Onvyra-2.7.3-reader.73-universal.apk \
  --out onvyra-updates/latest.json
```

`changes.json` 示例：

```json
[
  {"type": "improvement", "items": ["将较大的内置图片改为无损 WebP，减少安装包体积。"]},
  {"type": "fix", "items": ["修正书源搜索结果的排序计算。"]}
]
```

`type` 只接受 `feature`、`fix`、`improvement`、`notice`。脚本会算好每个包的 SHA-256 与大小，并校验签名。**清单必须先于 Release 附件提交**，否则应用会拿到不存在的下载地址。

## 7. 上传

```bash
gh release create "v${VERSION}" \
  "Onvyra-${VERSION}-arm64-v8a.apk" \
  "Onvyra-${VERSION}-x86_64.apk" \
  "Onvyra-${VERSION}-universal.apk" \
  "Onvyra-${VERSION}-source.zip" \
  --repo gegewu26-source/Onvyra-Releases \
  --title "Onvyra ${VERSION}" \
  --notes-file RELEASE_NOTES.md
```

发布说明建议同时提供简体中文、英文、繁体中文、日文四段，参考 `RELEASE_NOTES.md` 的排版。

## 8. 发布后检查

- [ ] 四个附件都在，文件名与清单里的 URL 完全一致
- [ ] `apksigner verify` 通过，证书指纹未变
- [ ] Release 说明里的版本号与 `app/build.gradle.kts`、`CHANGELOG.md` 一致
- [ ] `onvyra-updates/latest.json` 已在 `main` 分支，签名可验
- [ ] 源码 zip 能从零构建（可选，抽检即可）
- [ ] 在 arm64 真机上试装一次，确认覆盖升级成功

## 已知约束

- 全平台只用新证书，同时兼容 min26 的签名轮换无法兼得。旧 debug 签名用户需要先导出数据再重装。
- 更新清单的下载地址必须落在 `https://github.com/gegewu26-source/Onvyra-Releases/releases/download/<tag>/` 内，且以 `.apk` 结尾，否则应用拒绝。

## AGPL 义务

对外分发二进制时，同一 Release 页必须能拿到对应源码。源码 zip 与 APK 放在同一个 Release，即可满足第 13 条的获取途径要求。若修改版提供网络远程服务，还需在该服务上提供源码获取方式。