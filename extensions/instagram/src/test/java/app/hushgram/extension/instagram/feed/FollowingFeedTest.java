/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Start Home on Following turns on the remembered feed, and what it answers for the saved pick. */
@RunWith(RobolectricTestRunner.class)
public class FollowingFeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void theFlagIsOnWhileTheSwitchIsOn() {
        assertTrue(FollowingFeed.flag(0));
        assertTrue(FollowingFeed.flag(1));
    }

    @Test
    public void withNothingPickedHomeStartsOnFollowing() {
        assertEquals("FOLLOWING", FollowingFeed.saved(null));
        assertEquals("FOLLOWING", FollowingFeed.saved(""));
    }

    @Test
    public void aPickIsKept() {
        assertEquals("BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        assertEquals("FOLLOWING", FollowingFeed.saved("FOLLOWING"));
    }

    @Test
    public void withTheSwitchOffInstagramsAnswersStand() {
        Settings.START_ON_FOLLOWING.save(false);
        try {
            assertFalse(FollowingFeed.flag(0));
            assertTrue(FollowingFeed.flag(1));
            assertNull(FollowingFeed.saved(null));
            assertEquals("", FollowingFeed.saved(""));
            assertEquals("BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        } finally {
            Settings.START_ON_FOLLOWING.save(true);
        }
    }
}
