package com.skyble.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Translate-এর সব ভাষা, আর ভাষা বাছার খোঁজাযুক্ত ডায়ালগ।
 * কোডগুলো Google-এর নিজের (gtx) অনুবাদ endpoint যেভাবে চায় সেভাবেই।
 */
final class Langs {

    static final String[] CODES = {
            "af", "sq", "am", "ar", "hy", "as", "ay", "az", "bm", "eu", "be", "bn", "bho",
            "bs", "bg", "ca", "ceb", "zh-CN", "zh-TW", "co", "hr", "cs", "da", "dv", "doi",
            "nl", "en", "eo", "et", "ee", "tl", "fi", "fr", "fy", "gl", "ka", "de", "el",
            "gn", "gu", "ht", "ha", "haw", "he", "hi", "hmn", "hu", "is", "ig", "ilo", "id",
            "ga", "it", "ja", "jv", "kn", "kk", "km", "rw", "gom", "ko", "kri", "ku", "ckb",
            "ky", "lo", "la", "lv", "ln", "lt", "lg", "lb", "mk", "mai", "mg", "ms", "ml",
            "mt", "mi", "mr", "mni-Mtei", "lus", "mn", "my", "ne", "no", "ny", "or", "om",
            "ps", "fa", "pl", "pt", "pa", "qu", "ro", "ru", "sm", "sa", "gd", "nso", "sr",
            "st", "sn", "sd", "si", "sk", "sl", "so", "es", "su", "sw", "sv", "tg", "ta",
            "tt", "te", "th", "ti", "ts", "tr", "tk", "ak", "uk", "ur", "ug", "uz", "vi",
            "cy", "xh", "yi", "yo", "zu"
    };

    static final String[] EN = {
            "Afrikaans", "Albanian", "Amharic", "Arabic", "Armenian", "Assamese", "Aymara",
            "Azerbaijani", "Bambara", "Basque", "Belarusian", "Bengali", "Bhojpuri",
            "Bosnian", "Bulgarian", "Catalan", "Cebuano", "Chinese (Simplified)",
            "Chinese (Traditional)", "Corsican", "Croatian", "Czech", "Danish", "Dhivehi",
            "Dogri", "Dutch", "English", "Esperanto", "Estonian", "Ewe", "Filipino",
            "Finnish", "French", "Frisian", "Galician", "Georgian", "German", "Greek",
            "Guarani", "Gujarati", "Haitian Creole", "Hausa", "Hawaiian", "Hebrew", "Hindi",
            "Hmong", "Hungarian", "Icelandic", "Igbo", "Ilocano", "Indonesian", "Irish",
            "Italian", "Japanese", "Javanese", "Kannada", "Kazakh", "Khmer", "Kinyarwanda",
            "Konkani", "Korean", "Krio", "Kurdish (Kurmanji)", "Kurdish (Sorani)", "Kyrgyz",
            "Lao", "Latin", "Latvian", "Lingala", "Lithuanian", "Luganda",
            "Luxembourgish", "Macedonian", "Maithili", "Malagasy", "Malay", "Malayalam",
            "Maltese", "Maori", "Marathi", "Meiteilon (Manipuri)", "Mizo", "Mongolian",
            "Myanmar (Burmese)", "Nepali", "Norwegian", "Chichewa", "Odia (Oriya)", "Oromo",
            "Pashto", "Persian", "Polish", "Portuguese", "Punjabi", "Quechua", "Romanian",
            "Russian", "Samoan", "Sanskrit", "Scots Gaelic", "Sepedi", "Serbian", "Sesotho",
            "Shona", "Sindhi", "Sinhala", "Slovak", "Slovenian", "Somali", "Spanish",
            "Sundanese", "Swahili", "Swedish", "Tajik", "Tamil", "Tatar", "Telugu", "Thai",
            "Tigrinya", "Tsonga", "Turkish", "Turkmen", "Twi", "Ukrainian", "Urdu", "Uyghur",
            "Uzbek", "Vietnamese", "Welsh", "Xhosa", "Yiddish", "Yoruba", "Zulu"
    };

