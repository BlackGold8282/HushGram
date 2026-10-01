/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Hide the Explore grid empties an Explore page and hides its load more row. */
@RunWith(RobolectricTestRunner.class)
public class ExploreGridTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void aPageIsEmptiedWhileTheSwitchIsOn() {
        assertTrue(ExploreGrid.hide(Arrays.asList("section", "section")));
        assertTrue(ExploreGrid.hide(Collections.emptyList()));
        assertTrue(ExploreGrid.hide(null));
    }

    @Test
    public void withTheSwitchOffThePageStays() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        try {
            assertFalse(ExploreGrid.hide(Arrays.asList("section", "section")));
        } finally {
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    /** With the switch on, Explore's load more row hides and any other list keeps its own. */
    @Test
    public void onlyExploresLoadMoreRowGoes() {
        Object explore = new Object();
        Object location = new Object();
        ExploreGrid.track(explore);

        assertFalse(ExploreGrid.loadMoreRow(explore, true));
        assertFalse(ExploreGrid.loadMoreRow(explore, false));
        assertTrue(ExploreGrid.loadMoreRow(location, true));
        assertFalse(ExploreGrid.loadMoreRow(location, false));
        assertTrue(ExploreGrid.loadMoreRow(null, true));
    }

    @Test
    public void withTheSwitchOffExploreKeepsItsRow() {
        Object explore = new Object();
        ExploreGrid.track(explore);
        Settings.HIDE_EXPLORE_GRID.save(false);
        try {
            assertTrue(ExploreGrid.loadMoreRow(explore, true));
            assertFalse(ExploreGrid.loadMoreRow(explore, false));
        } finally {
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }
}
