package com.skyble.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds downloadable links for a YouTube video.
 *
 * The watch page gives nothing usable, so two paths are tried in order:
 * 1. YouTube's own player API through the Android VR client, which hands back
 *    direct googlevideo links (fast, no third party in the middle).
 * 2. Public Piped and Invidious front-ends for the times YouTube refuses.
 */
final class Tube {

    private Tube() { }

    private static final String KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";

    /**
     * YouTube only serves plain file links to its own apps, so the player API is
     * called with phone app clients, one after another until one answers.
     */
    private static final String ANDROID_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    private static final String VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; "
                    + "Quest 3 Build/SQ3A.220605.009.A1) gzip";
    private static final String TS_UA =
            "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip";

    // আগে এগুলো; কাজ না করলে সরাসরি ইনস্ট্যান্স তালিকা থেকেও খোঁজা হয়
    private static final String[] HOSTS = {
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.drgns.space"
    };

    private static final String[] INV_HOSTS = {
            "https://inv.nadeko.net",
            "https://invidious.f5.si",
            "https://yewtu.be"
    };

    private static final String INV_LIST = "https://api.invidious.io/instances.json?sort_by=health";

    private static final Pattern V_PARAM = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern SHORTS = Pattern.compile("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})");
    private static final Pattern BARE = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** শেষবার কেন আটকাল, সেটা পপআপে দেখানো হয় */
    static String lastError = "";

    /** কেন আটকাল, সেটা সহজ ভাষায় */
    static String friendlyError() {
        String e = lastError == null ? "" : lastError.toLowerCase(Locale.US);
        if (e.contains("bot") || e.contains("sign in")) {
            return "YouTube asked for a robot check. Try again later or on another network.";
        }
        if (e.length() == 0) return "";
        return "YouTube will not hand this video to another app. That happens with some music and licensed videos.";
    }

    /** পেজটা ইউটিউবের কি না */
    static boolean isYouTube(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("youtube.com") || u.contains("youtu.be")
                || u.contains("youtube-nocookie.com");
    }

