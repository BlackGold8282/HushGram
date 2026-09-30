/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Which parts of the Reels viewer each of the three switches leaves out. */
@RunWith(RobolectricTestRunner.class)
public class ReelDeclutterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void withTheSwitchesOnEveryPartIsHidden() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideChips());
        assertTrue(ReelDeclutter.hideSocialFooter());
    }

    /** Each switch answers for its own parts only. */
    @Test
    public void eachSwitchTurnsOffOnlyItsOwnParts() {
        Settings.HIDE_REEL_CHIPS.save(false);
        try {
            assertFalse(ReelDeclutter.hideChips());
            assertTrue(ReelDeclutter.hideFollowButton());
            assertTrue(ReelDeclutter.hideSocialFooter());
        } finally {
            Settings.HIDE_REEL_CHIPS.save(true);
        }
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        try {
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideSocialFooter());
            assertTrue(ReelDeclutter.hideChips());
        } finally {
            Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
            Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
        }
    }
}
