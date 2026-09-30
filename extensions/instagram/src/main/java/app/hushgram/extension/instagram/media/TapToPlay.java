/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Tap to play: videos, reels and stories wait for a tap.
 *
 * <p>Instagram 449 plays through IgGrootPlayer. A feed video or a reel starts through
 * IgVideoPlayerImpl's playInternal, which then plays its IgGrootPlayer, and the story viewer and a
 * few other screens play an IgGrootPlayer themselves. The patch asks {@link #allowStart} first
 * thing in playInternal, handing it that IgGrootPlayer, and {@link #allowDirectStart} first thing
 * in IgGrootPlayer's play, and a no makes either return before it does anything. Each start
 * carries a reason, a string, but it can't tell a tap from an automatic start: playInternal only
 * ever gets "autoplay" or "resume", and a story you tap starts with "autoplay". So a start goes
 * ahead when one of these holds:
 *
 * <ul>
 *   <li>The player is armed: it made a start this gate let through, and it hasn't been paused or
 *       given a new video since. That keeps what a tap started going through Instagram's own
 *       restarts, such as playInternal's play of that same player, or the play after a seek's
 *       pause ({@link #MOMENTARY}).</li>
 *   <li>A tap ended no more than {@link #TAP_WINDOW_MS} ago ({@link TapClock}).</li>
 * </ul>
 *
 * <p>Every other start is held, and the player stays where it was, showing its first frame or its
 * cover. Instagram's own autoplay check answers no while the switch is on ({@link #autoplayAllowed}),
 * so the feed draws the play button it draws when data saver is on. A tap on a reel resumes it only
 * when Instagram knows you paused it yourself, so a tap on a held reel would go down the pause path
 * and do nothing: {@link #resumeOnTap} sends a tap on a reel that's waiting down the resume path
 * instead. Off, paused, before the settings
 * are ready, or when anything here throws, every start goes ahead and the check answers what
 * Instagram decided, as it would unpatched.
 */
public final class TapToPlay {
    /** How long after a tap ends a start still counts as that tap's. */
    static final long TAP_WINDOW_MS = 1000;

    /**
     * How long a start keeps its player armed through a new video. IgGrootPlayer can be prepared
     * again right after the play the gate let through, and that shouldn't disarm it. A non-tap
     * gesture expires this grace before another video can bind.
     */
    static final long BIND_GRACE_MS = 2000;

    /**
     * Pause reasons Instagram plays the same video again right after, so they don't undo the tap
     * that started it: a seek pauses and plays again with "seek_force_pause", a drag of the
     * scrubber with "seek", a pinch to zoom with "paused_for_pinch_to_zoom", and a reel that
     * loops with "paused_for_replay".
     */
    static final Set<String> MOMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "seek", "seek_force_pause", "paused_for_pinch_to_zoom", "paused_for_replay")));

    /**
     * The IgVideoPlayerImpl states a tap on a reel starts from: prepared and never started, which is
     * where the gate leaves a held reel, and paused, which is where a pause for the comments sheet
     * leaves it. ClipsVideoPlayer's resume restarts a player only from these two, so a tap on a reel
     * in any other state does what Instagram decided.
     */
    static final Set<String> RESUMABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("PREPARED", "PAUSED")));

    /** What {@link ReelStateReader}'s stubs return until the patch fills them in. */
    static final Object NOT_PATCHED = new Object();

    /** How many decisions get a line of their own before the log sums them up. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** How many decisions each summary line covers after that. */
    static final int SUMMED_UP_BY = 50;

    private static final String SOURCE = "TapToPlay";

    private static final ArmedPlayers ARMED = new ArmedPlayers();
    private static final Object LOG_LOCK = new Object();
    private static int decisions;
    private static int allowedSinceSummary;
    private static int heldSinceSummary;
    private static boolean checkLogged;

    /** Makes the next decision throw, once. For tests. */
    @Nullable
    static volatile RuntimeException failNext;

    private TapToPlay() { }

    /**
     * The hook, first thing in IgVideoPlayerImpl's playInternal, with the IgGrootPlayer it's about
     * to play. False, and playInternal returns at once, before it marks the video as playing.
     */
    public static boolean allowStart(@Nullable Object player, @Nullable String reason) {
        return allow(player, reason, "player start", "");
    }

    /** The hook, first thing in IgGrootPlayer's play. False, and the play returns at once. */
    public static boolean allowDirectStart(@Nullable Object player, @Nullable String reason) {
        return allow(player, reason, "direct start", " (direct)");
    }

    /**
     * The hook, first thing in IgGrootPlayer's pause. A paused player waits for a tap again, unless
     * the pause is one of the {@link #MOMENTARY} ones Instagram plays on from by itself.
     */
    public static void paused(@Nullable Object player, @Nullable String reason) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "player pause");
            if (reason == null || !MOMENTARY.contains(reason)) ARMED.disarm(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "player pause", failure);
        }
    }

    /** The hook, first thing where IgGrootPlayer is prepared with a video. A new video waits for a tap. */
    public static void rebound(@Nullable Object player) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "player prepare");
            ARMED.disarmUnlessArmedSince(player, SystemClock.uptimeMillis() - BIND_GRACE_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "player prepare", failure);
        }
    }

    /** A swipe keeps the current video armed, but the next video must wait for its own start. */
    static void nonTapGesture() {
        ARMED.expireBindGrace();
    }

    /**
     * The hook at the return of Instagram's VideoAutoplayChecker, the check behind "Use less
     * mobile data". While the switch is on it answers no, so the feed draws its play button on a
     * video instead of starting it. Nothing is stored, so turning the switch off gives Instagram's
     * own answer again.
     */
    public static boolean autoplayAllowed(boolean answer) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            if (!answer || !on()) return answer;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "autoplay check");
            logCheckOnce();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "autoplay check", failure);
            return answer;
        }
    }

    /**
     * The hook in Instagram's Reels tap, at the branch where it has decided between resuming the
     * reel and pausing it, with that decision and the tap's navigator. Instagram resumes only a reel
     * you paused yourself, so a tap on a reel this patch held took the pause path, found nothing
     * playing and did nothing, however often you tapped. While the switch is on, a tap on a reel that's
     * prepared or paused ({@link #RESUMABLE}) resumes it, the way it resumes one you paused, and that
     * start comes inside the tap's window. That includes a reel Instagram stopped for a tap on one of
     * its stickers, which its own tap leaves stopped. A playing reel still pauses, and a reel with no
     * player or one still loading does what Instagram decided. Off, paused, before the settings are
     * ready, or when anything here throws, the tap does what Instagram decided.
     */
    public static boolean resumeOnTap(boolean resume, @Nullable Object navigator) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (resume || navigator == null || !on()) return resume;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "Reels tap");
            Object state = reelStates.stateOf(navigator);
            if (state == NOT_PATCHED) {
                HookStatus.missingMember(FamilyNames.TAP_TO_PLAY, "method", "ClipsVideoPlayerController", "the reel's state");
                return resume;
            }
            String name = state instanceof Enum ? ((Enum<?>) state).name() : null;
            boolean start = name != null && RESUMABLE.contains(name);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: a tap on a reel "
                    + (name == null ? "with no player" : name) + (start ? " starts it" : " goes to Instagram"));
            return start;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "Reels tap", failure);
            return resume;
        }
    }

    /** The state of the reel a tap landed on. Tests put their own in. */
    interface ReelStates {
        /** IgVideoPlayerImpl's state enum, null when the tap's reel has no player, or {@link #NOT_PATCHED}. */
        @Nullable
        Object stateOf(Object navigator);
    }

    /**
     * Reaches the reader only through this, from inside {@link #resumeOnTap}'s try, so a reader the
     * phone refuses to load costs the Reels tap and never the start gate beside it.
     */
    static ReelStates reelStates = ReelStateReader::reelState;

    private static void logCheckOnce() {
        synchronized (LOG_LOCK) {
            if (checkLogged) return;
            checkLogged = true;
        }
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                () -> "Tap to play: Instagram's autoplay check answers no");
    }

    private static boolean allow(@Nullable Object player, @Nullable String reason, String hook, String path) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (!on()) return true;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, hook);
            return decide(player, reason, SystemClock.uptimeMillis(), path);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, hook, failure);
            return true;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.TAP_TO_PLAY.get();
    }

    /** The rule, with the clock passed in. Arms the player when it lets the start through. */
    static boolean decide(@Nullable Object player, @Nullable String reason, long now, String path) {
        boolean armed = ARMED.armed(player);
        long sinceTap = TapClock.msSinceTap(now);
        boolean allowed = armed || (sinceTap >= 0 && sinceTap <= TAP_WINDOW_MS);
        if (allowed && !armed) ARMED.arm(player, now);
        logDecision(allowed, reason, sinceTap, armed, path);
        return allowed;
    }

    private static void logDecision(boolean allowed, @Nullable String reason, long sinceTap, boolean armed, String path) {
        String line;
        synchronized (LOG_LOCK) {
            decisions++;
            if (decisions <= LOGGED_ONE_BY_ONE) {
                line = "Tap to play: " + (allowed ? "allowed " : "held ") + (reason == null ? "no reason" : reason)
                        + " " + (sinceTap < 0 ? "no tap" : sinceTap + " ms") + " armed " + (armed ? "yes" : "no") + path;
            } else {
                if (allowed) allowedSinceSummary++;
                else heldSinceSummary++;
                if (allowedSinceSummary + heldSinceSummary < SUMMED_UP_BY) return;
                line = "Tap to play: " + SUMMED_UP_BY + " more starts, " + allowedSinceSummary + " allowed, "
                        + heldSinceSummary + " held";
                allowedSinceSummary = 0;
                heldSinceSummary = 0;
            }
        }
        final String logged = line;
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> logged);
    }

    /** Forgets every armed player and the log's counts, and puts the patched reel state reader back. For tests. */
    static void forget() {
        ARMED.clear();
        reelStates = ReelStateReader::reelState;
        synchronized (LOG_LOCK) {
            decisions = 0;
            allowedSinceSummary = 0;
            heldSinceSummary = 0;
            checkLogged = false;
        }
        failNext = null;
    }

    static boolean armed(Object player) {
        return ARMED.armed(player);
    }

    static int armedCount() {
        return ARMED.size();
    }

    /**
     * The players a start was let through on, by identity and weakly: a player Instagram drops is
     * forgotten with it, and one whose equals says it's another player is still itself.
     */
    static final class ArmedPlayers {
        private final Map<Key, Long> armedAt = new HashMap<>();
        private final ReferenceQueue<Object> gone = new ReferenceQueue<>();

        synchronized void arm(@Nullable Object player, long now) {
            purge();
            if (player != null) armedAt.put(new Key(player, gone), now);
        }

        synchronized boolean armed(@Nullable Object player) {
            purge();
            return player != null && armedAt.containsKey(new Key(player, null));
        }

        synchronized void disarm(@Nullable Object player) {
            purge();
            if (player != null) armedAt.remove(new Key(player, null));
        }

        synchronized void expireBindGrace() {
            purge();
            armedAt.replaceAll((player, at) -> Long.MIN_VALUE);
        }

        /** Disarms [player] unless its start came at or after [since]. */
        synchronized void disarmUnlessArmedSince(@Nullable Object player, long since) {
            purge();
            if (player == null) return;
            Key key = new Key(player, null);
            Long at = armedAt.get(key);
            if (at != null && at < since) armedAt.remove(key);
        }

        synchronized int size() {
            purge();
            return armedAt.size();
        }

        synchronized void clear() {
            armedAt.clear();
            purge();
        }

        private void purge() {
            Reference<?> cleared;
            while ((cleared = gone.poll()) != null) {
                armedAt.remove(cleared);
            }
        }

        private static final class Key extends WeakReference<Object> {
            private final int hash;

            Key(Object player, @Nullable ReferenceQueue<Object> queue) {
                super(player, queue);
                hash = System.identityHashCode(player);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (other == this) return true;
                if (!(other instanceof Key)) return false;
                Object mine = get();
                return mine != null && mine == ((Key) other).get();
            }
        }
    }
}
