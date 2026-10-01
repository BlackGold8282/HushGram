/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the Explore grid" patch.
 *
 * <p>The Search tab's grid comes from Instagram's topical Explore pages, read from the server (or
 * its copy of the last one) by one parser. The patch asks {@link #hide} as that parser finishes a
 * page, and while the switch is on it empties the page's sections and marks it the last, so the
 * grid stays empty and nothing more is fetched. Search, recent searches and search results come
 * from other requests and stay.
 */
public final class ExploreGrid {
    private ExploreGrid() {
    }

    /**
     * Injected where Instagram finishes reading an Explore page, with the page's sections. Answers
     * true while Hide the Explore grid is on, and false otherwise, or when anything goes wrong.
     * Never throws, and never waits for the settings: before they're ready the page stays.
     */
    public static boolean hide(List<?> sections) {
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_GRID);
            if (!Utils.settingsReady() || !Settings.HIDE_EXPLORE_GRID.get()) return false;
            int count = sections == null ? 0 : sections.size();
            Logger.printDebug(() -> "Explore: emptied a page of " + count + " sections");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, "explore page", failure);
            return false;
        }
    }
}
