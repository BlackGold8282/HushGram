/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download in the menu of a feed post with a video.
 *
 * <p>The feed's post menu has Instagram's own Download row, which it shows only on reels whose
 * owner lets other people download them, and which fetches a copy with a watermark. The patch keeps
 * that row and changes who shows it and what a tap does:
 *
 * <ul>
 *   <li>The menu's builder asks {@link #offer} with Instagram's answer to whether the post may be
 *       downloaded, and {@link #withhold} with the server flag that holds the row back. With the
 *       switch on, every post with a video gets the row.
 *   <li>The menu's handler asks {@link #save} first when Download is tapped, which saves the video
 *       from the addresses its Media already holds, through {@link MediaSave}.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with the switch
 * off, or when something throws, the menu is Instagram's own.
 */
public final class VideoDownload {
    private VideoDownload() {
    }

    /** The source a feed video save's lines carry in the diagnostic report. */
    private static final String SOURCE = "VideoDownload";

    /**
     * Instagram's answer [eligible] to whether the post [media] has a Download row, or yes with the
     * switch on when the post has a video. Never throws.
     */
    public static boolean offer(boolean eligible, Object media) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            return eligible || on() && hasVideo(media);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu", t);
            return eligible;
        }
    }

    /** Instagram's flag [held] that keeps the Download row out, or no with the switch on. Never throws. */
    public static boolean withhold(boolean held) {
        return held && !on();
    }

    /**
     * Saves the post [media] when its Download row is tapped with the switch on, and answers whether
     * it did, in which case Instagram's own download is skipped. [activity] is the one the menu
     * belongs to. A save that can't start says so. Never throws.
     */
    public static boolean save(Object media, Activity activity) {
        try {
            if (!on()) return false;
            Context context = activity != null ? activity : Utils.getContext();
            List<MediaSave.Rendition> renditions = media == null ? null : ReelDownload.renditions(media);
            String manifest = media == null ? null : InstagramMedia.dashManifest(media);
            final int files = renditions == null ? 0 : renditions.size();
            final boolean dash = manifest != null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "feed video download tapped: " + files + " file(s)" + (dash ? " and a manifest" : ", no manifest"));
            if (media == null || !MediaSave.saveVideo(context, renditions, manifest, ReelDownload.details(media))) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
            return true;
        } catch (Throwable t) {
            // It runs inside Instagram's click dispatch, where a throw ends the app. Instagram's own
            // download goes ahead instead.
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu", t);
            return false;
        }
    }

    /** Whether [media] lists a video: single files or a DASH manifest. */
    static boolean hasVideo(Object media) {
        return media != null && (!ReelDownload.renditions(media).isEmpty() || InstagramMedia.dashManifest(media) != null);
    }

    private static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_VIDEOS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu switch", t);
            return false;
        }
    }
}
