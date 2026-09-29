/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Helper for the "Disable analytics" patch. */
public final class Analytics {

    private Analytics() {}

    /**
     * Where the events go instead: the discard port on this phone's own loopback address. Nothing
     * listens there, so a connection is refused at once, the same failure an upload meets with no
     * network, and nothing leaves the phone.
     */
    static final String REFUSED = "https://127.0.0.1:9";

    /**
     * Injected where Instagram picks the address it uploads usage events to, its own logging
     * endpoints and Facebook's graph endpoint. Answers [url] with its host swapped for
     * {@link #REFUSED} while the switch is on, or [url] as it came. Never throws.
     */
    public static String endpoint(String url) {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (url == null || !Utils.settingsReady() || !Settings.DISABLE_ANALYTICS.get()) return url;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "switch read", t);
            return url;
        }
        return refused(url);
    }

    /** [url] with its scheme and host replaced by {@link #REFUSED}, keeping the path and query. */
    static String refused(String url) {
        int scheme = url.indexOf("://");
        int path = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        return REFUSED + (path < 0 ? "/" : url.substring(path));
    }
}
