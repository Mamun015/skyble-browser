package com.skyble.browser;

import android.content.res.ColorStateList;
import android.os.Build;
import android.widget.Switch;

/** সুইচ: চালু থাকলে অ্যাকসেন্ট, বন্ধ থাকলে ধূসর — ম্যাটেরিয়াল ইউ রঙের সাথে মেলে। */
final class SwitchStyle {

    private SwitchStyle() { }

    private static final int[][] STATES = new int[][]{
            {android.R.attr.state_checked},
            {-android.R.attr.state_checked}
    };

    static void apply(Switch sw, int accent, int onAccent, int trackOff, int thumbOff) {
        if (Build.VERSION.SDK_INT < 21) return;
        // বন্ধ সুইচের থাম্বও যেন দেখা যায় — নইলে ট্র্যাকের সাথে মিশে যায়
        sw.setThumbTintList(new ColorStateList(STATES, new int[]{onAccent, thumbOff}));
        sw.setTrackTintList(new ColorStateList(STATES, new int[]{accent, trackOff}));
    }
}
