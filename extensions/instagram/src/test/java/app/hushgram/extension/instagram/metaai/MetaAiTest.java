/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import app.hushgram.extension.instagram.feed.FeedSuggestions;
import app.hushgram.extension.instagram.reels.FeedReels;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;

/** What Hide Meta AI answers for Meta AI's search flags and which feed items it takes out. */
@RunWith(RobolectricTestRunner.class)
public class MetaAiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like Instagram 449's feed item kinds, a few of them. */
    enum Kind {
        MEDIA, AD, CLIPS_NETEGO, EXPLORE_STORY, THREADS_IN_FEED_UNIT, VIBES_IN_FEED_UNIT,
        HATCH_IMMERSIVE_IN_FEED_UNIT, MEMU_IN_FEED_UNIT
    }

    /** The item's other enum on 449: why the feed was fetched. */
    enum Fetch { COLD_START, PULL_TO_REFRESH }

    static final class Item {
        Fetch fetch = Fetch.COLD_START;
        Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    @Test
    public void aSearchFlagAnswersOffWhileTheSwitchIsOn() {
        assertFalse(MetaAi.searchFlag(true));
        assertFalse(MetaAi.searchFlag(false));
    }

    /** With the switch off Instagram's own answer stands, either way. */
    @Test
    public void withTheSwitchOffInstagramDecides() {
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertTrue(MetaAi.searchFlag(true));
            assertFalse(MetaAi.searchFlag(false));
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    @Test
    public void everyMetaAiUnitIsTakenOut() {
        for (String name : MetaAi.FEED_UNITS) {
            assertNull(name, MetaAi.filter(new Item(Kind.valueOf(name))));
        }
    }

    /** Posts, ads, the reels row, suggestions and Threads' units are the other patches' to take. */
    @Test
    public void everythingElseStays() {
        for (Kind kind : new Kind[] {Kind.MEDIA, Kind.AD, Kind.CLIPS_NETEGO, Kind.EXPLORE_STORY, Kind.THREADS_IN_FEED_UNIT}) {
            Item item = new Item(kind);
            assertSame(kind.name(), item, MetaAi.filter(item));
        }
        Item unset = new Item(null);
        assertSame(unset, MetaAi.filter(unset));
        assertNull(MetaAi.filter(null));
    }

    @Test
    public void withThePostsSwitchOffMetaAiUnitsStay() {
        Settings.HIDE_META_AI_POSTS.save(false);
        try {
            Item memu = new Item(Kind.MEMU_IN_FEED_UNIT);
            assertSame(memu, MetaAi.filter(memu));
            assertFalse(MetaAi.searchFlag(true));
        } finally {
            Settings.HIDE_META_AI_POSTS.save(true);
        }
    }

    /**
     * With Hide Reels in the feed and Hide suggested posts in too, the three filters answer in any
     * order: an item comes out when any of them takes it.
     */
    @Test
    public void besideTheOtherFeedFiltersAnyOrderAnswersTheSame() {
        for (Kind kind : Kind.values()) {
            Item item = new Item(kind);
            Object metaAiLast = MetaAi.filter(FeedSuggestions.filter(FeedReels.filter(item)));
            Object metaAiFirst = FeedReels.filter(FeedSuggestions.filter(MetaAi.filter(item)));
            boolean kept = kind == Kind.MEDIA || kind == Kind.AD;
            if (kept) {
                assertSame(kind.name(), item, metaAiLast);
                assertSame(kind.name(), item, metaAiFirst);
            } else {
                assertNull(kind.name(), metaAiLast);
                assertNull(kind.name(), metaAiFirst);
            }
        }
    }

    @Test
    public void theReportCountsTheMetaAiUnits() {
        FeedFilterCounters.snapshotAndClear();
        MetaAi.filter(new Item(Kind.VIBES_IN_FEED_UNIT));
        MetaAi.filter(new Item(Kind.MEDIA));
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(MetaAi.ROUTE));
        assertTrue(report.toString(), report.toString().contains("VIBES_IN_FEED_UNIT"));
    }
}
