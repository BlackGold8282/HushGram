/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The rule Tap to play holds every start to: a start goes ahead on an armed player or within a
 * second of a tap, whatever reason Instagram gives. Everything else is held. Off, paused, before
 * the settings are ready, or when the rule throws, every start goes ahead, and the autoplay check
 * answers what Instagram decided.
 */
@RunWith(RobolectricTestRunner.class)
public class TapToPlayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Reasons Instagram 449 hands playInternal and IgGrootPlayer's play. */
    private static final List<String> REASONS = Arrays.asList(
            "autoplay", "resume", "start", "retry", "play_after_recovery", "seek_force_pause", "ig_live_pip");

    @Before
    public void start() {
        // Moves the clock well past zero, so "a tap five seconds ago" is a time the clock has seen.
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAP_TO_PLAY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    /** Stands in for IgVideoPlayerImpl's state enum: only the constant names matter. */
    private enum State { IDLE, PREPARING, PREPARED, PLAYING, PAUSED, STOPPING }

    private static void tapAt(long upAt) {
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 50, upAt - 90, 8);
        TapClock.record(MotionEvent.ACTION_UP, 51, 52, upAt, 8);
    }

    @Test
    public void theSwitchStartsOn() {
        assertTrue("picking the patch is the choice to use it", Settings.TAP_TO_PLAY.get());
    }

    @Test
    public void noStartGoesAheadWithoutATap() {
        for (String reason : REASONS) {
            assertFalse(reason, TapToPlay.allowStart(new Object(), reason));
            assertFalse(reason, TapToPlay.allowDirectStart(new Object(), reason));
        }
        assertFalse("no reason at all", TapToPlay.allowStart(new Object(), null));
        assertEquals("a held start arms nothing", 0, TapToPlay.armedCount());
    }

    @Test
    public void aStartWithinASecondOfATapGoesAhead() {
        long now = 100_000;
        tapAt(now - TapToPlay.TAP_WINDOW_MS);
        assertTrue("a tap exactly a second ago", TapToPlay.decide(new Object(), "autoplay", now, ""));
        tapAt(now - TapToPlay.TAP_WINDOW_MS - 1);
        assertFalse("a tap just over a second ago", TapToPlay.decide(new Object(), "autoplay", now, ""));
        tapAt(now + 5);
        assertFalse("a tap the clock hasn't reached yet", TapToPlay.decide(new Object(), "autoplay", now, ""));
    }

    /** A story you tap starts with "autoplay", and playInternal only ever hears "autoplay" or "resume". */
    @Test
    public void anyReasonRightAfterATapGoesAhead() {
        long now = 200_000;
        tapAt(now - 300);
        for (String reason : REASONS) assertTrue(reason, TapToPlay.decide(new Object(), reason, now, ""));
    }

    @Test
    public void anArmedPlayerRestartsUntilItsPaused() {
        Object player = new Object();
        Object other = new Object();
        TapToPlayForTests.tapEnded(100);
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        assertTrue(TapToPlay.armed(player));
        TapClock.forget();

        assertTrue("IgGrootPlayer's own play of the same player", TapToPlay.allowDirectStart(player, "autoplay"));
        assertTrue("a resume", TapToPlay.allowStart(player, "resume"));
        assertFalse("another player", TapToPlay.allowStart(other, "autoplay"));

        TapToPlay.paused(player, "scroll");
        assertFalse(TapToPlay.armed(player));
        assertFalse("the start after a pause", TapToPlay.allowStart(player, "resume"));

        TapToPlayForTests.tapEnded(10);
        assertTrue("a tap plays it again", TapToPlay.allowStart(player, "resume"));
    }

    /** A seek pauses and plays again, and a reel loops the same way: neither undoes the tap. */
    @Test
    public void momentaryPausesKeepThePlayerArmed() {
        for (String reason : TapToPlay.MOMENTARY) {
            Object player = new Object();
            TapToPlayForTests.tapEnded(10);
            assertTrue(TapToPlay.allowDirectStart(player, "start"));
            TapClock.forget();
            TapToPlay.paused(player, reason);
            assertTrue(reason, TapToPlay.armed(player));
            assertTrue(reason, TapToPlay.allowDirectStart(player, reason));
        }
        Object held = new Object();
        TapToPlay.paused(held, "seek_force_pause");
        assertFalse("a seek on a held player doesn't start it", TapToPlay.allowDirectStart(held, "seek_force_pause"));
        Object paused = new Object();
        TapToPlayForTests.tapEnded(10);
        assertTrue(TapToPlay.allowDirectStart(paused, "start"));
        TapToPlay.paused(paused, null);
        assertFalse("a pause with no reason", TapToPlay.armed(paused));
    }

    /**
     * The long video viewer's scrubber pauses a playing video with "Seek start" and plays it again
     * when the drag ends, with an automatic start and no tap, since a drag isn't one. The video
     * plays on from where it was dragged to.
     */
    @Test
    public void aDragOfTheLongVideoScrubberKeepsItPlaying() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(10);
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        TapClock.forget();

        TapToPlay.paused(player, "Seek start");

        assertTrue("the seek's pause disarmed it", TapToPlay.armed(player));
        assertTrue("the play at the drag's end", TapToPlay.allowStart(player, "autoplay"));
    }

    /** Debug logging says what ended a start, and a pause Instagram plays on from ends none. */
    @Test
    public void debugLoggingSaysWhatEndedAStart() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Object seeking = new Object();
        Object scrolled = new Object();
        Object rebound = new Object();
        TapToPlayForTests.tapEnded(10);
        for (Object player : Arrays.asList(seeking, scrolled, rebound)) assertTrue(TapToPlay.allowStart(player, "autoplay"));
        TapClock.forget();
        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);

        TapToPlay.paused(seeking, "Seek start");
        TapToPlay.paused(scrolled, "scroll");
        TapToPlay.paused(new Object(), "scroll");
        TapToPlay.rebound(rebound);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tap to play: a pause for scroll ends a start"));
        assertTrue(report, report.contains("Tap to play: a new video ends a start"));
        assertFalse(report, report.contains("Seek start ends"));
    }

    /** A new video disarms, but not a prepare right after the start the gate just let through. */
    @Test
    public void aNewVideoDisarmsButTheStartsOwnPrepareDoesnt() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        TapToPlay.rebound(player);
        assertTrue("the prepare right after the start", TapToPlay.armed(player));

        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);
        TapToPlay.rebound(player);
        assertFalse("the next video", TapToPlay.armed(player));
        assertFalse(TapToPlay.allowStart(player, "autoplay"));
    }

    @Test
    public void aRapidSwipeCannotReuseThePreviousTapOrThePlayersPrepareGrace() {
        Object player = new Object();
        tapAt(SystemClock.uptimeMillis());
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        TapToPlay.rebound(player);
        assertTrue("the tap's own prepare", TapToPlay.armed(player));

        SystemClock.sleep(10);
        long swipe = SystemClock.uptimeMillis();
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, swipe, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 50, 300, swipe, 8);
        assertFalse("another player cannot borrow the previous tap during a swipe",
                TapToPlay.allowStart(new Object(), "autoplay"));
        assertTrue("scrolling alone doesn't interrupt the current video", TapToPlay.armed(player));
        TapToPlay.rebound(player);
        assertFalse("a new video after the swipe cannot borrow the previous start", TapToPlay.armed(player));
        TapClock.record(MotionEvent.ACTION_UP, 50, 100, swipe, 8);
        assertFalse(TapToPlay.allowStart(player, "autoplay"));
        assertFalse(TapToPlay.allowDirectStart(player, "start"));

        SystemClock.sleep(1);
        tapAt(SystemClock.uptimeMillis());
        assertTrue("the next video's own tap works", TapToPlay.allowStart(player, "autoplay"));
        TapToPlay.rebound(player);
        assertTrue("that tap keeps its own prepare grace", TapToPlay.armed(player));
    }

    /** Players are told apart by identity, so one whose equals claims another is still itself. */
    @Test
    public void playersAreToldApartByIdentity() {
        Object player = new AlwaysEqual();
        Object twin = new AlwaysEqual();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        TapClock.forget();
        assertFalse(TapToPlay.allowStart(twin, "autoplay"));
        TapToPlay.paused(twin, "scroll");
        assertTrue(TapToPlay.armed(player));
    }

    /** An armed player Instagram drops is forgotten with it: the gate holds no player in memory. */
    @Test
    public void aDroppedPlayerIsForgotten() throws InterruptedException {
        TapToPlayForTests.tapEnded(20);
        WeakReference<Object> dropped = armOne();
        for (int i = 0; i < 100 && dropped.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the gate kept the player alive", dropped.get());
        assertEquals(0, TapToPlay.armedCount());
    }

    private static WeakReference<Object> armOne() {
        Object player = new Object();
        assertTrue(TapToPlay.allowStart(player, "autoplay"));
        assertEquals(1, TapToPlay.armedCount());
        return new WeakReference<>(player);
    }

    @Test
    public void offPausedOrNotReadyEveryStartGoesAhead() {
        Settings.TAP_TO_PLAY.save(false);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay"));
        assertTrue(TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue("Instagram's own answer", TapToPlay.autoplayAllowed(true));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay"));
        assertTrue(TapToPlay.autoplayAllowed(true));
        PauseForTests.resume();
        assertFalse("the control: running, the same start is held", TapToPlay.allowStart(new Object(), "autoplay"));
        assertEquals("nothing armed while off or paused", 0, TapToPlay.armedCount());
    }

    @Test
    public void aStartBeforeTheSettingsAreReadyGoesAhead() {
        SettingsContextRule.withoutContext(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), "autoplay"));
            assertTrue(TapToPlay.autoplayAllowed(true));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), "autoplay"));
            assertTrue(TapToPlay.autoplayAllowed(true));
        });
        assertFalse("the control: ready, the same start is held", TapToPlay.allowStart(new Object(), "autoplay"));
    }

    @Test
    public void aFailureLetsTheStartGoAheadAndTheReportSaysSo() {
        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay"));
        assertEquals(Collections.singletonList("a working 'player start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY));
        assertFalse("only the one start went ahead", TapToPlay.allowStart(new Object(), "autoplay"));

        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue(String.join("\n", HookStatus.missing(FamilyNames.TAP_TO_PLAY)),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY).contains("a working 'direct start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"));
    }

    @Test
    public void eachHookCountsAndBindsInTheReport() {
        TapToPlay.allowStart(new Object(), "autoplay");
        TapToPlay.allowDirectStart(new Object(), "autoplay");
        TapToPlay.paused(new Object(), "scroll");
        TapToPlay.rebound(new Object());
        TapToPlay.autoplayAllowed(true);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.TAP_TO_PLAY + ": invoked 5, 5 found, 0 missing"));
    }

    /**
     * Only a reel Instagram's resume can restart goes down it: prepared, where the gate leaves a held
     * reel, or paused. Idle, loading, stopping or playing, the tap does what Instagram decided.
     */
    @Test
    public void aTapOnAPreparedOrPausedReelStartsIt() {
        Object navigator = new Object();
        for (State state : State.values()) {
            TapToPlay.reelStates = asked -> {
                assertSame(navigator, asked);
                return state;
            };
            boolean resumable = state == State.PREPARED || state == State.PAUSED;
            assertEquals(state.name(), resumable, TapToPlay.resumeOnTap(false, navigator));
        }
        TapToPlay.reelStates = asked -> null;
        assertFalse("no player under the tap: Instagram's answer", TapToPlay.resumeOnTap(false, navigator));
        assertFalse(TapToPlay.resumeOnTap(false, null));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    /** A reel you paused yourself already resumes. The reader isn't asked, so it can't turn that yes into a no. */
    @Test
    public void instagramsOwnResumeNeverAsksTheReader() {
        TapToPlay.reelStates = asked -> {
            throw new AssertionError("asked about a tap Instagram already resumes");
        };
        assertTrue(TapToPlay.resumeOnTap(true, new Object()));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    /**
     * The sequence on the phone. Instagram's own start of the reel was held. A tap lands on it, the
     * tap takes the resume path, and the start that path makes on the same tap goes ahead and arms
     * the player. Without that tap, the same start is held.
     */
    @Test
    public void aTapOnAHeldReelResumesItAndThatStartGoesAhead() {
        long now = 300_000;
        Object player = new Object();
        assertFalse("Instagram's own start, with no tap", TapToPlay.decide(player, "autoplay", now - 5_000, ""));
        TapToPlay.reelStates = asked -> State.PREPARED;
        tapAt(now);
        assertTrue("the tap takes the resume path", TapToPlay.resumeOnTap(false, new Object()));
        assertTrue("the resume's start, on the tap", TapToPlay.decide(player, "resume", now + 20, ""));
        assertTrue("armed, so it plays on", TapToPlay.armed(player));
        assertFalse("the same start with the tap long gone",
                TapToPlay.decide(new Object(), "resume", now + TapToPlay.TAP_WINDOW_MS + 1, ""));
    }

    @Test
    public void offPausedOrNotReadyATapOnAReelDoesWhatInstagramDecided() {
        TapToPlay.reelStates = asked -> State.PREPARED;
        Settings.TAP_TO_PLAY.save(false);
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(TapToPlay.resumeOnTap(false, new Object())));
        assertTrue("the control: on, the same tap starts the reel", TapToPlay.resumeOnTap(false, new Object()));
    }

    @Test
    public void anUnfilledReaderOrAFailureLeavesTheTapToInstagram() {
        // forget() put back the stub the patch fills in.
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        List<String> missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("method ClipsVideoPlayerController#the reel's state"));

        TapToPlay.reelStates = asked -> {
            throw new IllegalStateException("the controller failed");
        };
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("a working 'Reels tap' hook (it threw "
                + IllegalStateException.class.getName() + ")"));
    }

    /** While the switch is on, the check answers no, so the feed draws its play button. A no stays a no. */
    @Test
    public void theAutoplayCheckAnswersNoWhileTheSwitchIsOn() {
        assertFalse(TapToPlay.autoplayAllowed(true));
        assertFalse("Instagram's own no, data saver on", TapToPlay.autoplayAllowed(false));
        Settings.TAP_TO_PLAY.save(false);
        assertTrue(TapToPlay.autoplayAllowed(true));
        assertFalse(TapToPlay.autoplayAllowed(false));
    }

    @Test
    public void debugLoggingSaysEachDecisionThenSumsThemUp() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlayForTests.tapEnded(250);
        Object player = new Object();
        TapToPlay.allowStart(player, "autoplay");
        TapToPlay.allowDirectStart(player, "autoplay");
        TapClock.forget();
        TapToPlay.allowStart(new Object(), "resume");
        TapToPlay.allowDirectStart(new Object(), "start");
        TapToPlay.autoplayAllowed(true);
        TapToPlay.autoplayAllowed(true);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Tap to play: allowed autoplay 250 ms armed no"));
        assertTrue(report, report.contains("Tap to play: allowed autoplay 250 ms armed yes (direct)"));
        assertTrue(report, report.contains("Tap to play: held resume no tap armed no"));
        assertTrue(report, report.contains("Tap to play: held start no tap armed no (direct)"));
        assertEquals(report, 1, occurrences(report, "Tap to play: Instagram's autoplay check answers no"));

        // 4 so far: 36 more one by one, then 120 summed up in two lines, with 20 left over.
        for (int i = 0; i < 36 + 120; i++) TapToPlay.allowStart(new Object(), "autoplay");
        report = LogBufferManager.buildExportText();
        assertEquals(TapToPlay.LOGGED_ONE_BY_ONE, occurrences(report, "Tap to play: allowed ")
                + occurrences(report, "Tap to play: held "));
        assertEquals(report, 2, occurrences(report, "Tap to play: 50 more starts, 0 allowed, 50 held"));
    }

    @Test
    public void withoutDebugLoggingNothingIsLogged() {
        LogBufferManager.clearLogBuffer();
        TapToPlay.allowStart(new Object(), "autoplay");
        TapToPlay.autoplayAllowed(true);
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Tap to play: held"));
        assertFalse(report, report.contains("Tap to play: Instagram's autoplay check"));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static final class AlwaysEqual {
        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
