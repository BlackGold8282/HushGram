/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The stories you tapped Mark as seen on while viewing anonymously, and what became of each.
 *
 * <p>A story is known by its media ID, the digits Instagram's id for it starts with. Instagram keys
 * each story in a batch of views by that ID, its owner's and its reel's, joined by underscores, so a
 * batch's stories are matched to marks by the first part of their key. A key of any other shape is
 * never matched, and that story stays held back.
 *
 * <p>When a batch goes to be sent, {@link #choose} picks the stories in it that are marked, and the
 * ones an earlier batch held back that have been marked since, and puts them, and nothing else, in a
 * new batch of Instagram's that started empty. Each story picked counts as sent: its mark is spent,
 * and if it turns up in a batch again it's held back like any other. A mark whose story isn't in
 * the batch stays until a send carries it, for 24 hours after the tap at most. Marks are kept in
 * memory only, so they also lapse when Instagram's process ends.
 *
 * <p>Batches are held back whole while views are anonymous. So a story you mark after its batch went
 * can still go out, the stories of each batch held back while the button is on are kept here, up to
 * {@link #MAX_HELD} of them for 24 hours, and a tap on one of those sends it straight away.
 */
final class StoryMarks {
    /** How long a mark waits for a send, and how long a held-back story or a sent one is remembered. */
    static final long LIFETIME_MS = 24L * 60 * 60 * 1000;

    static final int MAX_MARKS = 200;
    static final int MAX_SENT = 500;
    static final int MAX_HELD = 300;

    /** Where a story stands. */
    enum State {
        /** Held back like any other while you view anonymously. */
        UNMARKED,
        /** Marked, and waiting for the next send to carry it. */
        MARKED,
        /** Marked and handed to Instagram's send. */
        SENT,
    }

    /** A story a batch held back: its key and entry in the batch's map, and when. */
    private static final class Held {
        final String story;
        final Object entry;
        final long at;

        Held(String story, Object entry, long at) {
            this.story = story;
            this.entry = entry;
            this.at = at;
        }
    }

    private final LongSupplier clock;
    /** Story ID to when it was marked, oldest first. */
    private final LinkedHashMap<String, Long> marked = new LinkedHashMap<>();
    /** Story ID to when it was sent, oldest first. */
    private final LinkedHashMap<String, Long> sent = new LinkedHashMap<>();
    /** A held-back story's key in a batch's map, to the story and its entry, oldest first. */
    private final LinkedHashMap<Object, Held> held = new LinkedHashMap<>();

    StoryMarks(LongSupplier clock) {
        this.clock = clock;
    }

    /** Where [story] stands, UNMARKED for null. */
    synchronized State state(@Nullable String story) {
        if (story == null) return State.UNMARKED;
        prune(clock.getAsLong());
        if (sent.containsKey(story)) return State.SENT;
        return marked.containsKey(story) ? State.MARKED : State.UNMARKED;
    }

    /** A tap: marks [story], or takes its mark back. A story already sent stays sent. Answers where it stands now. */
    synchronized State toggle(String story) {
        long now = clock.getAsLong();
        prune(now);
        if (sent.containsKey(story)) return State.SENT;
        if (marked.remove(story) != null) return State.UNMARKED;
        marked.put(story, now);
        trim(marked, MAX_MARKS);
        return State.MARKED;
    }

    /** Whether a batch held [story] back and it's still kept here to send. */
    synchronized boolean held(String story) {
        prune(clock.getAsLong());
        for (Held entry : held.values()) {
            if (entry.story.equals(story)) return true;
        }
        return false;
    }

    /**
     * Picks what goes out of [batch]: a new batch from [batches] holding only the marked stories of
     * [batch] and the marked ones held back before, or null when there are none, and then nothing
     * goes. Keeps the other stories of [batch] as held back. Null too when the new batch doesn't
     * start empty, since anything else in it would go out as well.
     */
    @Nullable
    synchronized Object choose(@Nullable Object batch, StorySeen.Batches batches) {
        long now = clock.getAsLong();
        prune(now);
        Map<Object, Object> stories = batch == null ? null : batches.stories(batch);
        Map<Object, Object> chosen = new LinkedHashMap<>();
        if (stories != null) {
            List<Map.Entry<Object, Object>> entries = new ArrayList<>(stories.entrySet());
            for (Map.Entry<Object, Object> entry : entries) {
                String story = storyOfKey(entry.getKey());
                if (story == null) continue;
                if (marked.containsKey(story)) {
                    chosen.put(entry.getKey(), entry.getValue());
                } else if (!sent.containsKey(story)) {
                    held.remove(entry.getKey());
                    held.put(entry.getKey(), new Held(story, entry.getValue(), now));
                }
            }
            trim(held, MAX_HELD);
        }
        for (Map.Entry<Object, Held> entry : held.entrySet()) {
            if (marked.containsKey(entry.getValue().story) && !chosen.containsKey(entry.getKey())) {
                chosen.put(entry.getKey(), entry.getValue().entry);
            }
        }
        if (chosen.isEmpty()) return null;

        Object fresh = batches.empty();
        Map<Object, Object> into = fresh == null ? null : batches.stories(fresh);
        if (into == null || !into.isEmpty()) return null;
        into.putAll(chosen);
        for (Object key : chosen.keySet()) {
            held.remove(key);
            String story = storyOfKey(key);
            marked.remove(story);
            sent.remove(story);
            sent.put(story, now);
        }
        trim(sent, MAX_SENT);
        return fresh;
    }

    /** Forgets everything. */
    synchronized void clear() {
        marked.clear();
        sent.clear();
        held.clear();
    }

    /**
     * The story ID in Instagram's id for a story, the media ID before its owner's ("123_456"), or
     * null for an id of another shape, such as a live video's or an ad's that isn't a post.
     */
    @Nullable
    static String storyOf(@Nullable String id) {
        if (id == null) return null;
        int split = id.indexOf('_');
        String media = split < 0 ? id : id.substring(0, split);
        if (!digits(media)) return null;
        if (split >= 0 && !digits(id.substring(split + 1))) return null;
        return media;
    }

    /** The story ID a batch's key is for, the first of its media, owner and reel parts, or null. */
    @Nullable
    static String storyOfKey(@Nullable Object key) {
        if (!(key instanceof String)) return null;
        String text = (String) key;
        int first = text.indexOf('_');
        int second = first < 0 ? -1 : text.indexOf('_', first + 1);
        if (second < 0 || second == text.length() - 1) return null;
        String media = text.substring(0, first);
        return digits(media) && digits(text.substring(first + 1, second)) ? media : null;
    }

    private static boolean digits(String text) {
        if (text.isEmpty() || text.length() > 30) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void prune(long now) {
        marked.values().removeIf(at -> expired(at, now));
        sent.values().removeIf(at -> expired(at, now));
        held.values().removeIf(entry -> expired(entry.at, now));
    }

    private static boolean expired(long at, long now) {
        return now - at >= LIFETIME_MS;
    }

    private static <K, V> void trim(LinkedHashMap<K, V> map, int max) {
        Iterator<K> oldest = map.keySet().iterator();
        while (map.size() > max && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
