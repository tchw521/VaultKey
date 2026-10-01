package com.vaultkey.data;

import com.vaultkey.crypto.Crypto;

public final class CryptoHolder {
    public static String enc(String s) { return s == null ? "" : Crypto.encStr(Session.key(), s); }
    public static String dec(String s) { return s == null ? "" : Crypto.decStr(Session.key(), s); }
}
