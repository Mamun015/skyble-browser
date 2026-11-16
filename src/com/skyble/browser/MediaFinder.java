package com.skyble.browser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds downloadable media / file streams on the current page.
 */
final class MediaFinder {

    private MediaFinder() { }

    public static final String JS = "(function(){\n  try {\n    var out = [];\n    var seen = {};\n    function skip(u){ return !u || /^blob:|^data:|^about:|^javascript:/i.test(u); }\n    function baseName(u){ try { var x = new URL(u, location.href); var p = x.pathname; var i = p.lastIndexOf('/'); return i >= 0 ? p.slice(i + 1) : p; } catch (e) { return ''; } }\n    function extOf(u){ try { var x = new URL(u, location.href); var p = x.pathname.toLowerCase(); var i = p.lastIndexOf('.'); return i >= 0 ? p.slice(i) : ''; } catch (e) { return ''; } }\n    var exts = ['.mp4','.m4v','.webm','.mkv','.mp3','.m4a','.aac','.ogg','.wav','.m3u8','.mpd','.pdf','.zip','.apk'];\n    function isExt(u){ return exts.indexOf(extOf(u)) >= 0; }\n    function add(u, k, l, s){\n      if (!u) return;\n      try { u = new URL(u, location.href).href; } catch (e) {}\n      if (skip(u)) return;\n      if (seen[u]) return;\n      if (out.length >= 40) return;\n      seen[u] = true;\n      l = String(l == null ? '' : l).replace(/[\\r\\n]+/g, ' ').trim();\n      if (l.length > 70) l = l.slice(0, 70);\n      var n = Number(s); if (!isFinite(n) || n < 0) n = 0;\n      out.push({u: String(u), k: String(k), l: l, s: n});\n    }\n    // media elements\n    try {\n      var medias = document.querySelectorAll('video,audio');\n      for (var i = 0; i < medias.length; i++) {\n        var el = medias[i];\n        var kind = (el.tagName || '').toLowerCase() === 'audio' ? 'audio' : 'video';\n        var label = el.getAttribute('title') || el.getAttribute('aria-label') || '';\n        if (!label) { var poster = el.getAttribute('poster'); if (poster) label = baseName(poster); }\n        if (!label) label = (el.tagName || '').toLowerCase();\n        var srcs = [];\n        if (el.currentSrc) srcs.push(el.currentSrc);\n        if (el.src) srcs.push(el.src);\n        var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];\n        for (var j = 0; j < sources.length; j++) { if (sources[j].src) srcs.push(sources[j].src); }\n        for (var j = 0; j < srcs.length; j++) add(srcs[j], kind, label, 0);\n      }\n    } catch (e) {}\n    // anchors\n    try {\n      var as = document.querySelectorAll('a[href]');\n      for (var i = 0; i < as.length; i++) {\n        var a = as[i];\n        var href = a.getAttribute('href');\n        if (!href) continue;\n        var u;\n        try { u = new URL(href, location.href).href; } catch (e) { continue; }\n        if (!isExt(u)) continue;\n        var e = extOf(u);\n        var k = (e === '.m3u8' || e === '.mpd') ? 'stream' : 'file';\n        var txt = (a.textContent || '').trim();\n        var label = txt || baseName(u);\n        add(u, k, label, 0);\n      }\n    } catch (e) {}\n    // performance resources\n    try {\n      var entries = performance.getEntriesByType('resource');\n      var perf = [];\n      for (var i = 0; i < entries.length; i++) {\n        var en = entries[i];\n        var name = en.name;\n        if (!name || !isExt(name)) continue;\n        var size = en.transferSize || en.encodedBodySize || 0;\n        if (!(size > 0)) continue;\n        perf.push({name: name, size: size});\n      }\n      perf.sort(function(a, b){ return b.size - a.size; });\n      for (var i = 0; i < perf.length; i++) {\n        var e = extOf(perf[i].name);\n        var k;\n        if (['.mp4','.m4v','.webm','.mkv'].indexOf(e) >= 0) k = 'video';\n        else if (['.mp3','.m4a','.aac','.ogg','.wav'].indexOf(e) >= 0) k = 'audio';\n        else if (e === '.m3u8' || e === '.mpd') k = 'stream';\n        else k = 'file';\n        var u;\n        try { u = new URL(perf[i].name, location.href).href; } catch (e2) { u = perf[i].name; }\n        add(u, k, baseName(u), perf[i].size);\n      }\n    } catch (e) {}\n    return JSON.stringify(out);\n  } catch (e) { return '[]'; }\n})();";

    public static List<String[]> parse(String jsResult) {
        List<String[]> out = new ArrayList<>();
        try {
            if (jsResult == null) return out;
            String text = jsResult.trim();
            if (text.isEmpty() || text.equals("null")) return out;
            Object first;
            try {
                first = new JSONTokener(text).nextValue();
            } catch (Exception e) {
                return out;
            }
            String json;
            if (first instanceof String) {
                json = (String) first;
            } else if (first == JSONObject.NULL) {
                return out;
            } else {
                json = text;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o;
                try {
                    o = arr.getJSONObject(i);
                } catch (Exception e) {
                    continue;
                }
                String u = o.optString("u", "");
                String k = o.optString("k", "");
                String l = o.optString("l", "");
                long sz = 0;
                try {
                    if (o.has("s")) sz = o.optLong("s", 0);
                } catch (Exception e) {
                    sz = 0;
                }
                if (u == null) continue;
                if (u.length() > 2000) continue;
                if (!u.startsWith("http://") && !u.startsWith("https://")) continue;
                String sizeText = (sz > 0) ? humanSize(sz) : "";
                out.add(new String[]{u, k, l, sizeText});
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    public static String titleFor(String kind, String label) {
        if (label != null) {
            String t = label.trim();
            if (!t.isEmpty()) return t;
        }
        String k = (kind == null) ? "" : kind.toLowerCase(Locale.US);
        if (k.equals("video")) return Lang.t("ভিডিও");
        if (k.equals("audio")) return Lang.t("অডিও");
        if (k.equals("stream")) return Lang.t("স্ট্রিম");
        return Lang.t("ফাইল");
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
