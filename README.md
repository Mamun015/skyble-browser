# Skyble Browser

A fast, light, private Android browser. Built for everyone.

Skyble Browser is a small WebView browser with a clean interface, tabs, an
ad blocker, in-app downloads, a read-friendly dark mode and a built-in
update check. The whole app is under 200 KB.

- Bangla and English interface
- Up to 8 tabs with a session that comes back after a restart
- Ad blocker with a host list
- In-app downloads with progress, speed and pause
- Dark mode: follow the system, always light, always dark, or force every
  page dark
- Built-in update check against GitHub releases
- No tracking, no analytics, no ads

## Download

Grab the latest APK from the [releases page](https://github.com/Mamun015/skyble-browser/releases).
Android 5.0 or newer is required.

## Build

No Gradle, no Android Studio. The build needs the Android SDK tools on
`PATH` (`aapt`, `zipalign`, `apksigner`), a JDK and `sdk/android.jar`.

```bash
bash build.sh
```

`build.sh` compiles, dexes with R8, packs, signs and verifies the APK.

## How it works

The app does not carry a rendering engine. Pages are drawn by the
Android System WebView that ships with the phone, which keeps the APK tiny
and the browser up to date with the system. A few sites refuse embedded
browsers; that is a WebView limit, not a bug in this app.

## Project layout

```
src/com/skyble/browser/   Java sources
assets/home.html          start page
assets/dark.js            dark mode for web pages
assets/translate.js       page translation
res/                      icons and resources
build.sh                  build script
```

## License

MPL-2.0. See `LICENSE` and `NOTICE`. The name and icon are not covered by
the license.

---

## বাংলায়

দ্রুত, হালকা আর নিরাপদ অ্যান্ড্রয়েড ব্রাউজার। ট্যাব, অ্যাড ব্লকার,
ইন-অ্যাপ ডাউনলোড, বাংলা ইন্টারফেস আর আপডেট চেক — সব একসাথে, ২০০ কিলোবাইটের কমে।

APK নামিয়ে ইনস্টল করলেই চলবে। Android ৫.০ বা তার পরের ভার্সন লাগবে।
