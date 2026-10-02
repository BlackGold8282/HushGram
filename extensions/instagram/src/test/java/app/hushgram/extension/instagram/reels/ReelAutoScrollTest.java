/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the auto scroll hooks answer and remember, and that Instagram's answer stands when they can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelAutoScrollTest {
    /** A memory that counts its writes. */
    static final class CountingMemory implements ReelAutoScroll.Memory {
        boolean on;
        int writes;

        CountingMemory(boolean on) {
            this.on = on;
        }

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public void remember(boolean on) {
            this.on = on;
            writes++;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, Instagram saying auto scroll is on is remembered, and a later "off", such as after a restart, is answered on. */
    @Test
    public void anOnAnswerIsRememberedAndOutlivesInstagramsOff() {
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());

        assertTrue(ReelAutoScroll.answer(1));
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("any non-zero is yes", ReelAutoScroll.answer(0x7f));

        assertTrue("Instagram forgot, HushGram didn't", ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(0));
    }

    /** Turning auto scroll off with Instagram's switch is remembered, so Instagram's "off" stands from then on. */
    @Test
    public void turningItOffIsRemembered() {
        assertTrue(ReelAutoScroll.answer(1));

        ReelAutoScroll.chosen(1);
        assertTrue("turning it on changes nothing until Instagram says it's on", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        ReelAutoScroll.chosen(0);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse(ReelAutoScroll.answer(0));

        assertTrue("on again once Instagram says so", ReelAutoScroll.answer(1));
        assertTrue(ReelAutoScroll.answer(0));
    }

    /** A choice to turn it on isn't remembered by itself: Instagram may still be asking how long for. */
    @Test
    public void turningItOnWaitsForInstagram() {
        ReelAutoScroll.chosen(1);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse(ReelAutoScroll.answer(0));
    }

    /** The hot path writes only when the choice changes. */
    @Test
    public void nothingIsWrittenWhenTheChoiceIsUnchanged() {
        CountingMemory memory = new CountingMemory(false);

        for (int i = 0; i < 5; i++) assertFalse(ReelAutoScroll.answer(0, () -> true, () -> true, memory));
        assertEquals(0, memory.writes);
        for (int i = 0; i < 5; i++) assertTrue(ReelAutoScroll.answer(1, () -> true, () -> true, memory));
        assertEquals(1, memory.writes);
        for (int i = 0; i < 5; i++) assertTrue(ReelAutoScroll.answer(0, () -> true, () -> true, memory));
        assertEquals(1, memory.writes);

        ReelAutoScroll.chosen(1, () -> true, memory);
        assertEquals(1, memory.writes);
        ReelAutoScroll.chosen(0, () -> true, memory);
        ReelAutoScroll.chosen(0, () -> true, memory);
        assertEquals(2, memory.writes);
        assertFalse(memory.on);
    }

    /**
     * Off, paused or before the settings are read, Instagram's answer stands. While the switch is
     * off the memory still follows Instagram, so turning the switch on goes by the latest choice;
     * paused or unready, nothing is remembered.
     */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.REEL_AUTO_SCROLL_ON.save(true);

        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        assertFalse(ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(1));
        ReelAutoScroll.chosen(0);
        assertFalse("turned off while the switch was off", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue(ReelAutoScroll.answer(1));
        assertTrue("seen on while the switch was off", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        assertTrue(ReelAutoScroll.answer(0));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(1));
        ReelAutoScroll.chosen(0);
        assertTrue("nothing is remembered while paused", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelAutoScroll.answer(0));

        SettingsContextRule.withoutContext(() -> {
            assertFalse(ReelAutoScroll.answer(0));
            assertTrue(ReelAutoScroll.answer(1));
            ReelAutoScroll.chosen(0);
        });
        assertTrue("nothing is remembered before the settings are read", Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    /** A switch or a memory that throws leaves Instagram's answer, and says which hook threw. */
    @Test
    public void aThrowingSwitchOrMemoryKeepsInstagramsAnswerAndIsReported() {
        ReelAutoScroll.Memory broken = new ReelAutoScroll.Memory() {
            @Override
            public boolean on() {
                throw new IllegalStateException("preferences went away");
            }

            @Override
            public void remember(boolean on) {
                throw new IllegalStateException("preferences went away");
            }
        };

        assertFalse(ReelAutoScroll.answer(0, () -> true, () -> true, broken));
        assertTrue(ReelAutoScroll.answer(1, () -> true, () -> true, broken));
        assertFalse(ReelAutoScroll.answer(0, () -> true, () -> {
            throw new IllegalStateException("settings went away");
        }, new CountingMemory(true)));
        assertTrue(ReelAutoScroll.answer(1, () -> {
            throw new IllegalStateException("settings went away");
        }, () -> true, new CountingMemory(false)));
        ReelAutoScroll.chosen(0, () -> true, broken);

        String missing = HookStatus.missing(FamilyNames.REEL_AUTO_SCROLL).toString();
        assertTrue(missing, missing.contains("'auto scroll answer'"));
        assertTrue(missing, missing.contains("'auto scroll choice'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
