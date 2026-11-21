# Inline video: third-party components

The inline player uses the app's Android Media3 player and public platform embed pages. Optional enhanced URL extraction uses the following pinned components. It does not install another app or add a background download service.

## In-picture controls, source selection, and quality

The message-local Compose controls sit inside the video viewport in both inline and fullscreen modes. They expose playback time, seeking, speed, fullscreen, and menus without adding a toolbar above the picture. An idle timer hides the controls; menu and seek interactions keep them visible.

The inline viewport is fixed at 16:9 for its available chat width, throughout loading, errors, menus, source changes and control visibility. Native metadata still describes the video's true display ratio, including pixel aspect ratio and rotation. Inline playback starts at complete-image FIT; encoded black bars remain part of the source image.

Controls overlay the unchanged viewport; toggling them never reserves strips or changes the picture transform. Fullscreen locks its entry orientation until dismissed, hides system bars and fills the window; only controls reserve display-cutout safety space. The native view uses an XML-configured Media3 TextureView, with the aspect-correct content frame scaled uniformly and clipped inside an unchanged PlayerView. Fullscreen starts at centered cover without stretching. Two-finger pinch can reveal the complete FIT image or enlarge it; two-finger pan is clamped to the available picture area. Gestures transform decoded video pixels, not controls, and cannot become a final single-finger tap. No new player SDK or media processing service is added. The surface choice follows the [Media3 surface documentation](https://developer.android.com/media/media3/ui/surface).

Speed, quality and source menus are small vertically scrolling strips anchored immediately above their own buttons, with widths of 64/88/112dp in both inline and fullscreen modes. There is no visible header or close bar. Height is bounded by space above the button and at most 224dp. The selected entry is cyan. The transparent outside region dismisses the list without dimming the entire image.

Manual native video quality selection uses Media3's supported video tracks and `TrackSelectionOverride`. Labels describe actual advertised resolution (or bitrate when resolution is unknown), with an automatic option that clears the override. A single-rendition source does not acquire synthetic HD/4K options. Embedded webpages retain their platform-specific quality limitations. These controls add no separate player, conversion service, upscaler, or FFmpeg dependency.

Movie source selection uses the existing catalog client to enumerate matching titles, years, episodes, and lines, then resolves the exact selected locator. Catalog quality labels are identified as catalog claims; they are separate from the decoded stream's measured resolution. Switching native sources reuses the same player and retains the current position, playback intent, speed, and fullscreen state. No playback URL or authentication header is included in the menu's locator objects.

## Native transport and screen gestures

The existing Media3 player handles transport. The play icon follows `playWhenReady` while buffering and treats `STATE_ENDED` as a replay before testing the pause branch. One pointer recognizer arbitrates confirmed single tap, double tap, temporary speed hold, horizontal seek, fullscreen vertical adjustment and pinch. Seek commits only after an unconsumed release; Compose's consumed synthetic release for cancellation discards the preview. Temporary speed never changes the permanent selected rate or starts paused audio, and is reset on cancellation, media replacement, fullscreen changes and backgrounding.

Fullscreen brightness uses only the dialog's [`WindowManager.LayoutParams.screenBrightness`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness), restoring its previous value when disposed. It does not request `WRITE_SETTINGS`. For the initial value it prefers an existing window override, then a valid normalized system setting, then raw settings normalized against the declared integer range. Read-only inspection of the user's OnePlus PLC110 found a normal maximum of 4095 while the legacy integer resource still says 255; the calibration is limited to that measured manufacturer/model, including raw values below 255. Unknown extended ranges fall back to 50% rather than being silently clamped to maximum. No hidden brightness API, privileged permission or shell subprocess is used. Media volume uses [`AudioManager.STREAM_MUSIC`](https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,%20int,%20int)) with no system popup and the normal `MODIFY_AUDIO_SETTINGS` manifest permission. Inline vertical movement is left to chat scrolling. No additional player dependency is introduced.

## Android extraction runtime

- Maven coordinate: `io.github.junkfood02.youtubedl-android:library:0.18.1` (plus its `common:0.18.1` dependency).
- Project and build sources: <https://github.com/yausername/youtubedl-android>.
- Published Maven metadata: <https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1.pom>.
- Published corresponding Java/Kotlin source: <https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1-sources.jar> and <https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/common/0.18.1/common-0.18.1-sources.jar>.
- License: GPL-3.0, retained in `app/src/main/assets/video_extractor/LICENSE-youtubedl-android.txt`. The project documents how it builds its bundled Python runtime in `BUILD_PYTHON.md`.
- Onvyra initializes this component only when enhanced extraction is requested. The separate FFmpeg and aria2c artifacts are not dependencies of this feature.

The transitive Commons Compress ZIP reader registers extra-field classes with reflective no-argument construction. `app/proguard-video.pro` preserves those concrete classes and constructors in optimized builds; otherwise R8 can make `AsiExtraField` abstract and first-time Python extraction fails during `ExtraFieldUtils` initialization. The optional extractor also converts `LinkageError` into a recoverable source error. Optimized-runtime probes must exercise the actual APK classes and a fresh Python extraction, rather than relying on debug instrumentation or cold launch alone.

## Pinned yt-dlp script

