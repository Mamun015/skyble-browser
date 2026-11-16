package com.skyble.browser;

import android.content.Context;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Domain blocklist loaded from assets/adblock.txt. */
final class AdBlock {

    private static final String ASSET = "adblock.txt";
    private static final Object LOCK = new Object();
    private static volatile Set<String> domains = null;
    private static volatile boolean loaded = false;

    private AdBlock() { }

    /** Loads the list once; safe to call from any thread and more than once. */
    static void init(Context c) {
        if (loaded || c == null) return;
        synchronized (LOCK) {
            if (loaded) return;
            Set<String> set = new HashSet<>(8192);
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(
                        new InputStreamReader(c.getAssets().open(ASSET), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String host = line.trim();
                    if (host.isEmpty() || host.charAt(0) == '#') continue;
                    set.add(host.toLowerCase(Locale.US));
                }
            } catch (IOException | RuntimeException e) {
                // Missing or unreadable asset: keep browsing unblocked.
            } finally {
                close(reader);
            }
            domains = set;
            loaded = true;
        }
    }

    /** True when the host or one of its parent domains is on the list. */
    static boolean isBlocked(String host) {
        Set<String> set = domains;
        if (set == null || host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return false;
        if (set.contains(h)) return true;
        int dot = h.indexOf('.');
        for (int step = 0; dot >= 0 && step < 5; step++) {
            h = h.substring(dot + 1);
            if (h.isEmpty()) return false;
            if (set.contains(h)) return true;
            dot = h.indexOf('.');
        }
        return false;
    }

    /** Number of loaded domains, for diagnostics. */
    static int size() {
        Set<String> set = domains;
        return set == null ? 0 : set.size();
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
