/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.StringSetting;

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

    // ---- Downloads -------------------------------------------------------------------------
    // What every save reads when it starts (app.hushgram.extension.instagram.download), ported
    // with the save pipeline from Hushfacebook 3a473639 with the same types and defaults, keyed
    // hushgram_ and with Instagram's folder name. None of them is a switch: the patch that saves
    // brings its own switch and its settings rows.

    /**
     * The folder every save goes to, under Movies for a video and Pictures for a photo. The
     * settings row and an import keep it clean, and {@link SaveFolder#sanitize} cleans it again
     * wherever it's read, so whatever wrote the store, a save lands in one folder under each.
     * It isn't a switch, and a paused Instagram makes no HushGram saves for it to steer.
     */
    public static final StringSetting SAVE_FOLDER =
            new StringSetting("hushgram_save_folder", SaveFolder.DEFAULT);

    /**
     * The quality a video save asks for: the best the player streams, a ceiling, or the smallest
     * file. Every video save reads it when it starts, and one that finds nothing at or under a
     * ceiling takes the nearest above it. Photos always save whole. Like the folder, it isn't a
     * switch.
     */
    public static final EnumSetting<DownloadQuality> DOWNLOAD_QUALITY =
            new EnumSetting<>("hushgram_download_quality", DownloadQuality.BEST);

    /**
     * Video saves keep to what other apps open: H.264 video with AAC-LC or HE-AAC sound, within
     * {@link #DOWNLOAD_QUALITY}, or the app's single MP4 file when the manifest has no such pair.
     * The sharpest version Meta streams is often AV1 with xHE-AAC sound, which Gallery and VLC play
     * and WhatsApp turns down. Off by default, so a save keeps the sharpest.
     */
    public static final BooleanSetting DOWNLOAD_COMPATIBLE =
            new BooleanSetting("hushgram_download_compatible", FALSE);

    /**
     * The name a saved video gets: {date}, {video_id}, {owner} and {posted} fill in per save, the
     * last three only when the save knows them, and the default is IG_VID_ and the date and time.
     * Photos keep their IG_IMG_ names. Cleaned like the folder wherever it's read
     * ({@link FileNameTemplate#sanitize}), and like the folder, it isn't a switch.
     */
    public static final StringSetting FILENAME_TEMPLATE =
            new StringSetting("hushgram_filename_template", FileNameTemplate.DEFAULT);
}
