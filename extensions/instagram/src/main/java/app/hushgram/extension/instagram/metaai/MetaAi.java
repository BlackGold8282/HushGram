/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide Meta AI" patch.
 *
 * <p>Instagram decides whether its search bars offer Meta AI from server flags. On 449 one flag
 * covers the Search tab's bar ("Search with Meta AI"), its results and Meta AI's answers there, and
 * two more the bar at the top of your messages: its "Search or ask Meta AI" hint, and the Meta AI
 * ring at its end. The patch passes every read of them through {@link #searchFlag}, which answers no
 * while its switch is on, the answer an account without Meta AI gets.
 *
 * <p>The home feed's Meta AI units go through {@link #filter} at the feed's parse helper, the way
 * Hide suggested posts' do.
 */
public final class MetaAi {
    /**
     * The feed item kinds of Meta AI's units, by the constant names Instagram 449 gives them:
     * Vibes (its feed of AI videos), Meta AI chats ("hatch") and Imagine pictures of you ("memu").
     * Hide Reels in the feed takes out the first two as well.
     */
    static final Set<String> FEED_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")));

    /** The diagnostic counter route for the feed units. */
    static final String ROUTE = "Meta AI in the feed";

    private static volatile boolean loggedSearch;

    private MetaAi() {
    }

    /**
     * Injected right after each read of one of Meta AI's search flags. Answers false while Hide
     * Meta AI in search is on, and [enabled] otherwise, or when anything goes wrong. Never throws,
     * and never waits for the settings: before they're ready Instagram's answer stands.
     */
    public static boolean searchFlag(boolean enabled) {
        if (!enabled) return false;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return true;
            if (!loggedSearch) {
                loggedSearch = true;
                Logger.printDebug(() -> "Meta AI: search flag answered off");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "search flag", failure);
            return enabled;
        }
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for a Meta AI unit
     * while Hide Meta AI posts is on, and [item] itself otherwise, or when anything goes wrong.
     * Never throws.
     */
    public static Object filter(Object item) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            String kind = FeedItemKinds.kindIn(item, FEED_UNITS, FamilyNames.META_AI);
            if (kind == null) return item;
            FeedFilterCounters.sawKind(ROUTE, kind);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_POSTS.get()) return item;
            FeedFilterCounters.removed(ROUTE, 1, kind);
            Logger.printDebug(() -> "Meta AI: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "feed item", failure);
            return item;
        }
    }
}
