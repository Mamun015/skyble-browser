# Skyble Tube

Small helper app that downloads YouTube videos, called from Skyble Browser or opened
from any app by sharing a link. Standalone too: it has its own launcher icon.

## Build

```sh
./plugin/build.sh
```

No Gradle. Uses the same toolchain and signing chain as the browser
(`../sdk/android.jar`, `../sdk/r8.jar`, `../sign.sh`).
Output: `plugin/SkybleTubePlugin-1.0.apk`.

The YouTube resolver is NewPipeExtractor (v0.24.6, ANDROID InnerTube client) from
`plugin/libs/`. Its bytecode needs Java 8+ APIs that Android only has from API 24/26
onwards, so the app is built with core library desugaring: D8 gets `--desugared-lib`
and the j$ runtime is dexed by L8 from `plugin/libs/desugar_jdk_libs-2.0.4.jar`.
The app ships as `classes.dex` plus `classes2.dex` (native multidex, minSdk 21).

## How the browser calls it

```java
Intent i = new Intent("com.skyble.tube.plugin.DOWNLOAD");
i.putExtra("url", "https://www.youtube.com/watch?v=...");
i.setPackage("com.skyble.tube.plugin");
startActivity(i);
```

It also answers `ACTION_SEND` (`text/plain`) and `ACTION_VIEW` for YouTube links.

## Notes

- Streams come from NewPipeExtractor first: the muxed 360p file, plus 720p and 1080p
  picture tracks that are downloaded next to a separate m4a audio track. If that
  fails, the older Piped and Invidious front-ends are tried as a fallback.
- Rows with an empty audio URL are single files; rows with an audio URL are joined
  afterwards by `Muxer` (MediaMuxer) into one mp4.
- The chosen file goes through the system DownloadManager into `Downloads/`.
- Formats can disappear when YouTube or the front-ends refuse the request; the app
  then says so instead of failing halfway.

## License

This helper app is licensed **GPL-3.0-or-later**, because it bundles
NewPipeExtractor (GPL-3.0-or-later, Copyright (C) 2015 Christian Schabesberger and
the NewPipe e.V. contributors). The licence text is in `plugin/LICENSE`, the
attribution in `plugin/NOTICE`. The "NewPipe" name and logo are not used as this
app's name or icon.

The browser itself stays under its own licence (MPL-2.0) and is not affected by
this.
