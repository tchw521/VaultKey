package com.vaultkey.data;

public final class Session {
    private static byte[] key;
    private static long lastTouch;
    public static byte[] key() { return key; }
    public static void set(byte[] k) { key = k; lastTouch = System.currentTimeMillis(); }
    public static void touch() { lastTouch = System.currentTimeMillis(); }
    public static void lock() { key = null; }
    public static boolean expired(long timeoutMs) {
        return key == null || (System.currentTimeMillis() - lastTouch) > timeoutMs;
    }
}
