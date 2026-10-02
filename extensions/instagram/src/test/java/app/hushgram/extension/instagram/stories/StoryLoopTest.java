/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

/** What the loop hook answers, and how Loop a story and Stop Story auto-advance share a finished story. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryLoopTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOOP_STORIES.save(true);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        StoryLoop.inBuildForTests = true;
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryLoop.inBuildForTests = null;
        Settings.LOOP_STORIES.resetToDefault();
        Settings.BLOCK_STORY_AUTO_ADVANCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Picking the patch is the choice to use it, so its switch starts on. */
    @Test
    public void theSwitchStartsOn() {
        Settings.LOOP_STORIES.resetToDefault();
        assertTrue(Settings.LOOP_STORIES.get());
    }

    /** With the switch on, the viewer's loop test says yes whatever Instagram's server said. */
    @Test
    public void onEveryStoryLoops() {
        assertTrue("the server said no", StoryLoop.loop(0));
        assertTrue("the server said yes", StoryLoop.loop(1));
        assertTrue(HookStatus.missing(FamilyNames.STORY_LOOP).toString(), HookStatus.missing(FamilyNames.STORY_LOOP).isEmpty());
    }

    /** Off, paused or before the settings are read, the loop test keeps Instagram's answer. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.LOOP_STORIES.save(false);
        assertFalse("off", StoryLoop.loop(0));
        assertTrue("off, Instagram's own yes", StoryLoop.loop(1));
        Settings.LOOP_STORIES.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryLoop.loop(0));
        assertTrue("paused, Instagram's own yes", StoryLoop.loop(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", StoryLoop.loop(0));
            assertTrue("no context, Instagram's own yes", StoryLoop.loop(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", StoryLoop.loop(0));
            assertTrue("pause undecided, Instagram's own yes", StoryLoop.loop(1));
        });

        assertTrue("back on", StoryLoop.loop(0));
    }

    /**
     * With both switches on, Loop wins: Stop holds nothing, so a finished photo reaches the place
     * where Instagram starts it over, the way a video loops in the player before Stop is asked.
     */
    @Test
    public void loopWinsOverStopWhileItsLooping() {
        assertTrue(StoryLoop.takesOver());
        assertFalse("both on, the story loops instead of holding", StoryAdvance.hold());

        Settings.LOOP_STORIES.save(false);
        assertFalse(StoryLoop.takesOver());
        assertTrue("Loop off, Stop holds", StoryAdvance.hold());
        Settings.LOOP_STORIES.save(true);

        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse("Stop off, nothing is held", StoryAdvance.hold());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, Loop stands aside", StoryLoop.takesOver());
        assertFalse("paused, Stop holds nothing either", StoryAdvance.hold());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", StoryLoop.takesOver());
            assertFalse("no context", StoryAdvance.hold());
        });
    }

    /** A build without Loop a story leaves Stop as it was, though Loop's unused switch reads on. */
    @Test
    public void withoutTheLoopPatchStopHoldsAsBefore() {
        StoryLoop.inBuildForTests = false;
        assertTrue(Settings.LOOP_STORIES.get());
        assertFalse(StoryLoop.takesOver());
        assertTrue(StoryAdvance.hold());
    }
}
