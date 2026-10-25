package com.n6.foldduo;

import rikka.shizuku.ShizukuProvider;

/**
 * ShizukuProvider tries to initialize Sui (a separate library we don't ship) in onCreate, which
 * crashes the app at startup with NoClassDefFoundError. Turn that off before onCreate runs.
 */
public class ShizukuBinderProvider extends ShizukuProvider {
    static {
        ShizukuProvider.disableAutomaticSuiInitialization();
    }
}
