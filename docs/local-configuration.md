# Local build configuration / 本地构建配置

A clean checkout builds with no preconfigured addresses for the four optional video-site adapters. The adapters use the app's existing parsers and access checks. The app's bundled verification data, donation images, community links and update settings are part of the original app configuration.

## Site addresses / 站点地址

1. Copy [private-video-endpoints.example.json](../config/private-video-endpoints.example.json) to a local directory outside the repository. Fill in only the adapters you use. Each value is an HTTPS origin without a path, port, query or credentials, or a bare hostname.
2. Point the build environment at the local JSON file.

```powershell
$env:ONVYRA_PRIVATE_VIDEO_ENDPOINTS = 'D:\Onvyra-Local\video-endpoints.json'
.\gradlew.bat :app:assembleDebug --no-configuration-cache
```

For a public APK, clear `ONVYRA_PRIVATE_VIDEO_ENDPOINTS` before building. A personal APK built with this variable set contains the site addresses supplied in the JSON file. Keep the personal APK and address file in your local build area.

公开版默认不预置这四个站点的地址。个人版可用环境变量指定仓库外的地址文件，只填写自己需要的适配器。授权校验仍使用应用原有的配置，赞赏图片、社群入口和更新功能照常使用。个人 APK 会包含你填入的站点地址，请单独存放。

### Optional aliases / 可选别名

- MissAV uses `missav_legacy_1` through `missav_legacy_4`, `missav_mirror_1` through `missav_mirror_5`, and `missav_stream`.
- Jable uses `jable_mirror`.
- Hanime1 uses `hanime1_alternate`, `hanime1_mirror_1`, `hanime1_mirror_2`, `hanime1_restricted_1`, `hanime1_restricted_2`, `hanime1_stream_primary`, `hanime1_stream_backup`, and `hanime1_stream_rewrite`.
- SupJav uses `supjav_embed` and `supjav_stream`.

Unfilled aliases are ignored. Fill only the host roles required by your configuration.

## Other optional inputs / 其他可选项

- `ONVYRA_SIGNING_PROPERTIES` refers to a local signing configuration file. Build release packages using your own signing key outside the checkout. Never commit the keystore, passwords, or verification signing private key.
- `VITE_ONVYRA_SOURCE_URL` overrides the web UI's source link when publishing a modified build from a different source repository.
