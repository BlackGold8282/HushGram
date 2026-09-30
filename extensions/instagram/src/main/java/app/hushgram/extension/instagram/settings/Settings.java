/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static java.lang.Boolean.TRUE;

import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Picking a patch in
 * Morphe Manager is the choice to use it, and the switch is the way to turn it off again without
 * patching a second time. While HushGram is paused, safe mode included
 * ({@link app.hushgram.extension.shared.settings.HushgramPause}), a switch answers off and the hook
 * behind it takes Instagram's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /** Sponsored posts, reels and stories: the ad injector is told no ad went in. */
    public static final BooleanSetting HIDE_ADS =
            new BooleanSetting("hushgram_hide_ads", TRUE);

    /** igsh, igshid, utm_source and the other tracking keys come off links that leave Instagram. */
    public static final BooleanSetting SANITIZE_SHARING_LINKS =
            new BooleanSetting("hushgram_sanitize_sharing_links", TRUE);

    /**
     * Instagram's event uploads, to its own logging endpoint and to Facebook's graph endpoint, go
     * to an address on the phone that refuses them. The uploader reads the address when it starts,
     * so a change shows after a restart.
     */
    public static final BooleanSetting DISABLE_ANALYTICS =
            new BooleanSetting("hushgram_disable_analytics", TRUE, true);

    /**
     * The screen an old build shows to push an update, which a patched build can't install from
     * the Play Store. Instagram checks when its main screen opens.
     */
    public static final BooleanSetting REMOVE_BUILD_EXPIRED_POPUP =
            new BooleanSetting("hushgram_remove_build_expired_popup", TRUE);

    /**
     * The reels you watch, and how far into each you got, which Instagram posts to
     * clips/write_seen_state/ to rank your Reels. Nobody else sees it. Held back, reels you've
     * watched may come back.
     */
    public static final BooleanSetting DONT_SEND_REEL_WATCH_HISTORY =
            new BooleanSetting("hushgram_dont_send_reel_watch_history", TRUE);

    /**
     * A story whose photo timer ran out or whose video ended stays on screen until you tap or
     * swipe, instead of the viewer moving on by itself.
     */
    public static final BooleanSetting BLOCK_STORY_AUTO_ADVANCE =
            new BooleanSetting("hushgram_block_story_auto_advance", TRUE);

    /**
     * The rows of suggested reels between posts in the home feed, and the other feed units that
     * open the Reels viewer. A reel someone you follow posts is a post and stays.
     */
    public static final BooleanSetting HIDE_FEED_REELS =
            new BooleanSetting("hushgram_hide_feed_reels", TRUE);
}
