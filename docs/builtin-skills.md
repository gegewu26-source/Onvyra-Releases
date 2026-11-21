# XuanXing built-in skill library

Onvyra bundles 43 task-scoped skills and their supporting assets. Original authors,
source inventory and license obligations are recorded in
`app/src/main/assets/builtin_skills/NOTICE.md`, `catalog.json`, and the per-skill licenses.
This document does not replace those notices or licenses.

The existing Onvyra `skill-creator` is retained. No skill is automatically enabled.
Built-ins are read-only under `/builtin_skills`; imported or edited user skills under `/skills`
override a built-in with the same name. This addition never writes to user skill folders or
changes assistants' or conversations' enabled skill selections.

Dependencies described by these documents are not installed by adding the skill. Desktop tools,
Android SDK / ADB, root-enabled or iOS devices, and specific MCP servers must be supplied separately
when required. Original commands and scripts are reference assets; they were not executed during import.
Onvyra adds a dependency note and compatibility metadata to each imported SKILL.md, and shortens
the apk-clues-extract description to fit the Agent Skills metadata limit. Two source metadata
files are renamed to visible asset filenames so Android asset packaging retains them; their
contents are unchanged and catalog.json records the original names. Relative Markdown asset
links are added so Onvyra use_skill can discover and read supporting references and scripts.

Excluded: `参考` and `破限` are global prompt overrides; `破` is a prompt evaluation note with
missing referenced files; `rev-dex-dumper` depends on a bundled opaque executable. A generated
Python `__pycache__` file is also omitted. The excluded source files are not changed.

Imported skills:

- `ad-removal`
- `ai-reverse-workflow`
- `Android Studio`
- `android-emulator-skill`
- `android-gradle-logic`
- `android-pentest`
- `android-release`
- `android-reverse-engineering`
- `android-unpacking`
- `apk-clues-extract`
- `apktool`
- `deobfuscation-ollvm`
- `devirtualization-vmp`
- `flutter`
- `flutter-reverse`
- `frida-scripts`
- `hybrid-h5-reverse`
- `ida-decompile`
- `IDA-Skill`
- `jadx`
- `kernel-system-hook`
- `memory-integrity-bypass`
- `mt-mcp-apk-analyzer`
- `noroot-hook`
- `openclaw-sdk`
- `packer-identification`
- `performing-dynamic-analysis-of-android-app`
- `protocol-crypto-analysis`
- `rev-frida`
- `rev-idapython`
- `rev-ios-dump`
- `rev-struct`
- `rev-symbol`
- `rev-u3d-dump`
- `rev-unicorn-debug`
- `reverse-patch-techniques`
- `signature-bypass`
- `smali-repack`
- `Smart Patch (IDA Pro)`
- `static-symbolic-execution`
- `svc-instruction-trace`
- `unity-il2cpp-reverse`
- `xposed-module-builder`


## 2026-10-21 phone security workflows

The library now contains 48 built-in entries: the existing 44 are preserved and
four new entries are available in the existing extension/Skills picker:

- phone-security-workbench: environment discovery, target intake and task records.
- phone-web-api-testing: API mapping, two-session authorization, session/JWT,
  browser CORS/CSRF and input/error validation.
- phone-android-reverse-workflow: APK integrity, static-to-runtime evidence and
  rebuild/signing verification across the existing Android/Termux/Kali tools.
- phone-security-evidence: finding triage, redaction and reproducible reports.

Sixteen source documents from the user-supplied 236-skill archive were condensed
and corrected, rather than copying all files. The four entries expose 11 linked
reference documents for progressive loading; no script or binary is included in
the new folders. Existing user skill directories, assistant/conversation
enabledSkills and UI layout are unchanged. The stale android-pentest reference
to absent client-reverse now points at android-reverse-engineering.

Per-source hashes and declared licenses are in catalog.json/additionalLibraries;
see NOTICE-Pentest-Skills.md for the source-license boundary. Current phone
Frida/native-tool facts are dated snapshots requiring live checks, not promises
that a service or an end-to-end test is working. Adding Skills does not install
software or join two MCP private filesystems.

## 2026-10-29 skill selection

All 48 built-in entries now include a short `routing-summary` and a
`routing-category` in their frontmatter. Summaries distinguish overlapping
workflows and specialist skills by objective, input, platform and stage. Original
descriptions, instructions, supporting files and license notices are preserved.

The model receives a grouped catalog of the current conversation's enabled
skills, with summaries, compatibility requirements and any declared triggers.
It is instructed to choose the most specific suitable skill automatically; a
broad task starts with one workflow and loads specialists when needed. The
`use_skill` name schema lists enabled names, and execution still rejects any
unavailable name. Names mentioned as comparisons in summaries are not automatically
enabled. Provider schema support varies; the execution check applies to all providers.

Full instructions and references are loaded on demand. Imported skills without
the optional routing fields retain their original descriptions. User skills
override same-name built-ins including routing metadata; no selection or user
file is changed. This improves discovery, but the model still makes the choice;
it does not guarantee every model will call the best skill on every request.
