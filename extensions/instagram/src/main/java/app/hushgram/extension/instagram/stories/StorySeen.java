/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "View stories anonymously" patch.
 *
 * <p>Instagram gathers the stories you've seen into a batch and posts it to {@code media/seen/},
 * which is what puts you on each story's viewer list. The patch asks {@link #toSend} first thing in
 * the method that sends a batch, and sends what it answers in the batch's place, or nothing at all
 * on null. Each caller has already cleared its own copy, so a batch held back is gone, and turning
 * the switch off lets the next batch through.
 *
 * <p>With the second switch on, the stories you tapped Mark as seen on ({@link StorySeenButton})
 * still go out: the answer is then a batch of the extension's own, started empty by Instagram's own
 * constructor, holding those stories and nothing else. See {@link StoryMarks}.
 *
 * <p>It fails open, like the other hooks: the switch off, a pause, settings that aren't ready yet,
 * or a failure reading them send the views as Instagram would. A failure picking out the marked
 * stories holds the whole batch back, as the switch on does without marks.
 */
public final class StorySeen {
    /** The diagnostic counter route: each batch of views Instagram went to send, and the ones held back. */
    static final String ROUTE = "Story views";

    /** What a batch held back is counted under. */
    static final String HELD_BACK = "viewed stories";

    /** The stories marked as seen, shared with the button. */
    static final StoryMarks MARKS = new StoryMarks(SystemClock::elapsedRealtime);

    /** Where {@link #toSend} reads and starts batches. Tests hand in their own. */
    interface Batches {
        /** A new batch of Instagram's, holding nothing, or null. */
        @Nullable
        Object empty();

        /** The map of stories [batch] holds, Instagram's own, which changes the batch when changed. */
        @Nullable
        Map<Object, Object> stories(Object batch);
    }

    static final Batches PATCHED = new Batches() {
        @Override
        public Object empty() {
            return emptyBatch();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<Object, Object> stories(Object batch) {
            return (Map<Object, Object>) seenStories(batch);
        }
    };

    private StorySeen() {
    }

    /**
     * Asked first thing in the send, with the batch it was handed. Answers the batch to send: the
     * same one while the switch is off, HushGram is paused or the settings aren't ready; one holding
     * only the stories you marked; or null, and the send returns before the request is built. Never
     * throws.
     */
    @Nullable
    public static Object toSend(Object batch) {
        return toSend(batch, PATCHED, StorySeen::anonymous, StorySeenButton::switchedOn, MARKS);
    }

    @Nullable
    static Object toSend(@Nullable Object batch, Batches batches, BooleanSupplier anonymous, BooleanSupplier marking,
                         StoryMarks marks) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!anonymous.getAsBoolean()) return batch;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story seen send", failure);
            return batch;
        }
        Object marked = null;
        try {
            if (marking.getAsBoolean()) marked = marks.choose(batch, batches);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "marked story send", failure);
            marked = null;
        }
        if (marked == null) {
            FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
            Logger.printDebug(() -> "Story views: held back a batch of viewed stories");
        } else {
            Logger.printDebug(() -> "Story views: sent only the stories marked as seen");
        }
        return marked;
    }

    /** Whether views are held back: the switch on, HushGram not paused and the settings read. */
    static boolean anonymous() {
        return Utils.settingsReady() && Settings.VIEW_STORIES_ANONYMOUSLY.get();
    }

    /**
     * Sends the stories marked as seen that an earlier batch held back, through Instagram's own send
     * for [session]'s account, handing it an empty batch for {@link #toSend} to fill. Never throws.
     */
    static void sendMarked(Object session) {
        try {
            Object batch = emptyBatch();
            if (batch != null) send(session, batch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "marked story send", failure);
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: a new batch of Instagram's, from its constructor taking nothing. */
    @Nullable
    public static Object emptyBatch() {
        return null;
    }

    /** Filled in by the patch: the map of stories [batch] holds, the one sent under "reels". */
    @Nullable
    public static Map<?, ?> seenStories(Object batch) {
        return null;
    }

    /** Filled in by the patch: hands [batch] to Instagram's send for [session]'s account. */
    public static void send(Object session, Object batch) {
    }
}
