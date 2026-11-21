# AndroidControl integration

This module includes a narrow, modified subset of Mika, Copyright 2026 wangzan101,
under Apache-2.0. Reference revision: `688f59d5a18d99d0231504598da4059f3feb226e`.

Source: https://github.com/wangzan101/Mika

The upstream license, NOTICE and third-party attribution are retained verbatim in
`src/main/assets/android-control-notices/`. `shell/ShellContext.kt` also retains
the upstream scrcpy / Genymobile / Romain Vimont attribution.

Reused and adapted: `core/adb` TLS, identity, ADB protocol, SPAKE2 and cryptographic
helpers; `core/shell/HelperHandoff`; the authenticated app_process/provider handoff
from `HelperMain` / `HelperHandoffProvider`; the virtual-display, launch and native
input subset of `ShellService` and `ShellContext`. The optional live-preview overlay
adapts the focus and drag lifecycle from the upstream accessibility `OverlayHost`;
its display-scoped protected screenshot pipeline, throttling and task lifecycle are
modified for this integration.

Changes: package names and process/provider identities; narrow Binder contract;
explicit master switch and manual accessibility authorization; bounded reconnect;
interruptible display-scoped input; standalone session and batch orchestration;
Onvyra Tool registration and existing approval UI integration; offline OCR,
independent permission/settings page and stop notification; synthetic fixtures and
tests; installed-app name resolution and explicit resumable manual takeover;
a focusless, draggable, collapsible local preview with an always-available stop
control. The upstream BatchRunner and accessibility implementation were read as
references; their broader agent framework is not incorporated.

Mika chat UI, browser, memory, knowledge database, Linux sandbox and sandbox native
binaries are not copied. The retained full upstream third-party notice describes
its original distribution; it is retained for attribution, not as a statement that
all listed components are incorporated by this module.

Additional dependency: bundled Google ML Kit Text Recognition (Chinese),
`com.google.mlkit:text-recognition-chinese:16.0.1`, subject to Google's terms:
https://developers.google.com/ml-kit/terms
