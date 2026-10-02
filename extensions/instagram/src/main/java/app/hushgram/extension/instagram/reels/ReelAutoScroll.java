/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Keep Reels auto scroll on" patch.
 *
 * <p>Instagram 449 asks one method whether auto scroll is on in Reels: the scroller that moves on
 * to the next reel reads it, and so does every auto scroll switch Instagram draws. Where the answer
 * comes from is a server choice: a value kept in memory, which every start forgets; a timer you set
 * when you turn it on; or a saved preference, which Instagram itself clears whenever you leave
 * Reels in one setup. The patch passes every answer of that method through {@link #answer}, every
 * answer of the saved preference's getter through {@link #saved}, and hands each choice made with
 * Instagram's own switches that turn it on or off to {@link #chosen}.
 *
 * <p>HushGram keeps the last choice ({@link Settings#REEL_AUTO_SCROLL_ON}). The check answering on
 * is remembered as on, and turning auto scroll off with one of Instagram's switches is remembered
 * as off. While the switch is on, Instagram's "off" is answered with what's remembered, so auto
 * scroll stays on across restarts and leaving Reels until you turn it off, and Instagram's own
 * switches show that answer. The memory follows Instagram while the switch is off too, so turning
 * the switch on later goes by your latest choice.
 *
 * <p>The saved preference's own "on" is never remembered. In the memory and timer setups Instagram
 * doesn't go by it, and turning auto scroll off there doesn't clear it, so an old "on" saved by the
 * Reels tab's long press would otherwise turn auto scroll back on as soon as anything read it. Where
 * Instagram does go by the preference, the check reads it through the getter, so its "on" still
 * reaches {@link #answer} that way.
 *
 * <p>Paused, before the settings are read, or when anything goes wrong, Instagram's answer stands
 * and nothing is remembered. {@link #answer} runs every time the scroller checks, so it reads a
 * saved value and writes only when the choice changes. No hook throws.
 */
public final class ReelAutoScroll {
    /** Where the last choice is kept: {@link Settings#REEL_AUTO_SCROLL_ON} outside tests. */
    interface Memory {
        boolean on();

        void remember(boolean on);
    }

    static final Memory SAVED = new Memory() {
        @Override
        public boolean on() {
            return Settings.REEL_AUTO_SCROLL_ON.savedValue();
        }

        @Override
        public void remember(boolean on) {
            Settings.REEL_AUTO_SCROLL_ON.save(on);
        }
    };

    private ReelAutoScroll() {
    }

    /**
     * Injected at every return of Instagram's check of whether auto scroll is on in Reels, with
     * Instagram's answer as an int (non-zero is yes). Answers yes when Instagram does, remembering
     * it, and while the switch is on, also when auto scroll was last left on. Otherwise answers what
     * Instagram did.
     */
    public static boolean answer(int instagram) {
        return answer(instagram, ReelAutoScroll::learning, ReelAutoScroll::switchedOn, SAVED);
    }

    static boolean answer(int instagram, BooleanSupplier learning, BooleanSupplier on, Memory memory) {
        boolean scrolling = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (!learning.getAsBoolean()) return scrolling;
            if (scrolling) {
                if (!memory.on()) {
                    memory.remember(true);
                    Logger.printDebug(() -> "Reel auto scroll: on, remembered");
                }
                return true;
            }
            return on.getAsBoolean() && memory.on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll answer", failure);
            return scrolling;
        }
    }

    /**
     * Injected at every return of the saved auto scroll preference's getter, with the saved value as
     * an int (non-zero is on). Answers on when the preference is, and while the switch is on, also
     * when auto scroll was last left on, so the Reels tab's long press offers to turn off what's on.
     * Remembers nothing: see the class notes.
     */
    public static boolean saved(int instagram) {
        return saved(instagram, ReelAutoScroll::switchedOn, SAVED);
    }

    static boolean saved(int instagram, BooleanSupplier on, Memory memory) {
        boolean stored = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            return stored || (on.getAsBoolean() && memory.on());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll saved", failure);
            return stored;
        }
    }

    /**
     * Injected where one of Instagram's switches turns auto scroll on or off, with the choice as
     * an int (non-zero is on). Off is remembered, so auto scroll stays off after a restart. On is
     * remembered once Instagram answers that it's on, since some setups ask how long for first.
     */
    public static void chosen(int choice) {
        chosen(choice, ReelAutoScroll::learning, SAVED);
    }

    static void chosen(int choice, BooleanSupplier learning, Memory memory) {
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (choice != 0 || !learning.getAsBoolean()) return;
            if (memory.on()) {
                memory.remember(false);
                Logger.printDebug(() -> "Reel auto scroll: turned off, remembered");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll choice", failure);
        }
    }

    /** Whether the memory may follow Instagram: the settings are read and HushGram isn't paused. */
    static boolean learning() {
        return Utils.settingsReady() && !HushgramPause.isPaused();
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_REEL_AUTO_SCROLL.get();
    }
}
