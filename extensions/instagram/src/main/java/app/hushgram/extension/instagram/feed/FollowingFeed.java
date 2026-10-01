/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Start Home on Following" patch.
 *
 * <p>Instagram has a feed picker at the top of Home that offers For you, Following and Favorites,
 * shows the picked feed's name and remembers the pick. Two server flags turn it on: one has the
 * pick remembered, read on 449 by Home, the picker and the feed request among others, and one puts
 * For you in the picker and the name at the top. The patch passes every read of both through
 * {@link #flag}, which answers yes while the switch is on, and the remembered pick through
 * {@link #saved}, which answers Following while there's none yet.
 */
public final class FollowingFeed {
    /** The name Instagram 449 saves for the Following feed, its feed type constant's. */
    static final String FOLLOWING = "FOLLOWING";

    private static volatile boolean loggedFlag;
    private static volatile boolean loggedDefault;

    private FollowingFeed() {
    }

    /**
     * Injected right after each read of the feed picker's flags, with Instagram's answer as an int
     * (non-zero is yes). Answers true while the switch is on, and Instagram's answer otherwise, or
     * when anything goes wrong. Never throws, and never waits for the settings: before they're
     * ready Instagram's answer stands.
     */
    public static boolean flag(int enabled) {
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!Utils.settingsReady() || !Settings.START_ON_FOLLOWING.get()) return enabled != 0;
            if (!loggedFlag) {
                loggedFlag = true;
                Logger.printDebug(() -> "Following feed: feed picker flags answered on");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed flag", failure);
            return enabled != 0;
        }
    }

    /**
     * Injected where Instagram hands back the feed you last picked, with that feed's name. Answers
     * the name when there is one, Following when there isn't and the switch is on, and the name
     * otherwise, or when anything goes wrong. Never throws.
     */
    public static String saved(String name) {
        if (name != null && !name.isEmpty()) return name;
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!Utils.settingsReady() || !Settings.START_ON_FOLLOWING.get()) return name;
            if (!loggedDefault) {
                loggedDefault = true;
                Logger.printDebug(() -> "Following feed: no feed picked yet, starting on Following");
            }
            return FOLLOWING;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "saved feed", failure);
            return name;
        }
    }
}
