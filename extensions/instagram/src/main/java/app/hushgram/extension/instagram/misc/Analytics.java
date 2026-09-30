/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Application;

import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
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

    /** How many different addresses and outcomes the debug log names, per process. */
    static final int LOGGED_ADDRESSES = 16;

    private static final String SOURCE = "Analytics";
    private static final Set<String> LOGGED = new HashSet<>();

    /**
     * Injected where Instagram picks the address it uploads usage events to, its own logging
     * endpoints and Facebook's graph endpoint. Answers [url] with its host swapped for
     * {@link #REFUSED} while the switch is on, or [url] as it came. Never throws. The debug log
     * names each address the first time this process sees it, with what happened to it, so a
     * report shows what the server handed over and which process asked.
     */
    public static String endpoint(String url) {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (url == null) return null;
            if (!Utils.settingsReady()) {
                log(url, "went out as it came, the settings weren't ready");
                return url;
            }
            if (!Settings.DISABLE_ANALYTICS.get()) {
                log(url, "went out as it came, the switch is off");
                return url;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "switch read", t);
            return url;
        }
        log(url, "refused");
        return refused(url);
    }

    /** Logs [url], without its query, and [what] happened to it, once per process. */
    private static void log(String url, String what) {
        try {
            String address = withoutQuery(url);
            synchronized (LOGGED) {
                if (LOGGED.size() >= LOGGED_ADDRESSES || !LOGGED.add(address + " " + what)) return;
            }
            String process = Application.getProcessName();
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Disable analytics: " + address + " " + what + " (" + process + ")");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "log", t);
        }
    }

    /** [url] up to its query or fragment: a token rides in the query, never in the host or path. */
    static String withoutQuery(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = query;
        if (fragment >= 0 && fragment < end) end = fragment;
        return url.substring(0, end);
    }

    /** Forgets the logged addresses. For tests. */
    static void forget() {
        synchronized (LOGGED) {
            LOGGED.clear();
        }
    }

    /** [url] with its scheme and host replaced by {@link #REFUSED}, keeping the path and query. */
    static String refused(String url) {
        int scheme = url.indexOf("://");
        int path = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        return REFUSED + (path < 0 ? "/" : url.substring(path));
    }
}
