/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** What the feed menu's hooks answer, and what a tap on Download does with them. */
@RunWith(RobolectricTestRunner.class)
public class VideoDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        Settings.DOWNLOAD_VIDEOS.save(true);
    }

    /**
     * With the switch on, the server flag never holds the row back, and Instagram's own yes stands.
     * A post with no video gets no row: without the bridges written, nothing has one.
     */
    @Test
    public void withTheSwitchOnOnlyAVideoIsOffered() {
        assertTrue(VideoDownload.offer(true, new Object()));
        assertFalse("a post without a video got the row", VideoDownload.offer(false, new Object()));
        assertFalse(VideoDownload.offer(false, null));
        assertFalse(VideoDownload.withhold(true));
        assertFalse(VideoDownload.withhold(false));
    }

    /** Off, the menu is Instagram's own. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertTrue(VideoDownload.offer(true, new Object()));
        assertFalse(VideoDownload.offer(false, new Object()));
        assertTrue(VideoDownload.withhold(true));
        assertFalse(VideoDownload.withhold(false));
        assertFalse("a tap was taken from Instagram", VideoDownload.save(new Object(), null));
    }

    /**
     * A tap with the switch on is HushGram's, even when the post gives nothing to save: the toast
     * says so rather than Instagram's own download starting.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertTrue(VideoDownload.save(new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    /** Without the bridges written, a post has no video, and the read doesn't throw. */
    @Test
    public void anUnpatchedPostHasNoVideo() {
        assertFalse(VideoDownload.hasVideo(new Object()));
        assertFalse(VideoDownload.hasVideo(null));
    }
}
