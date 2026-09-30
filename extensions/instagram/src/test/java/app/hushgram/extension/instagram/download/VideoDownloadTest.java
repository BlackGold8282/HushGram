/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** What the feed menu's hooks do with a post, and what a tap on Download does. */
@RunWith(RobolectricTestRunner.class)
public class VideoDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        Settings.DOWNLOAD_VIDEOS.save(true);
    }

    /**
     * A post with no video gets no row, and the builder's list is left as it was. Without the
     * bridges written, no post has one.
     */
    @Test
    public void aPostWithoutAVideoGetsNoRow() {
        ArrayList<Object> rows = new ArrayList<>();
        rows.add("Report");

        VideoDownload.offer(new Object(), rows);
        VideoDownload.offer(null, rows);
        VideoDownload.offer(new Object(), null);

        assertEquals(Collections.singletonList("Report"), rows);
    }

    /** Off, the menu is Instagram's own, and so is a tap. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        ArrayList<Object> rows = new ArrayList<>();

        VideoDownload.offer(new Object(), rows);

        assertTrue(rows.isEmpty());
        assertFalse("a tap was taken from Instagram", VideoDownload.save(new Object(), null));
    }

    /**
     * A tap on a post without a video, your own photo for one, goes to Instagram's own download
     * rather than a failure toast.
     */
    @Test
    public void aTapOnAPostWithoutAVideoIsInstagrams() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertFalse(VideoDownload.save(new Object(), activity));
        assertFalse(VideoDownload.save(null, activity));
        assertEquals(null, ShadowToast.getTextOfLatestToast());
    }

    /**
     * With the switch on, the short menu's list gets Download in front, so its row shows first; the
     * list Instagram made is left as it was. A list that has Download already comes back as is.
     */
    @Test
    public void theShortMenuKeepsDownloadFirst() {
        List<String> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT");

        List<?> allowed = VideoDownload.allow(options, "DOWNLOAD");

        assertEquals(Arrays.asList("DOWNLOAD", "WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT"), allowed);
        assertEquals(Arrays.asList("WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT"), options);
        List<String> already = Arrays.asList("REPORT", "DOWNLOAD");
        assertSame(already, VideoDownload.allow(already, "DOWNLOAD"));
    }

    /** Off, or with nothing to go on, the short menu's list is Instagram's own. */
    @Test
    public void offTheShortMenuIsInstagrams() {
        List<String> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        assertSame(options, VideoDownload.allow(options, null));
        assertNull(VideoDownload.allow(null, "DOWNLOAD"));

        Settings.DOWNLOAD_VIDEOS.save(false);

        assertSame(options, VideoDownload.allow(options, "DOWNLOAD"));
    }

    /** Without the bridges written, a post has no video, and the read doesn't throw. */
    @Test
    public void anUnpatchedPostHasNoVideo() {
        assertFalse(VideoDownload.hasVideo(new Object()));
        assertFalse(VideoDownload.hasVideo(null));
    }
}
