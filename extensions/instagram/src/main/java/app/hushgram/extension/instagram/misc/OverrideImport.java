/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Applies a validated overrides document through the typed native table Instagram's own override
 * editor writes, one parameter at a time.
 *
 * <p>Nothing reaches the native table until the document matches this exact build, schema and
 * session, every changed parameter's type agrees with Instagram's own decoder, and a second
 * capture right before the first write still sees the same manager, store and bytes. The previous
 * overrides are saved first, outside the native store. After writing, the store is read back; if
 * it doesn't hold the result, every change is put back the same typed way. Nothing here writes
 * the native file directly, imports a string, wipes the table or reloads it.
 */
public final class OverrideImport {
    public enum Outcome {
        /** The document already matches the store. No native call was made. */
        UNCHANGED,
        /** The store now holds the document. Instagram applies it after a restart. */
        APPLIED,
        /** The store didn't take the change, and its previous values were put back and read back. */
        ROLLED_BACK,
        /** The store didn't take the change and the put-back couldn't be confirmed. Restore is armed. */
        UNRECOVERED
    }

    public static final class Result {
        public final Outcome outcome;
        public final int changes;
        Result(Outcome outcome, int changes) { this.outcome = outcome; this.changes = changes; }
    }

    /** An earlier import couldn't be confirmed or put back, so only Restore may run for this store. */
    public static final class RestoreFirst extends IOException {
        RestoreFirst() { super("An earlier override import needs Restore"); }
    }

    static final int MAX_CHANGES = 512;
    static final String DIRECTORY = "hushgram-overrides";
    private static final String NULL = "__NULL_VALUE__";
    private static final long POLL_MILLIS = 50;
    /** How long the native store gets to show a typed write in its file. */
    static long settleMillis = 3000;
    private static final Object LOCK = new Object();

    private OverrideImport() {}

    /** Imports a document chosen by the user. Throws before any native call when it doesn't fit. */
    public static Result apply(Activity activity, byte[] document) throws IOException {
        return run(activity, document, false);
    }

    /** Puts back the overrides saved before the last import into this session's store. */
    public static Result restore(Activity activity) throws IOException {
        return run(activity, null, true);
    }

    private static final class Change {
        final OverrideExchange.Parameter parameter;
        final String before, after;
        Change(OverrideExchange.Parameter parameter, String before, String after) {
            this.parameter = parameter; this.before = before; this.after = after;
        }
    }

    private static Result run(Activity activity, byte[] document, boolean restoring) throws IOException {
        synchronized (LOCK) {
            OverrideExchange.Snapshot before = OverrideExchange.capture(activity);
            Store store = new Store(activity, before);
            if (restoring) document = store.backup();
            else if (store.awaitingRestore(before)) throw new RestoreFirst();
            Map<Long, String> target = new TreeMap<>();
            OverrideExchange.validated(document, before, target);
            Map<Long, String> current = OverrideExchange.values(bytes(before.raw), before);
            List<Change> changes = changes(before, current, target);
            if (changes.isEmpty()) {
                if (restoring) store.settled();
                return new Result(Outcome.UNCHANGED, 0);
            }
            if (changes.size() > MAX_CHANGES) throw invalid();
            try {
                for (Change change : changes) {
                    if (DeveloperOptions.getOverrideTypeNative(change.parameter.nativeId) != change.parameter.type) throw invalid();
                }
            } catch (IOException mismatch) { throw mismatch; }
            catch (Throwable failure) { throw invalid(); }
            // The commit boundary: the same session manager, store, schema and bytes as validated.
            OverrideExchange.Snapshot now = OverrideExchange.capture(activity);
            if (now.manager != before.manager || !now.file.equals(before.file) || !Arrays.equals(now.raw, before.raw)
                    || !OverrideExchange.sameSchema(now, before)) throw invalid();
            Object table;
            try { table = DeveloperOptions.getOverrideTableNative(now.manager); }
            catch (Throwable failure) { throw invalid(); }
            if (table == null) throw invalid();
            if (!restoring) store.saveBackup(OverrideExchange.export(before));
            store.arm(before);

            int reached = 0;
            boolean written = true;
            for (Change change : changes) {
                reached++;
                if (!write(table, change.parameter, change.after)) { written = false; break; }
            }
            Map<Long, String> expected = new TreeMap<>(current);
            for (Change change : changes) {
                if (change.after == null) expected.remove(key(change.parameter));
                else expected.put(key(change.parameter), change.after);
            }
            if (written && holds(now, expected)) {
                store.settled();
                return new Result(Outcome.APPLIED, changes.size());
            }
            // Put back everything that may have reached the table, the failed write included.
            boolean undone = true;
            for (int i = reached - 1; i >= 0; i--) {
                Change change = changes.get(i);
                undone &= write(table, change.parameter, change.before);
            }
            if (undone && holds(now, current)) {
                // A restore that didn't hold leaves the store as it was, so Restore stays armed.
                if (!restoring) store.settled();
                return new Result(Outcome.ROLLED_BACK, 0);
            }
            return new Result(Outcome.UNRECOVERED, 0);
        }
    }

    /** The typed changes from current to target. A null override has no typed writer, so it can't move. */
    private static List<Change> changes(OverrideExchange.Snapshot snapshot, Map<Long, String> current,
                                        Map<Long, String> target) throws IOException {
        List<Change> changes = new ArrayList<>();
        TreeSet<Long> keys = new TreeSet<>(current.keySet());
        keys.addAll(target.keySet());
        for (long key : keys) {
            OverrideExchange.Parameter parameter = OverrideExchange.parameter(snapshot, key);
            if (parameter == null) throw invalid();
            String before = current.get(key), after = target.get(key);
            if (before != null && after != null && same(parameter.type, before, after)) continue;
            if (NULL.equals(before) || NULL.equals(after)) throw invalid();
            changes.add(new Change(parameter, before, after));
        }
        return changes;
    }