    static final String[] BN = {
            "আফ্রিকান", "আলবেনীয়", "আমহারিক", "আরবি", "আর্মেনীয়", "অসমীয়া", "আইমারা",
            "আজারবাইজানি", "বামবারা", "বাস্ক", "বেলারুশীয়", "বাংলা", "ভোজপুরি",
            "বসনীয়", "বুলগেরীয়", "কাতালান", "সেবুয়ানো", "চীনা (সরলীকৃত)",
            "চীনা (প্রথাগত)", "কর্সিকান", "ক্রোয়েশীয়", "চেক", "ডেনিশ", "দিভেহি",
            "ডোগরি", "ডাচ", "ইংরেজি", "এস্পেরান্তো", "এস্তোনীয়", "এভে", "ফিলিপিনো",
            "ফিনিশ", "ফরাসি", "ফ্রিসীয়", "গ্যালিশীয়", "জর্জীয়", "জার্মান", "গ্রিক",
            "গুয়ারানি", "গুজরাটি", "হাইতিয়ান ক্রেওল", "হাউসা", "হাওয়াইয়ান", "হিব্রু", "হিন্দি",
            "হমং", "হাঙ্গেরীয়", "আইসল্যান্ডীয়", "ইগবো", "ইলোকানো", "ইন্দোনেশীয়", "আইরিশ",
            "ইতালীয়", "জাপানি", "জাভানীয়", "কন্নড়", "কাজাখ", "খমের", "কিনিয়ারোয়ান্ডা",
            "কোঙ্কনি", "কোরীয়", "ক্রিও", "কুর্দি (কুরমানজি)", "কুর্দি (সোরানি)", "কিরগিজ",
            "লাও", "ল্যাটিন", "লাটভিয়ান", "লিঙ্গালা", "লিথুয়ানীয়", "লুগান্ডা",
            "লুক্সেমবার্গীয়", "ম্যাসিডোনীয়", "মৈথিলি", "মালাগাসি", "মালয়", "মালায়ালাম",
            "মাল্টীয়", "মাওরি", "মারাঠি", "মৈতৈলোন (মণিপুরি)", "মিজো", "মঙ্গোলীয়",
            "বার্মিজ", "নেপালি", "নরওয়েজীয়", "চিচেওয়া", "ওড়িয়া", "ওরোমো",
            "পশতু", "ফারসি", "পোলিশ", "পর্তুগিজ", "পাঞ্জাবি", "কেচুয়া", "রোমানীয়",
            "রুশ", "সামোয়ান", "সংস্কৃত", "স্কটিশ গ্যালিক", "সেপেদি", "সার্বীয়", "সেসোথো",
            "শোনা", "সিন্ধি", "সিংহলি", "স্লোভাক", "স্লোভেনীয়", "সোমালি", "স্পেনীয়",
            "সুন্দানীয়", "সোয়াহিলি", "সুইডিশ", "তাজিক", "তামিল", "তাতার", "তেলুগু", "থাই",
            "তিগ্রিনিয়া", "সোঙ্গা", "তুর্কি", "তুর্কমেন", "তুই", "ইউক্রেনীয়", "উর্দু", "উইঘুর",
            "উজবেক", "ভিয়েতনামি", "ওয়েলশ", "খোসা", "ইডিশ", "ইওরুবা", "জুলু"
    };

    interface Pick {
        void onPick(String code);
    }

    private Langs() { }

    /** কোড থেকে ভাষার নাম; অজানা কোড হলে নিজেই কোডটা ফেরে */
    static String name(String code, boolean en) {
        if (code == null) return "";
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equalsIgnoreCase(code)) return en ? EN[i] : BN[i];
        }
        return code;
    }

    /** ভাষা বাছার ডায়ালগ — লিখে খোঁজা যায়, বাছলে কোডটা cb-তে যায় */
    static void pick(Activity act, AlertDialog.Builder builder, String title,
                     boolean en, String selected, final Pick cb) {
        final float density = act.getResources().getDisplayMetrics().density;

        final List<String> names = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        for (int i = 0; i < CODES.length; i++) {
            String nm = en ? EN[i] : BN[i];
            if (CODES[i].equalsIgnoreCase(selected)) nm = "✓ " + nm;
            names.add(nm);
            codes.add(CODES[i]);
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * density), (int) (6 * density), (int) (16 * density), 0);

        final EditText search = new EditText(act);
        search.setSingleLine(true);
        search.setHint(Lang.t("ভাষা খুঁজুন"));
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView list = new ListView(act);
        // যা দেখানো হচ্ছে তার সমান্তরাল কোড তালিকা — ফিল্টার করলেও মিলে থাকে
        final List<String> shownNames = new ArrayList<>(names);
        final List<String> shownCodes = new ArrayList<>(codes);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(act,
                android.R.layout.simple_list_item_1, shownNames);
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (330 * density)));

        builder.setTitle(title).setView(box).setNegativeButton(Lang.t("বন্ধ"), null);
        final AlertDialog dlg = builder.show();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownCodes.size()) return;
            String code = shownCodes.get(position);
            dlg.dismiss();
            if (cb != null) cb.onPick(code);
        });

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase();
                shownNames.clear();
                shownCodes.clear();
                for (int i = 0; i < names.size(); i++) {
                    String label = names.get(i);
                    if (q.length() == 0 || label.toLowerCase().contains(q)
                            || codes.get(i).toLowerCase().startsWith(q)) {
                        shownNames.add(label);
                        shownCodes.add(codes.get(i));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });
    }
}
