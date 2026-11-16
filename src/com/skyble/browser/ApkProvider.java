package com.skyble.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * শুধু ব্রাউজারের নিজের আপডেট APK-টা ইনস্টলারকে পড়তে দেয়। ফাইলটা অ্যাপের
 * নিজের ক্যাশে থাকে, তাই Android 7+ এর ফাইল-URI নিষেধাজ্ঞাও লাগে না।
 */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.skyble.browser.apk";
    /** কোন ফাইলটা দিতে হবে — ইনস্টল শুরুর আগে মেইন থ্রেডে সেট হয় */
    static File shared;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = shared;
        if (f == null || !f.exists()) throw new FileNotFoundException("no apk");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
