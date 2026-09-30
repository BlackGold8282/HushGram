/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

/**
 * The name each patch goes by in Morphe Manager, which is also the family its hooks report
 * under in Hook status.
 *
 * <p>Compile-time constants, so a hook that names its family inlines the text and loads no
 * class. That matters for the hooks that can run before HushGram has a context, such as the
 * signature read Instagram makes while its content providers start: touching {@link PatchFamily}
 * there would load the settings with no context to read them from.
 */
public final class FamilyNames {
    public static final String HIDE_ADS = "Hide ads";
    public static final String SANITIZE_SHARING_LINKS = "Sanitize sharing links";
    public static final String DISABLE_ANALYTICS = "Disable analytics";
    public static final String BUILD_EXPIRED_POPUP = "Remove build expired popup";
    public static final String RESTORE_TRUST = "Restore trust on re-signed builds";
    public static final String REEL_WATCH_HISTORY = "Don't send reel watch history";
    public static final String STORY_AUTO_ADVANCE = "Stop Story auto-advance";

    private FamilyNames() {
    }
}
