/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide suggested posts" patch.
 *
 * <p>The patch passes every item Instagram's home feed parse helper reads through {@link #filter},
 * beside Hide Reels in the feed's filter when both are in. Suggestions are items of their own kinds: a row
 * of accounts to follow is one of the {@link #ACCOUNT_UNITS}, and a single post or reel from an
 * account you don't follow, labeled "Suggested for you" or "Suggested Reel", is an
 * {@link #SUGGESTED_POST}, which carries its post inside it. A post from an account you follow is a
 * MEDIA item and stays. Each comes back as null while its switch is on, and every caller of that
 * helper skips a null item, the home feed's page loads and its cache of recommended posts alike.
 *
 * <p>Explore's grid doesn't go through that helper (S22, Instagram 449), so it keeps its posts.
 */
public final class FeedSuggestions {
    /**
     * The feed item kinds of suggested accounts, shops, hashtags and lists, by the constant names
     * Instagram 449 gives them. Threads' units (the ones its JSON names text_app_) aren't here.
     */
    static final Set<String> ACCOUNT_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUGGESTED_USERS", "SUGGESTED_TOP_ACCOUNTS", "SUGGESTED_PRODUCERS", "SUGGESTED_PRODUCERS_V2",
            "SUGGESTED_CLOSE_FRIENDS", "SUGGESTED_BUSINESSES", "SUGGESTED_SHOPS", "SUGGESTED_HASHTAGS",
            "SUGGESTED_SHAREABLE_LISTS", "FOLLOW_CHAIN_USERS", "TYA_SUGGESTIONS_IN_FEED_UNIT")));

    /** The kind of a single suggested post or reel ("explore_story" in the feed's JSON). */
    static final String SUGGESTED_POST = "EXPLORE_STORY";

    /** Every kind this patch reads. */
    static final Set<String> KINDS;

    static {
        Set<String> kinds = new HashSet<>(ACCOUNT_UNITS);
        kinds.add(SUGGESTED_POST);
        KINDS = Collections.unmodifiableSet(kinds);
    }

    /** The diagnostic counter route: the suggestions seen, and the ones taken out. */
    static final String ROUTE = "Feed suggestions";

    private FeedSuggestions() {
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for a unit of
     * suggested accounts or a suggested post while its switch is on, and [item] itself otherwise,
     * or when anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            String kind = FeedItemKinds.kindIn(item, KINDS, FamilyNames.FEED_SUGGESTIONS);
            if (kind == null) return item;
            FeedFilterCounters.sawKind(ROUTE, kind);
            BooleanSetting setting = SUGGESTED_POST.equals(kind)
                    ? Settings.HIDE_SUGGESTED_POSTS : Settings.HIDE_SUGGESTED_ACCOUNTS;
            if (!Utils.settingsReady() || !setting.get()) return item;
            FeedFilterCounters.removed(ROUTE, 1, kind);
            Logger.printDebug(() -> "Feed suggestions: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "feed item", failure);
            return item;
        }
    }
}
