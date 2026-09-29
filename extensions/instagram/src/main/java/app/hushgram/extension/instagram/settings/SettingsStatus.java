/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

/**
 * Which patches were selected for this build.
 *
 * <p>Every method answers false here. A patch that adds a feature rewrites its method to answer
 * true, so the settings screen offers only the switches this APK backs and the diagnostic report
 * lists only the patches it carries.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class SettingsStatus {
    private SettingsStatus() {
    }

    public static boolean hideAds() {
        return false;
    }

    public static boolean sanitizeSharingLinks() {
        return false;
    }

    public static boolean disableAnalytics() {
        return false;
    }

    public static boolean buildExpiredPopup() {
        return false;
    }

    public static boolean restoreTrust() {
        return false;
    }
}
