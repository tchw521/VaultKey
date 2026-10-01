package com.vaultkey.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static SharedPreferences p;
    public static void init(Context c) { p = c.getSharedPreferences("vk", Context.MODE_PRIVATE); }
    public static String get(String k, String d) { return p.getString(k, d); }
    public static String getS(String k, String d) { return p.getString(k, d); }
    public static void putS(String k, String v) { p.edit().putString(k, v).apply(); }
    public static void put(String k, String v) { p.edit().putString(k, v).apply(); }
    public static boolean getB(String k, boolean d) { return p.getBoolean(k, d); }
    public static void putB(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
    public static int getI(String k, int d) { return p.getInt(k, d); }
    public static void putI(String k, int v) { p.edit().putInt(k, v).apply(); }
    public static void remove(String k) { p.edit().remove(k).apply(); }
    public static boolean has(String k) { return p.contains(k); }
}