    private static boolean write(Object table, OverrideExchange.Parameter parameter, String value) {
        try {
            long id = parameter.nativeId;
            if (value == null) return DeveloperOptions.removeOverrideNative(table, id) == 1;
            switch (parameter.type) {
                case 1: return DeveloperOptions.setOverrideBooleanNative(table, id, "true".equals(value) ? 1 : 0) == 1;
                case 2: return DeveloperOptions.setOverrideLongNative(table, id, Long.parseLong(value)) == 1;
                case 3: return DeveloperOptions.setOverrideStringNative(table, id, value) == 1;
                case 4: return DeveloperOptions.setOverrideDoubleNative(table, id, Double.parseDouble(value)) == 1;
                default: return false;
            }
        } catch (Throwable failure) {
            // A native failure can carry a value or a session path. Keep only the verdict.
            return false;
        }
    }

    /** Waits for the store's own file to hold exactly these values, compared by type. */
    private static boolean holds(OverrideExchange.Snapshot snapshot, Map<Long, String> expected) {
        long deadline = System.nanoTime() + settleMillis * 1_000_000L;
        while (true) {
            try {
                byte[] bytes = null;
                if (snapshot.file.exists()) {
                    try (InputStream input = new FileInputStream(snapshot.file)) { bytes = OverrideExchange.read(input); }
                }
                if (same(snapshot, OverrideExchange.values(bytes(bytes), snapshot), expected)) return true;
            } catch (IOException | RuntimeException partial) {
                // The native writer may be partway through the file. Read it again.
            }
            if (System.nanoTime() - deadline >= 0) return false;
            try { Thread.sleep(POLL_MILLIS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
    }

    private static boolean same(OverrideExchange.Snapshot snapshot, Map<Long, String> one, Map<Long, String> other) {
        if (!one.keySet().equals(other.keySet())) return false;
        for (Map.Entry<Long, String> entry : one.entrySet()) {
            OverrideExchange.Parameter parameter = OverrideExchange.parameter(snapshot, entry.getKey());
            if (parameter == null || !same(parameter.type, entry.getValue(), other.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean same(int type, String one, String other) {
        if (one.equals(other)) return true;
        if (NULL.equals(one) || NULL.equals(other)) return false;
        try {
            if (type == 2) return Long.parseLong(one) == Long.parseLong(other);
            if (type == 4) return Double.compare(Double.parseDouble(one), Double.parseDouble(other)) == 0;
        } catch (NumberFormatException failure) { return false; }
        return false;
    }

    private static long key(OverrideExchange.Parameter parameter) { return OverrideExchange.key(parameter.config, parameter.index); }
    private static byte[] bytes(byte[] raw) { return raw == null ? "{}".getBytes(StandardCharsets.UTF_8) : raw; }
    static IOException invalid() { return new IOException("Invalid or unavailable native overrides"); }

    /**
     * The saved copy and the armed marker for one native store, named by a hash of its path so an
     * account's copy never applies to another. Both live in Instagram's private files, outside the
     * native store, and every write is a synced temporary file moved into place.
     */
    private static final class Store {
        final File backup, armed;

        Store(Activity activity, OverrideExchange.Snapshot snapshot) throws IOException {
            File directory = new File(activity.getFilesDir(), DIRECTORY);
            String name = sha256(snapshot.file.getPath());
            backup = new File(directory, name + ".json");
            armed = new File(directory, name + ".armed");
        }

        byte[] backup() throws IOException {
            if (!backup.isFile()) throw invalid();
            try (InputStream input = new FileInputStream(backup)) { return OverrideExchange.read(input); }
        }

        /** A marker from another Instagram build can't be restored there, so it no longer blocks. */
        boolean awaitingRestore(OverrideExchange.Snapshot snapshot) throws IOException {
            if (!armed.exists()) return false;
            byte[] marker;
            try (InputStream input = new FileInputStream(armed)) { marker = OverrideExchange.read(input); }
            if (String.valueOf(OverrideExchange.hostCode(snapshot)).equals(new String(marker, StandardCharsets.UTF_8))) return true;
            settled();
            return armed.exists();
        }

        void saveBackup(byte[] document) throws IOException { replace(backup, document); }
        void arm(OverrideExchange.Snapshot snapshot) throws IOException {
            replace(armed, String.valueOf(OverrideExchange.hostCode(snapshot)).getBytes(StandardCharsets.UTF_8));
        }
        /**
         * Best effort, after the outcome is already known. A marker that won't go away only keeps
         * imports waiting for Restore, which is the safe side.
         */
        void settled() {
            //noinspection ResultOfMethodCallIgnored
            armed.delete();
        }

        private static void replace(File target, byte[] bytes) throws IOException {
            File directory = Objects.requireNonNull(target.getParentFile());
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw invalid();
            File temporary = new File(directory, target.getName() + ".tmp");
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException failure) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
                throw invalid();
            }
        }

        private static String sha256(String text) throws IOException {
            try {
                StringBuilder name = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) {
                    name.append(String.format(Locale.ROOT, "%02x", value & 255));
                }
                return name.toString();
            } catch (Exception failure) { throw invalid(); }
        }
    }
}
