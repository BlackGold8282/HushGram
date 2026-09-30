/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import java.util.List;

/**
 * Instagram's media model, read for a save. Instagram's getters have shortened names that change
 * with every release, so none is named here: each method below answers null as built, and the
 * download patch writes its body at patch time, as a call to the getter it finds by the field it
 * reads. A method whose body wasn't written answers null, and a save then has nothing to go on and
 * says so.
 *
 * <p>Every argument is Instagram's own object, handed over as an Object, of the type the method
 * names. The written bodies cast it, so a wrong type throws, and the callers catch that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class InstagramMedia {
    private InstagramMedia() {
    }

    /** A Media's {@code video_versions}: the single MP4 files Instagram lists for its video. */
    public static List<?> videoVersions(Object media) {
        return null;
    }

    /** A Media's {@code video_dash_manifest}: the DASH manifest its player streams, as text. */
    public static String dashManifest(Object media) {
        return null;
    }

    /** A Media's id, {@code <media pk>_<owner's pk>}. */
    public static String mediaId(Object media) {
        return null;
    }

    /** A Media's {@code user}: who posted it. */
    public static Object owner(Object media) {
        return null;
    }

    /** A Media's {@code taken_at}: when it was posted, in seconds since 1970. */
    public static Long takenAt(Object media) {
        return null;
    }

    /** A User's {@code username}. */
    public static String username(Object user) {
        return null;
    }

    /** A video version's {@code url}. */
    public static String versionUrl(Object version) {
        return null;
    }

    /** A video version's {@code width}, in pixels. */
    public static Integer versionWidth(Object version) {
        return null;
    }

    /** A video version's {@code height}, in pixels. */
    public static Integer versionHeight(Object version) {
        return null;
    }
}
