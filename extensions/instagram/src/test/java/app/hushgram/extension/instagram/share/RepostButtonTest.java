/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Hide the Repost button answers that a post can't be reposted. */
@RunWith(RobolectricTestRunner.class)
public class RepostButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.HIDE_REPOST_BUTTON.resetToDefault();
    }

    /** Once the patch is picked, its switch starts on, so every post reads as one that can't be reposted. */
    @Test
    public void withTheSwitchOnNothingCanBeReposted() {
        assertTrue(RepostButton.hide());
        assertSame(Boolean.FALSE, RepostButton.eligible(Boolean.TRUE));
        assertSame(Boolean.FALSE, RepostButton.eligible(null));
    }

    @Test
    public void withTheSwitchOffTheTreeAnswersAsItDid() {
        Settings.HIDE_REPOST_BUTTON.save(false);
        assertFalse(RepostButton.hide());
        assertSame(Boolean.TRUE, RepostButton.eligible(Boolean.TRUE));
        assertSame(Boolean.FALSE, RepostButton.eligible(Boolean.FALSE));
        assertNull(RepostButton.eligible(null));
    }

    /** The Feed UFI binder gets a last pass because its state can be built before settings are ready. */
    @Test
    public void withTheSwitchOnTheFeedUfiRepostViewsHide() {
        View icon = new View(RuntimeEnvironment.getApplication());
        View count = new View(RuntimeEnvironment.getApplication());

        RepostButton.feedUfi(icon, count);

        assertEquals(View.GONE, icon.getVisibility());
        assertEquals(View.GONE, count.getVisibility());
        assertNull(icon.getContentDescription());
        assertNull(count.getContentDescription());
    }

    @Test
    public void withTheSwitchOffTheFeedUfiViewsStayAsTheyWere() {
        Settings.HIDE_REPOST_BUTTON.save(false);
        View icon = new View(RuntimeEnvironment.getApplication());
        View count = new View(RuntimeEnvironment.getApplication());
        icon.setContentDescription("Repost");
        count.setContentDescription("1");

        RepostButton.feedUfi(icon, count);

        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals(View.VISIBLE, count.getVisibility());
        assertEquals("Repost", icon.getContentDescription());
        assertEquals("1", count.getContentDescription());
    }
}
