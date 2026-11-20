# Skyble Browser — Development Guide

Android WebView browser (Android 5.0+), plain Java, **no Gradle, no AndroidX**.
Deep architecture notes: [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md).

## Build

```bash
# JDK 17 and aapt / zipalign / apksigner on PATH
JAVA_HOME=/path/to/jdk-17 PATH=$JAVA_HOME/bin:$PATH bash build.sh
```

- `sdk/android.jar` and `sdk/r8.jar` are gitignored. A fresh clone either copies
  them in or sets `ANDROID_HOME`; `build.sh` falls back to
  `platforms/android-*/android.jar` and `build-tools/*/lib/d8.jar`.
- Unsigned build (F-Droid): `ANDROID_HOME=/path/to/sdk UNSIGNED=1 bash build.sh`
  → `build/aligned.apk`.
- Release builds are signed by `sign.sh` from key material kept outside the
  repository. Point `SKYBLE_SIGNING_DIR` at that directory (default
  `$HOME/Keystores/skyble`); it holds the current key, the previous key and the
  rotation lineage. Both keys are required: Android 5–8 verifies the v1/v2
  signature (previous key), Android 9+ verifies the v3 signature and its
  lineage (current key).
- Signing settings come from `SKYBLE_LEGACY_KS`, `SKYBLE_LEGACY_PASS`,
  `SKYBLE_LEGACY_ALIAS`, `SKYBLE_CURRENT_KS`, `SKYBLE_CURRENT_PASS`,
  `SKYBLE_CURRENT_ALIAS` and `SKYBLE_LINEAGE`, or from `keystore.properties` /
  `skyble-release.properties` in that directory.
- Never commit key material, and never publish an APK signed outside the chain:
  existing installs reject a different signature.

## Layout

| path | purpose |
| --- | --- |
| `src/com/skyble/browser/MainActivity.java` | entire browser UI: shell, tabs, menus, sheets (≈4.5k lines) |
| `src/com/skyble/browser/SettingsActivity.java` | settings screen |
| `Downloader.java`, `DownloadInbox.java`, `MediaFinder.java`, `Muxer.java`, `Tube.java` | download pipeline (HTTP, media sniffing, YouTube, MP4 muxing) |
| `AdBlock.java` | host blocklist from `assets/adblock.txt` |
| `Updater.java` | GitHub release check + APK update flow |
| `Lang.java`, `Langs.java` | Bengali→English runtime translation layer |
| `Theme.java`, `SwitchStyle.java`, `Notify.java`, `Favicon.java`, `Pot.java`, `BrowserRole.java`, `ApkProvider.java`, `Trace.java` | theming, notifications, icons, misc |
| `assets/` | `home.html` start page, `dark.js`, `translate.js`, `adblock.txt`, `icon.png` |
| `res/` | only colors/styles/themes/icons — **no layout XML, UI is built in Java** |
| `docs/` | GitHub Pages website (served from `main` /docs) |
| `plugin/` | optional GPL-3.0 helper app (NewPipeExtractor) |
| `fastlane/` | store listing text, icon, screenshots |
| `fdroid/` | fdroiddata metadata draft |

## Conventions

- **Bengali is the source language.** UI strings are Bengali literals inside
  Java; the English version is an `EN.put("বাংলা", "English")` entry in
  `Lang.java`. New UI text = Bengali literal + `Lang` entry.
- UI is constructed programmatically (LinearLayout/TextView/WedView). Do not add
  layout XML or a dependency framework.
- Java 11 syntax, but match the existing style: anonymous inner classes,
  explicit types, Bengali comments. Keep API 21 compatibility.
- License split: main app MPL-2.0, `plugin/` GPL-3.0. Brand assets are not MPL
  (see `TRADEMARK.md`).

## Version bump (all must match)

1. `AndroidManifest.xml` → `android:versionCode` (+1) and `android:versionName`
2. `build.sh` → `NAME="SkybleBrowser-<version>.apk"`
3. `MainActivity.java` → `VERSION`
4. `SettingsActivity.java` → `VERSION`
5. `docs/index.html` → version text (2 places)
6. `fdroid/com.skyble.browser.yml` → only when updating F-Droid

## Release checklist

1. Bump version everywhere above; build and run `apksigner verify --print-certs`.
2. Commit, tag `v<version>`, push `main` + tag.
3. GitHub release: title `Skyble Browser <version>`, attach
   `SkybleBrowser-<version>.apk`. `Updater` reads `releases/latest` and picks the
   first `.apk` asset, so the tag/versionName must match.
4. Website (`docs/`) is deployed by GitHub Pages from `main`.

## Gotchas

- `Updater` hardcodes `Mamun015/skyble-browser`; renaming the repo breaks updates.
- The update check compares the release `tag_name` with the installed
  `versionName`; test builds must follow the `1.2.5 test 1` pattern handled by
  `Updater.baseVersion`.
- `assets/adblock.txt` (≈85 KB) is the whole blocklist.
- Known cleanup items (2026-11-20): `Updater` User-Agent still says
  `BanglaBrowser/`; F-Droid metadata is pinned at 1.0.1; release keystore and
  `sdk/` jars are not in the repo.
