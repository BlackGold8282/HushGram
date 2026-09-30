/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * What the Clean up Reels patch asks before a part of the Reels viewer is drawn.
 *
 * <p>The viewer's overlay is built from components, each drawn by its own render method, and a
 * render that answers nothing leaves its part out. Instagram does that itself whenever a part has
 * nothing to show. The patch asks one of these first thing in each render it hides:
 *
 * <ul>
 *   <li>{@link #hideFollowButton} in the Follow button beside a reel's author. The Follow button
 *       on the cards of suggested accounts between reels is another component and stays.
 *   <li>{@link #hideChips} in each pill that prompts you to make something or promotes something:
 *       the attribution pills for Edits, templates and creative tools, the Stories template pill,
 *       the Meta AI pill, the Ray-Ban Meta glasses pills and the affiliate link. The other things
 *       Instagram puts above a reel's author, a live badge or a state-controlled media label, have
 *       components of their own and stay.
 *   <li>{@link #hideSocialFooter} in the inline comment preview and the row of friends who saw
 *       the reel, and in Instagram's check for putting the bubbles of friends' likes, comments and
 *       follows above the author, which then answers no.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with a switch
 * off, or when something throws, Instagram draws the part.
 */
public final class ReelDeclutter {
    private ReelDeclutter() {
    }

    /** True leaves the Follow button beside a reel's author out. Never throws. */
    public static boolean hideFollowButton() {
        return hide(Settings.HIDE_REEL_FOLLOW_BUTTON, "Follow button");
    }

    /** True leaves a creation or promotion pill out. Never throws. */
    public static boolean hideChips() {
        return hide(Settings.HIDE_REEL_CHIPS, "pill");
    }

    /** True leaves friends' activity and the comment preview out. Never throws. */
    public static boolean hideSocialFooter() {
        return hide(Settings.HIDE_REEL_SOCIAL_FOOTER, "friends' activity");
    }

    private static boolean hide(BooleanSetting setting, String what) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            return Utils.settingsReady() && setting.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, what, failure);
            return false;
        }
    }
}
