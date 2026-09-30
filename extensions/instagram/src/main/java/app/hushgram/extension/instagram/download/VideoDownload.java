/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
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
 * <p>Instagram's feed menu has a Download row of its own, but only on your own posts, and a tap
 * fetches a copy with a watermark. The patch adds the same row to anyone else's post and changes
 * what a tap does:
 *
 * <ul>
 *   <li>The menu's builder calls {@link #offer} where it starts on the rows for someone else's
 *       post. With the switch on, a post with a video gets Instagram's Download row there.
 *   <li>The short menu most of the feed opens shows only the rows whose option is on a fixed list,
 *       in the list's order. The method that makes the list hands it to {@link #allow}, which puts
 *       Download first with the switch on.
 *   <li>The menu's handler asks {@link #save} first when Download is tapped, which saves the video
 *       from the addresses its Media already holds, through {@link MediaSave}. A post without a
 *       video goes to Instagram's own download.
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
     * Adds the Download row to [rows], the list the feed menu's builder [menu] fills for someone
     * else's post, when the switch is on and the post has a video. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (menu == null || rows == null || !on() || !hasVideo(InstagramMedia.feedMenuMedia(menu))) return;
            InstagramMedia.addDownloadRow(menu, rows);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu", t);
        }
    }

    /**
     * Answers [options], the options the short feed menu keeps, with [download], Instagram's
     * Download option, in front when the switch is on. The menu keeps a row only when its option is
     * on this list and orders the rows by it, so without this the row {@link #offer} added never
     * shows there. A list that already has Download, or any list with the switch off, comes back
     * as it came. Never throws.
     */
    public static List<?> allow(List<?> options, Object download) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (options == null || download == null || !on() || options.contains(download)) return options;
            List<Object> allowed = new ArrayList<>(options.size() + 1);
            allowed.add(download);
            allowed.addAll(options);
            return allowed;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "short feed menu", t);
            return options;
        }
    }

    /**
     * Saves the post [media] when its Download row is tapped with the switch on and the post has a
     * video, and answers whether it did, in which case Instagram's own download is skipped. A post
     * without a video, your own photo for one, goes to Instagram's own download. [activity] is the
     * one the menu belongs to. A save that can't start says so. Never throws.
     */
    public static boolean save(Object media, Activity activity) {
        try {
            if (!on() || !hasVideo(media)) return false;
            Context context = activity != null ? activity : Utils.getContext();
            List<MediaSave.Rendition> renditions = media == null ? null : ReelDownload.renditions(media);
            String manifest = media == null ? null : InstagramMedia.dashManifest(media);
            final int files = renditions == null ? 0 : renditions.size();
            final boolean dash = manifest != null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "feed video download tapped: " + files + " file(s)" + (dash ? " and a manifest" : ", no manifest"));
            if (!MediaSave.saveVideo(context, renditions, manifest, ReelDownload.details(media))) {
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
