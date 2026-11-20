# Skyble Browser — Project Context

Architecture, build, and maintenance notes. Last reviewed 2026-11-20.

## Snapshot

- Repo: `Mamun015/skyble-browser` (public, MPL-2.0; brand assets reserved)
- Current version: **1.2.6** (`versionCode` 28), tag `v1.2.6`
- Releases: v1.0.1, v1.2, v1.2.1, v1.2.2, v1.2.4, v1.2.5, v1.2.6 (APK assets)
- Website: <https://mamun015.github.io/skyble-browser/> (GitHub Pages from `docs/`)
- Source size: ~8.7k lines of Java, 21 MB working tree
- Companion: `Mamun015/skyble-tube` (standalone YouTube downloader)

## Product

A sub-1 MB Android browser that renders through the system WebView instead of
shipping Chromium. Core features:

- Up to 50 tabs, session restore, speed dial, history/bookmark suggestions
- Ad/tracker host blocking, no analytics or ad SDKs
- Downloads: direct files plus YouTube 1080p/720p/480p/360p, with parallel
  connections, resume, and on-device MP4 muxing
- Page translation via Google Translate (auto for non-English pages)
- Four themes, Material You palette on Android 12+, page dark mode
- English + Bengali UI, in-app GitHub release updater
- Optional GPL helper app (`plugin/`) using NewPipeExtractor

## Build

No Gradle. `build.sh` runs the plain SDK toolchain:

1. `aapt package` resources + manifest + assets → base APK
2. `javac` all sources (Java 11, `-source 11 -target 11`)
3. D8/R8 dexing (`sdk/r8.jar`, min API 21)
4. `zipalign`
5. `sign.sh` with the release key chain (unless `UNSIGNED=1`)
6. `apksigner verify`

Inputs that are **not in git**: `sdk/android.jar`, `sdk/r8.jar` and the signing
material (all gitignored). Build machine needs JDK 17 + Android build-tools.

Signing uses a two-key chain kept outside the repository, pointed at by
`SKYBLE_SIGNING_DIR`:

| file | role |
| --- | --- |
| `keystore.jks` | previous release key — v1/v2 signatures, Android 5–8 |
| `skyble-release.jks` | current release key — v3 signature, Android 9+ |
| `lineage.bin` | rotation proof linking the previous key to the current one |

Both keys are needed on every build. An APK signed with only one of them either
skips old devices or breaks updates on new ones.

Certificates currently in the chain:

```
legacy : C=BD, O=Mobile, CN=Bangla Browser
         SHA-256 80:4B:A5:81:D3:D0:8A:82:90:4B:B0:E5:DE:F7:25:F7:DE:B2:9B:E3:D2:BB:07:4C:1D:2D:E1:EF:B1:1F:FD:34
current: C=BD, O=Mobile, CN=Skyble
         SHA-256 F3:B5:A5:05:26:C5:58:04:66:75:D3:0B:72:76:EA:45:70:D6:A1:1A:56:D3:CB:29:22:25:4F:2C:7B:14:EF:8D
```

## Source map

| File | Lines | Role |
| --- | ---: | --- |
| `MainActivity.java` | 4459 | Single activity. Builds the whole shell in code: toolbar, nav bar, tab strip, menus, sheets, dialogs. Owns `WebView` tabs, session save/restore, search suggestions, downloads UI, theme application, update prompts. |
| `Tube.java` | 861 | YouTube resolver: page/player parsing, signature handling, stream selection. |
| `SettingsActivity.java` | 656 | Settings screen (theme, text size, language, images/JS toggles, clearing data, about, update check). |
| `Muxer.java` | 546 | Joins separate video/audio tracks into one MP4 with `MediaMuxer`. |
| `Downloader.java` | 406 | HTTP downloads: 4 connections per file, resume, progress, notification, cancel. |
| `Lang.java` | 321 | Bengali → English string map applied at runtime. |
| `Pot.java` | 272 | Container/format probing for downloadable media. |
| `MediaFinder.java` | — | Detects media/PDF/ZIP/APK links on the current page. |
| `DownloadInbox.java` | — | Signature-protected receiver for helper-app download results. |
| `AdBlock.java` | — | Host matching against `assets/adblock.txt`. |
| `Updater.java` | 161 | Polls `api.github.com/repos/Mamun015/skyble-browser/releases/latest`, compares versions, downloads APK. Checks on open and hourly while running. |
| `Theme.java` | 134 | Palette, dark/light handling, Material You colors. |
| `Langs.java` | — | Google Translate target-language list for the picker. |
| `BrowserRole.java` | — | Default-browser role request. |
| `ApkProvider.java` | — | FileProvider-style content URI for installing the downloaded update. |
| `Notify.java`, `Favicon.java`, `SwitchStyle.java`, `Trace.java` | — | Notifications, favicons, widget styling, logging. |

Assets: `home.html` (start page), `translate.js` (page translation), `dark.js`
(forced dark pages), `adblock.txt` (host blocklist), `icon.png`.

## Runtime flows

**Startup** — `onCreate` reads prefs (`skyble_browser`), builds the shell,
restores tabs, applies theme/palette, starts the hourly update check.
**Tabs** — `Tab` objects wrap a `WebView`; session serialized on pause/destroy.
**Translation** — `translate.js` is injected; text goes to
`translate.googleapis.com` without a key; original text is kept for restore.
**YouTube download** — `Tube` resolves streams → `Downloader` fetches video and
audio → `Muxer` joins → file lands in the public Downloads folder; `DownloadInbox`
also accepts results from the standalone `skyble-tube` app and `plugin/`.
**Update** — `Updater` compares tag to installed `versionName`, shows a prompt,
downloads the APK, and hands it to the installer through `ApkProvider`.

## Releases and website

- Tags are `v<version>`; each release carries `SkybleBrowser-<version>.apk`.
- The updater picks the first `.apk` asset of `releases/latest`.
- `docs/` is the static site (banner, screenshots, sitemap, OG image). The
  version shown on the site must be bumped with every release.
- `fdroid/com.skyble.browser.yml` is a draft for fdroiddata and still says 1.0.1.

## Roadmap (from README)

- Built-in video player
- Stronger ad filtering with a maintained filter list

## State / open items

- [x] Recover the original `keystore.jks` (update compatibility).
- [x] Move release builds to a two-key chain with a rotation lineage.
- [ ] Keep `sdk/android.jar` + `sdk/r8.jar` available on the new machine.
- [ ] F-Droid metadata is stale (1.0.1/versionCode 6).
- [ ] `Updater` User-Agent string still says `BanglaBrowser/`.
- [ ] Decide the next feature from the roadmap (video player vs. filter list).

## Related projects

- `Mamun015/skyble-tube` — standalone YouTube downloader, companion app.
- `Mamun015/bangla-browser` — earlier Bengali browser (~175 KB), shares the
  translation/UI approach.
- `Mamun015/mamun015-cloudstream-bdix`, `Mamun015/Mamun015-Zangetsu`,
  `Mamun015/cs-repos`, `Mamun015/Zangetsu-Site` — Cloudstream/BDIX work.