- Version: **2026.10.26**.
- Source: <https://github.com/yt-dlp/yt-dlp/tree/2026.10.26>.
- Exact release: <https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26>.
- Bundled asset: <https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp> (the platform independent zipimport script, not a Windows or Linux binary).
- Release checksums: <https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/SHA2-256SUMS>.
- Asset size: **3,073,072 bytes**.
- SHA-256: `e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`.

The checked-in script bytes match both the release's `SHA2-256SUMS` entry and the GitHub release API asset digest. Its checksum is shipped as `yt-dlp.sha256`; provenance is retained as `provenance.json`. Initialization verifies that checksum before replacing the runtime's older bundled script. There is no runtime auto-update, self-update command, or remote parser service in this integration.

yt-dlp's own code uses the **Unlicense**. This release's zipimport archive also contains **ISC** code from [Meriyah](https://github.com/meriyah/meriyah) and **MIT** code from [Astring](https://github.com/davidbonnet/astring). These are not all the same license. Upstream's complete `LICENSE` and `THIRD_PARTY_LICENSES.txt` are retained beside the script as `LICENSE-yt-dlp.txt` and `THIRD_PARTY_LICENSES-yt-dlp.txt`; the latter is upstream's broader release license collection and also describes components of its other distribution formats.

Enhanced extraction obtains media metadata and playback URLs with `--skip-download`. Platform authentication, regional availability, DRM, or changes to a site's interfaces may still prevent playback. Current full YouTube extraction may also require a JavaScript runtime; this feature retains the public embedded-player fallback rather than silently installing an additional execution runtime.

## Movie catalog protocol reference

The native Kotlin catalog client is an independent implementation of HTTP JSON catalog requests and the common MacCMS `vod_play_url` format. It does not embed another project's webpage, Chromium extension, remote JavaScript, or Node.js server.

- Reference flow: [88lin/video_vip](https://github.com/88lin/video_vip), title lookup and `wsyzyDirect` episode metadata. The script declares GPL-3.0; no script code is copied or executed here.
- Catalog protocol references: [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV) (AGPL-3.0-or-later) and [Stardm0/MoonTV configuration](https://github.com/Stardm0/MoonTV/blob/d25cef49bd0f45b3f6a36c8f5cf22f3370610662/config.json) (MIT project). No Next.js server or frontend code was bundled.
- The original built-in source IDs remain `wsyzy`, `baofeng`, and `haohua`, in `MovieCatalogClient.kt`. A private registry can additionally retain up to 128 discovered compatible HTTP JSON catalogs. These are external network services, not video files included in the APK. A software project's license does not grant rights to third-party media returned by a service.
- Requests send the requested title/year and catalog IDs. App API credentials, chat history and account cookies are not sent to catalogs. The client retains standard certificate validation and does not disable TLS checks when a line fails.

## User-supplied configuration discovery package

The user supplied `影视接口功能_AI接入精简包.zip` on 2026-10-30. Its SHA-256 is `63f4497d7106c835de23cc2a657a71070fd42f972bfde9831101b596ca2e7c69`. The package contains `ConfigDecoder.java`, `ConfigDiscovery.java`, offline test assertions, and `sources.json` with 57 base addresses and 20 discovery pages. Source attribution is retained in `app/src/main/assets/movie_catalog/NOTICE.txt`; the supplied test class is included only in Android test sources. Package inclusion is not evidence that its source addresses currently work.

The Java core decodes configuration data and expands single/multiple repositories; it is not a movie search or playback engine. The integration extracts independent compatible HTTP JSON APIs and never downloads or executes referenced spider/JAR/JavaScript providers. Configuration readability, a matching catalog result, successful media resolution, and actual decoded playback remain separate checks. Details and the bounded scanner budgets are documented in [movie-source-discovery.md](movie-source-discovery.md).

After catalog discovery fails, automatic webpage discovery uses the user's currently selected search provider. The final model-directed tool is `video_find_sources`, followed by `web_fetch` and `video_resolve` for real candidate URLs. This does not add a new paid-provider account or silently rotate through other configured providers; calls can still incur the selected search service's and model's normal charges. Bing uses the injected HTTP client and proxy rather than opening a separate Jsoup transport. Neither this discovery path nor its source configurations add a DRM, membership, login or regional-access bypass.

Runtime validation of this discovery/fallback upgrade is not yet complete. Existing playback tests below or in earlier deliverables do not establish that the new chain passed; use the current upgrade's acceptance record for completed checks.

## Instrumentation test media

`app/src/androidTest/assets/inline-video-test.mp4` is an original generated test pattern: a colored moving rectangle and frame counter, H.264 in MP4, 320 × 180 pixels, 24 fps, 144 frames (6 seconds), without audio. It was encoded with Windows Media Foundation through OpenCV; no FFmpeg binary or codec package was added to the application. It contains no third-party footage, music, or personal data. It is only included in the Android test APK, not in the application APK.

`app/src/androidTest/assets/inline-video-wide-test.mp4` is an additional original 960 × 360, four-second test pattern with edge markers and a bottom subtitle line. It is used to inspect wide-picture fitting, subtitle visibility, control-area reservation, and fullscreen behavior; it contains no third-party movie footage and is shipped only in the test APK. The fitting checks belong to the preceding player upgrade, separately recorded in [inline-video-preview.md](inline-video-preview.md); test asset presence alone does not establish that a newly modified playback path passed.