    /** ভিডিওর আইডি — ইউটিউব লিংক না হলে null */
    static String videoId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!isYouTube(u)) return null;
        Matcher m = V_PARAM.matcher(u);
        if (m.find()) return m.group(1);
        m = SHORTS.matcher(u);
        if (m.find()) return m.group(1);
        int cut = u.indexOf('?');
        if (cut > 0) u = u.substring(0, cut);
        cut = u.indexOf('#');
        if (cut > 0) u = u.substring(0, cut);
        String tail = u.substring(u.lastIndexOf('/') + 1);
        if (BARE.matcher(tail).matches()) return tail;
        return null;
    }

    /**
     * সারি: {url, quality, sizeText, audioUrl}. audioUrl খালি মানে ফাইলটা
     * একটাই (ছবি+আওয়াজ একসাথে); না খালি হলে ওই দুটো জোড়া লাগাতে হবে।
     * ভালো মান আগে।
     */
    static List<String[]> resolve(String id) {
        List<String[]> out = new ArrayList<>();
        lastError = "";
        if (id == null || id.length() != 11) return out;
        long deadline = System.currentTimeMillis() + 12000;

        try {
            if (innertube(id, out)) return out;
        } catch (Exception ignored) { }

        for (String host : HOSTS) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryPiped(host, id, out)) return out;
        }
        for (String host : INV_HOSTS) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryInvidious(host, id, out)) return out;
        }
        for (String host : invidiousFromList()) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryInvidious(host, id, out)) return out;
        }
        return out;
    }

    // ------------------------------------------------------------ YouTube

    private static String androidClient(String visitor) {
        return "{\"clientName\":\"ANDROID\",\"clientVersion\":\"20.10.38\","
                + "\"androidSdkVersion\":34,\"osName\":\"Android\",\"osVersion\":\"14\","
                + "\"hl\":\"en\",\"gl\":\"US\",\"utcOffsetMinutes\":0"
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static String vrClient() {
        return "{\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"1.60.19\","
                + "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\","
                + "\"osName\":\"Android\",\"osVersion\":\"12\",\"androidSdkVersion\":32,"
                + "\"hl\":\"en\",\"gl\":\"US\",\"utcOffsetMinutes\":0}";
    }

    private static String testsuiteClient(String visitor) {
        return "{\"clientName\":\"ANDROID_TESTSUITE\",\"clientVersion\":\"1.9\","
                + "\"androidSdkVersion\":30,\"osName\":\"Android\",\"osVersion\":\"11\","
                + "\"hl\":\"en\",\"gl\":\"US\""
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static boolean innertube(String id, List<String[]> out) {
        List<String[]> vos = playerVisionOs(id);
        if (!vos.isEmpty()) {
            out.addAll(vos);
            addProgressiveFallback(id, out);
            return true;
        }
        String vd = visitorData();
        String[][] clients = {
                {androidClient(vd), "3", "20.10.38", ANDROID_UA},
                {vrClient(), "28", "1.60.19", VR_UA},
                {testsuiteClient(vd), "30", "1.9", TS_UA}
        };
        for (String[] c : clients) {
            List<String[]> got = player(id, c[0], c[1], c[2], c[3]);
            if (got.isEmpty()) continue;
            out.addAll(got);
            return true;
        }
        return false;
    }

    /**
     * একটা ক্লায়েন্ট দিয়েই তালিকা: ০ = visionOS, ১ = ফোনের অ্যাপ, ২ = VR, ৩ = টেস্ট সুইট।
     * ডাউনলোড আটকালে অন্য ক্লায়েন্টের লিংক দিয়ে চেষ্টা করা হয়।
     */
    static List<String[]> resolveClient(String id, int which) {
        List<String[]> out = new ArrayList<>();
        if (id == null || id.length() != 11) return out;
        if (which == 0) {
            try {
                out.addAll(playerVisionOs(id));
            } catch (Exception ignored) { }
            if (!out.isEmpty()) return out;
        }
        String vd = visitorData();
        String[][] clients = {
                {androidClient(vd), "3", "20.10.38", ANDROID_UA},
                {vrClient(), "28", "1.60.19", VR_UA},
                {testsuiteClient(vd), "30", "1.9", TS_UA}
        };
        String[] c = clients[Math.max(0, Math.min(clients.length - 1, which - 1))];
        try {
            out.addAll(player(id, c[0], c[1], c[2], c[3]));
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * visionOS শুধু আলাদা ছবি/আওয়াজ দেয়। জোড়া লাগাতে না পারলে যাতে আটকে না
     * যায়, তার জন্য সবচেয়ে নিচু মানের একটা এক-ফাইল (৩৬০p) সারি জুড়ে দেওয়া হয়।
     */
    private static void addProgressiveFallback(String id, List<String[]> out) {
        try {
            int lowest = Integer.MAX_VALUE;
            for (String[] r : out) {
                int rk = rank(r[1]);
                if (rk > 0 && rk < lowest) lowest = rk;
            }
            String vd = visitorData();
            for (String[] r : player(id, androidClient(vd), "3", "20.10.38", ANDROID_UA)) {
                if (r[3] != null && r[3].length() > 0) continue;
                if (rank(r[1]) <= 0 || rank(r[1]) >= lowest) continue;
                out.add(r);
            }
        } catch (Exception ignored) { }
    }

    private static String VISITOR;

    /** একবারই আনা হয়; ইউটিউব এটা দিয়ে নতুন ব্যবহারকারী ধরে নেয় */
    private static String visitorData() {
        if (VISITOR != null) return VISITOR.length() == 0 ? null : VISITOR;
        VISITOR = "";
        String resp = post("https://www.youtube.com/youtubei/v1/visitor_id?key=" + KEY,
                "{\"context\":{\"client\":" + androidClient(null) + "}}",
                ANDROID_UA, "3", "20.10.38");
        if (resp == null) return null;
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject ctx = o.optJSONObject("responseContext");
            String vd = ctx == null ? "" : ctx.optString("visitorData", "");
            if (vd.length() == 0) vd = o.optString("visitorData", "");
            if (vd.length() > 0) VISITOR = vd;
        } catch (Exception ignored) { }
        return VISITOR.length() == 0 ? null : VISITOR;
    }

    /** এক ক্লায়েন্ট দিয়ে প্লেয়ার API; যা পাওয়া গেল তার তালিকা ফেরায় */
    private static List<String[]> player(String id, String clientJson, String clientName,
                                         String clientVersion, String ua) {
        return player(id, clientJson, clientName, clientVersion, ua, null);
    }

    private static List<String[]> player(String id, String clientJson, String clientName,
                                         String clientVersion, String ua, String extraCtx) {
        List<String[]> found = new ArrayList<>();
        String body = "{\"context\":{\"client\":" + clientJson
                + ",\"user\":{\"lockedSafetyMode\":false},\"request\":{\"useSsl\":true}"
                + (extraCtx == null || extraCtx.length() == 0 ? "" : "," + extraCtx) + "},"
                + "\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
        String resp = post("https://www.youtube.com/youtubei/v1/player?key=" + KEY, body,
                ua, clientName, clientVersion);
        return parsePlayer(resp, clientName);
    }

    /** player API-র উত্তর থেকে ডাউনলোডযোগ্য সারি বানায় */
    private static List<String[]> parsePlayer(String resp, String clientName) {
        List<String[]> found = new ArrayList<>();
        if (resp == null) return found;
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject status = o.optJSONObject("playabilityStatus");
            if (status != null && !"OK".equals(status.optString("status"))) {
                String reason = status.optString("reason", "");
                if (reason.length() > 0) lastError = reason;
                Trace.w("tube", "client " + clientName + " status="
                        + status.optString("status") + " reason=" + reason);
                return found;
            }
            JSONObject sd = o.optJSONObject("streamingData");
            if (sd == null) return found;

            String audio = null;
            long audioLen = 0;
            JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
            if (adaptive != null) {
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f = adaptive.optJSONObject(i);
                    if (f == null) continue;
                    String mime = f.optString("mimeType", "");
                    if (!mime.startsWith("audio/mp4")) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    long len = f.optLong("contentLength", 0);
                    if (audio == null || f.optInt("itag", 0) == 140) {
                        audio = u;
                        audioLen = len;
                        if (f.optInt("itag", 0) == 140) break;
                    }
                }
            }

            // ছবি+আওয়াজ একসাথে: সাধারণত ৩৬০p, একটাই ফাইল
            JSONArray fm = sd.optJSONArray("formats");
            if (fm != null) {
                for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.optJSONObject(i);
                    if (f == null) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    if (!f.optString("mimeType", "").startsWith("video/mp4")) continue;
                    String q = f.optString("qualityLabel", "360p");
                    found.add(new String[]{u, cleanQuality(q),
                            sizeText(f.optLong("contentLength", 0)), ""});
                }
            }

            // ৭২০p/১০৮০p: ছবি আর আওয়াজ আলাদা, পরে জোড়া লাগাতে হয়
            if (adaptive != null && audio != null) {
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f = adaptive.optJSONObject(i);
                    if (f == null) continue;
                    String mime = f.optString("mimeType", "");
                    if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue;
                    int h = f.optInt("height", 0);
                    String label = f.optString("qualityLabel", "");
                    // উচ্চতা আর লেবেলের সংখ্যা সবসময় মেলে না (480p = 450), তাই লেবেল দেখে
                    if (rank(label) > 0 ? rank(label) < 480 : h < 480) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    long len = f.optLong("contentLength", 0);
                    if (label.length() == 0) label = h + "p";
                    found.add(new String[]{u, cleanQuality(label), sizeText(len + audioLen), audio});
                }
            }

            if (found.isEmpty()) {
                Trace.w("tube", "client " + clientName + " no formats, hls="
                        + (sd.optString("hlsManifestUrl", "").length() > 0));
                return found;
            }
            found.sort((a, b) -> rank(b[1]) - rank(a[1]));
            List<String[]> trimmed = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (String[] f : found) {
                if (seen.contains(f[1])) continue;
                seen.add(f[1]);
                trimmed.add(f);
                if (trimmed.size() >= 4) break;
            }
            for (String[] r : trimmed) {
                Trace.w("tube", "  row " + r[1] + " itag=" + r[0].replaceAll(".*itag=([0-9]+).*", "$1")
                        + " size=" + r[2] + (r[3] != null && r[3].length() > 0 ? " +audio" : ""));
            }
            return trimmed;
        } catch (Exception e) {
            return found;
        }
    }

    // ------------------------------------------------------- visionOS ক্লায়েন্ট

    /**
     * YouTube-এর visionOS অ্যাপ যে ক্লায়েন্ট ব্যবহার করে সেটাই এখন সবচেয়ে
     * নির্ভরযোগ্য: লিংক এলে ফাইলের শেষ পর্যন্ত চলে, poToken লাগে না।
     * শুধু নতুন visitorData দরকার, নইলে YouTube "Sign in to confirm" দেয়।
     */
    static final String VOS_UA =
            "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 "
                    + "like Mac OS X; US)";

    private static final String GAPIS = "https://youtubei.googleapis.com/youtubei/v1/";

    private static String visionClient(String visitor) {
        return "{\"clientName\":\"VISIONOS\",\"clientVersion\":\"1.04\",\"clientScreen\":\"WATCH\","
                + "\"platform\":\"MOBILE\",\"deviceMake\":\"Apple\","
                + "\"deviceModel\":\"RealityDevice17,1\",\"osName\":\"visionOS\","
                + "\"osVersion\":\"26.6.0.23O770\",\"hl\":\"en\",\"gl\":\"US\","
                + "\"utcOffsetMinutes\":0"
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static String visionCtx(String visitor) {
        return "{\"client\":" + visionClient(visitor)
                + ",\"request\":{\"internalExperimentFlags\":[],\"useSsl\":true}"
                + ",\"user\":{\"lockedSafetyMode\":false}}";
    }

    private static String VISION_VISITOR;

    /** visionOS-এর নিজের visitorData; না হলে প্লেয়ার কিছু দেয় না */
    private static String visionVisitor(boolean fresh) {
        if (!fresh && VISION_VISITOR != null) {
            return VISION_VISITOR.length() == 0 ? null : VISION_VISITOR;
        }
        VISION_VISITOR = "";
        String resp = postGapis(GAPIS + "visitor_id?prettyPrint=false",
                "{\"context\":" + visionCtx(null) + "}");
        if (resp == null) return null;
        try {
            JSONObject ctx = new JSONObject(resp).optJSONObject("responseContext");
            String vd = ctx == null ? "" : ctx.optString("visitorData", "");
            if (vd.length() > 0) VISION_VISITOR = vd;
            Trace.w("tube", "vision visitorData " + (vd.length() == 0 ? "missing" : "ok"));
        } catch (Exception ignored) { }
        return VISION_VISITOR.length() == 0 ? null : VISION_VISITOR;
    }

    private static final String T_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** ইউটিউবের চাওয়া ছোট র‍্যান্ডম প্যারামিটার */
    private static String tParam() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(T_ALPHABET.charAt(r.nextInt(T_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static List<String[]> playerVisionOs(String id) {
        for (int tryNo = 0; tryNo < 2; tryNo++) {
            String vd = visionVisitor(tryNo > 0);
            if (vd == null) return new ArrayList<>();
            String body = "{\"context\":" + visionCtx(vd) + ",\"videoId\":\"" + id
                    + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
            String resp = postGapis(GAPIS + "player?prettyPrint=false&t=" + tParam()
                    + "&id=" + id, body);
            if (resp == null) return new ArrayList<>();
            List<String[]> got = parsePlayer(resp, "101");
            if (!got.isEmpty()) return got;
            String e = lastError == null ? "" : lastError.toLowerCase(Locale.US);
            if (!e.contains("bot") && !e.contains("sign in")) return got;
            Trace.w("tube", "vision visitor stale, fetching a new one");
        }
        return new ArrayList<>();
    }

    /** visionOS API: কীলেস key নেই, কয়েকটা বিশেষ হেডার লাগে */
    private static String postGapis(String url, String body) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(5000);
            cn.setReadTimeout(9000);
            cn.setRequestMethod("POST");
            cn.setDoOutput(true);
            cn.setRequestProperty("Content-Type", "application/json");
            cn.setRequestProperty("User-Agent", VOS_UA);
            cn.setRequestProperty("X-Goog-Api-Format-Version", "2");
            OutputStream os = cn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = cn.getResponseCode();
            if (code < 200 || code >= 300) {
                Trace.w("tube", "vision api HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(cn.getInputStream(), "UTF-8"));
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 8000000) break;
            }
            r.close();
            Trace.w("tube", "vision api ok, " + sb.length() + " chars");
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Trace.w("tube", "vision api failed: " + e);
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    private static String post(String url, String body, String ua, String clientName,
                               String clientVersion) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(3500);
            cn.setReadTimeout(5000);
            cn.setRequestMethod("POST");
            cn.setDoOutput(true);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("Content-Type", "application/json");
            cn.setRequestProperty("User-Agent", ua);
            cn.setRequestProperty("X-YouTube-Client-Name", clientName);
            cn.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            OutputStream os = cn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = cn.getResponseCode();
            if (code < 200 || code >= 300) {
                Trace.w("tube", "player api " + clientName + " HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(cn.getInputStream(), "UTF-8"));
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 8000000) break;
            }
            r.close();
            Trace.w("tube", "player api " + clientName + "/" + clientVersion
                    + " ok, " + sb.length() + " chars");
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Trace.w("tube", "player api " + clientName + " failed: " + e);
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    // ------------------------------------------------------------ front-ends

    private static boolean tryPiped(String host, String id, List<String[]> out) {
        String body = get(host + "/streams/" + URLEncoder.encode(id));
        return body != null && addPiped(body, out);
    }

    private static boolean tryInvidious(String host, String id, List<String[]> out) {
        String body = get(host + "/api/v1/videos/" + URLEncoder.encode(id)
                + "?fields=title,formatStreams");
        return body != null && addInvidious(body, out);
    }

    private static List<String> invidiousFromList() {
        List<String> hosts = new ArrayList<>();
        try {
            String body = get(INV_LIST);
            if (body == null) return hosts;
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && hosts.size() < 4; i++) {
                JSONArray pair = arr.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                JSONObject info = pair.optJSONObject(1);
                if (info == null || !info.optBoolean("api", false)) continue;
                String uri = info.optString("uri", "");
                if (uri.length() > 8) hosts.add(trimSlash(uri));
            }
        } catch (Exception ignored) { }
        return hosts;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Piped: videoOnly=false মানে ছবি+আওয়াজ একসাথে */
    private static boolean addPiped(String body, List<String[]> out) {
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("error") && !o.isNull("error")) return false;
            JSONArray arr = o.optJSONArray("videoStreams");
            if (arr == null) return false;
            List<String[]> found = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || s.optBoolean("videoOnly", true)) continue;
                String url = s.optString("url", "");
                String mime = s.optString("mimeType", "");
                if (url.length() == 0 || !mime.startsWith("video/mp4")) continue;
                if (!downloadable(url)) continue;
                String q = s.optString("quality", "");
                if (q.length() == 0) q = s.optString("qualityLabel", "");
                long len = 0;
                try {
                    len = s.optLong("contentLength", 0);
                } catch (Exception ignored) { }
                found.add(new String[]{url, cleanQuality(q), len > 0 ? sizeText(len) : "", ""});
            }
            return finish(found, out);
        } catch (Exception e) {
            return false;
        }
    }

    /** Invidious: formatStreams-এ ছবি+আওয়াজ একসাথে থাকে */
    private static boolean addInvidious(String body, List<String[]> out) {
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("formatStreams");
            if (arr == null) return false;
            List<String[]> found = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String url = s.optString("url", "");
                String type = s.optString("container", s.optString("type", ""));
                if (url.length() == 0 || !downloadable(url)) continue;
                if (type.toLowerCase(Locale.US).contains("webm")) continue;
                String q = s.optString("qualityLabel", s.optString("resolution", ""));
                found.add(new String[]{url, cleanQuality(q), "", ""});
            }
            return finish(found, out);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean finish(List<String[]> found, List<String[]> out) {
        if (found.isEmpty()) return false;
        found.sort((a, b) -> rank(b[1]) - rank(a[1]));
        List<String> seen = new ArrayList<>();
        for (String[] f : found) {
            if (seen.contains(f[1])) continue;
            seen.add(f[1]);
            out.add(f);
            if (out.size() >= 6) break;
        }
        return !out.isEmpty();
    }

    /** যে সার্ভার থেকে সোজা নামানো যায় (গুগলের স্ট্রিম বা ফ্রন্টএন্ডের প্রক্সি) */
    private static boolean downloadable(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.endsWith("googlevideo.com") || host.contains("piped")
                    || host.contains("invidious") || host.endsWith("youtube.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static int rank(String q) {
        if (q == null) return 0;
        String digits = q.replaceAll("[^0-9]", "");
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.length() > 4 ? digits.substring(0, 4) : digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String cleanQuality(String q) {
        if (q == null || q.length() == 0) return Lang.t("ভিডিও");
        String s = q.replace("p60", "p").replace("p30", "p").replace("p25", "p").trim();
        if (s.length() > 14) s = s.substring(0, 14);
        return s;
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String get(String url) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(3000);
            cn.setReadTimeout(4000);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("Accept", "application/json");
            cn.setRequestProperty("User-Agent", "Skyble/1.1");
            int code = cn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(cn.getInputStream(), "UTF-8"));
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 4000) sb.append(line);
            r.close();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    // ------------------------------------------- কোন ক্লায়েন্টের লিংক পুরো চলে

    private static final String IOS_UA =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    private static final String MOB_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String COBALT_UA =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold";

    /** id -> itag -> {url, userAgent} যেগুলো ফাইলের ৬০%-এও ২০৬ দেয় */
    private static final java.util.HashMap<String, java.util.HashMap<Integer, String[]>> GOOD =
            new java.util.HashMap<>();
    private static final java.util.HashSet<String> SCANNED = new java.util.HashSet<>();

    static String[] goodLink(String id, int itag) {
        synchronized (GOOD) {
            java.util.HashMap<Integer, String[]> m = GOOD.get(id);
            return m == null ? null : m.get(itag);
        }
    }

    /** পেছনে বসে সব ক্লায়েন্ট পরীক্ষা করে, লগে ফল লেখে */
    static void scanClients(final String id, final List<String[]> rows) {
        if (id == null || id.length() != 11) return;
        synchronized (SCANNED) {
            if (SCANNED.contains(id)) return;
            SCANNED.add(id);
        }
        new Thread(new Runnable() {
            public void run() {
                try { scan(id); } catch (Exception ignored) { }
            }
        }).start();
    }

    private static String[][] clientList() {
        String vd = visitorData();
        return new String[][]{
                {androidClient(vd), "3", "20.10.38", ANDROID_UA, null},
                {iosClient(), "5", "20.10.4", IOS_UA, null},
                {mwebClient(vd), "2", "2.20250312.01.00", MOB_UA, null},
                {webEmbedClient(), "56", "1.20250310.01.00", WEB_UA,
                        "\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com/\"}"},
                {tvSimplyClient(), "85", "2.0", COBALT_UA,
                        "\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com/\"}"},
                {tvClient(), "7", "7.20250312.16.00", COBALT_UA, null}
        };
    }

    private static String iosClient() {
        return "{\"clientName\":\"IOS\",\"clientVersion\":\"20.10.4\","
                + "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\","
                + "\"osVersion\":\"18.3.2.22D82\",\"hl\":\"en\",\"gl\":\"US\"}";
    }

    private static String mwebClient(String visitor) {
        return "{\"clientName\":\"MWEB\",\"clientVersion\":\"2.20250312.01.00\","
                + "\"hl\":\"en\",\"gl\":\"US\""
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static String webEmbedClient() {
        return "{\"clientName\":\"WEB_EMBEDDED_PLAYER\","
                + "\"clientVersion\":\"1.20250310.01.00\",\"clientScreen\":\"EMBED\","
                + "\"hl\":\"en\",\"gl\":\"US\"}";
    }

    private static String tvSimplyClient() {
        return "{\"clientName\":\"TVHTML5_SIMPLY_EMBEDDED_PLAYER\","
                + "\"clientVersion\":\"2.0\",\"clientScreen\":\"EMBED\",\"hl\":\"en\",\"gl\":\"US\"}";
    }

    private static String tvClient() {
        return "{\"clientName\":\"TVHTML5\",\"clientVersion\":\"7.20250312.16.00\","
                + "\"hl\":\"en\",\"gl\":\"US\"}";
    }

    /** লগে আর (টেস্টে) কনসোলেও লেখে */
    private static void say(String msg) {
        Trace.w("scan", msg);
        System.out.println("scan: " + msg);
    }

    /** টেস্ট থেকে সরাসরি চালানোর জন্য */
    static void scanNow(String id) {
        scan(id);
    }

    private static void scan(String id) {
        java.util.HashMap<Integer, String[]> best = new java.util.HashMap<>();
        for (String[] c : clientList()) {
            List<String[]> got;
            try {
                got = player(id, c[0], c[1], c[2], c[3], c.length > 4 ? c[4] : null);
            } catch (Exception e) {
                say("client " + c[1] + " failed " + e);
                continue;
            }
            if (got.isEmpty()) {
                say("client " + c[1] + " -> no rows (" + lastError + ")");
                continue;
            }
            String[] top = got.get(0);
            int itag = itagOf(top[0]);
            long size = parseSize(top[2]);
            int code = deepProbe(top[0], c[3], size);
            say("client " + c[1] + " rows=" + got.size() + " itag=" + itag
                    + " size=" + top[2] + " deep=" + code);
            if (code == 206) {
                for (String[] r : got) {
                    int v = itagOf(r[0]);
                    int a = itagOf(r[3]);
                    if (v > 0) best.put(v, new String[]{r[0], c[3]});
                    if (a > 0) best.put(a, new String[]{r[3], c[3]});
                }
            }
        }
        synchronized (GOOD) {
            GOOD.put(id, best);
        }
        say(best.isEmpty()
                ? "no client passed the deep check"
                : "good urls for itags " + best.keySet());
    }

    /** ফাইলের ৬০% জায়গায় ১ বাইট; ২০৬ মানে ওই লিংক কাটা পড়েনি */
    private static int deepProbe(String url, String ua, long size) {
        if (url == null || url.length() == 0 || size <= 0) return 0;
        long off = (long) (size * 0.6);
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(8000);
            cn.setReadTimeout(12000);
            cn.setRequestProperty("User-Agent", ua);
            cn.setRequestProperty("Range", "bytes=" + off + "-" + off);
            return cn.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    private static int itagOf(String url) {
        if (url == null) return 0;
        try {
            int i = url.indexOf("itag=");
            if (i < 0) return 0;
            i += 5;
            int j = i;
            while (j < url.length() && Character.isDigit(url.charAt(j))) j++;
            return j > i ? Integer.parseInt(url.substring(i, j)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseSize(String text) {
        if (text == null) return 0;
        try {
            String s = text.trim().toUpperCase(java.util.Locale.US);
            double mul = 1;
            if (s.endsWith("KB")) { mul = 1024; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("MB")) { mul = 1048576; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("GB")) { mul = 1073741824; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("B")) { s = s.substring(0, s.length() - 1); }
            return (long) (Double.parseDouble(s.trim()) * mul);
        } catch (Exception e) {
            return 0;
        }
    }

}
