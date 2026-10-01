package com.vaultkey.ui;

import android.app.Application;
import com.vaultkey.data.Prefs;

public final class VaultApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
    }
}
