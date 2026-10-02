/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import java.util.EnumSet;

import app.hushgram.extension.instagram.media.ResumePlayback;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ClearResumeSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void start() { ResumePlaybackForTests.install(); }
    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ResumePlaybackForTests.forget();
        PauseForTests.resume();
        PatchFamily.inBuildForTests = null;
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
    }

    @Test public void rowClearsThenOffersOneUseUndoWhilePausedAndOff() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
        Settings.RESUME_LONG_VIDEOS.save(false);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference row = page.findPreference("hushgram_clear_resume_points");
            assertNotNull(row);
            assertTrue(row.isEnabled());
            assertEquals("Clear remembered positions", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("200"));
            assertTrue(String.valueOf(row.getSummary()).contains("30 days"));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Undo cleared positions", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("10 seconds"));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Clear remembered positions", String.valueOf(row.getTitle()));
        }
    }

    @Test public void noClearRowIsAddedWithoutTheResumePatch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertNull(DownloadSettingsTest.pageIn(controller).findPreference("hushgram_clear_resume_points"));
        }
    }
}
