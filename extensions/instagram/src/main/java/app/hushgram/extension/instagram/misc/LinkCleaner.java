/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Takes Instagram's tracking keys out of its own links, offline.
 *
 * <p>Only the query changes, and only by losing whole {@code key=value} pairs whose key is on the
 * list below, on an Instagram host. Everything else stays as it was written: the other pairs in
 * their order, how each value is encoded, the fragment, and links to any other site. So a link
 * that carries no tracking key comes back as the same string, and cleaning a clean link changes
 * nothing.
 *
 * <p>Three ways in: the two share-link parsers hand their link here as Instagram reads it from
 * the server, and every clipboard copy and share sheet Instagram opens goes through the stand-ins
 * below, which clean the Instagram links in the text on its way out. Nothing here goes online.
 */
public final class LinkCleaner {

    private LinkCleaner() {}

    /*
     * igsh, igshid and igsi are the keys Instagram 449 itself lists as its share-tracking keys
     * (the "igsh,igshid,igsi" default it strips from links it opens). igsh is a per-share id that
     * ties a link back to the account that shared it. The utm_ keys only label where a click came
     * from (ig_web_copy_link, ig_story_item_share, qr), and fbclid is Meta's click id. stkn is
     * the newer per-share id: on 449, Copy link and Share both gave /p/<code>/?stkn=<base64 id>,
     * a different id each time, and no igsh. The server adds it, so the app's own code never
     * names it. None of them picks what a link opens: the path does (/p/, /reel/, /stories/, a
     * username).
     */
    private static final Set<String> TRACKING = keys("igsh", "igshid", "igsi", "stkn", "fbclid",
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term", "utm_id");

    /** Instagram's hosts, each with its subdomains. */
    private static final String[] INSTAGRAM_HOSTS = {"instagram.com", "instagr.am", "ig.me"};

    /**
     * A web link in running text. It stops before trailing punctuation, so the full stop after a
     * link in a sentence isn't taken for part of its last value.
     */
    private static final Pattern WEB_LINK = Pattern.compile("(?i)https?://[^\\s<>\"']*[^\\s<>\"'.,!?;:]");

    /**
     * Injected where Instagram reads a share link from the server. Answers the link without its
     * tracking keys, or as it came while the switch is off, HushGram is paused or the settings
     * aren't ready yet. Never throws.
     */
    public static String sanitizeShared(String url) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        return enabled() ? clean(url) : url;
    }

    /** Stands in for {@link ClipboardManager#setPrimaryClip}: the same clip with its Instagram links cleaned. */
    public static void setPrimaryClip(ClipboardManager manager, ClipData clip) {
        manager.setPrimaryClip(sanitizedClip(clip));
    }

    /** Stands in for {@link Intent#createChooser(Intent, CharSequence)}, cleaning the shared text first. */
    public static Intent createChooser(Intent target, CharSequence title) {
        return Intent.createChooser(sanitizedShare(target), title);
    }

    /** Stands in for {@link Intent#createChooser(Intent, CharSequence, IntentSender)}, cleaning the shared text first. */
    public static Intent createChooser(Intent target, CharSequence title, IntentSender sender) {
        return Intent.createChooser(sanitizedShare(target), title, sender);
    }

    /**
     * [clip] with the Instagram links in its text items cleaned, or [clip] itself when there's
     * nothing to clean, the switch is off, or anything goes wrong. A clip with a URI, an intent or
     * HTML in an item is left as it came.
     */
    static ClipData sanitizedClip(ClipData clip) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (clip == null || !enabled()) return clip;
        try {
            int count = clip.getItemCount();
            List<ClipData.Item> items = new ArrayList<>(count);
            boolean changed = false;
            for (int i = 0; i < count; i++) {
                ClipData.Item item = clip.getItemAt(i);
                CharSequence text = item.getText();
                if (text == null || item.getUri() != null || item.getIntent() != null || item.getHtmlText() != null) {
                    items.add(item);
                    continue;
                }
                String cleaned = cleanText(text.toString());
                if (cleaned.contentEquals(text)) {
                    items.add(item);
                } else {
                    items.add(new ClipData.Item(cleaned));
                    changed = true;
                }
            }
            if (!changed || items.isEmpty()) return clip;
            ClipData copy = new ClipData(clip.getDescription(), items.get(0));
            for (int i = 1; i < items.size(); i++) copy.addItem(items.get(i));
            return copy;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "clipboard", t);
            return clip;
        }
    }

    /**
     * [target] with the Instagram links in its shared text cleaned, in place, or as it came when
     * there's nothing to clean, the switch is off, or anything goes wrong.
     */
    static Intent sanitizedShare(Intent target) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (target == null || !enabled()) return target;
        try {
            CharSequence text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null) return target;
            String cleaned = cleanText(text.toString());
            if (!cleaned.contentEquals(text)) target.putExtra(Intent.EXTRA_TEXT, cleaned);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "share sheet", t);
        }
        return target;
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && Settings.SANITIZE_SHARING_LINKS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "switch read", t);
            return false;
        }
    }

    /** [text] with every Instagram link in it cleaned. The rest of the text stays as it was. */
    static String cleanText(String text) {
        if (text.indexOf("://") < 0) return text;
        Matcher links = WEB_LINK.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (links.find()) {
            String link = links.group();
            String cleaned = clean(link);
            if (cleaned.equals(link)) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, links.start()).append(cleaned);
            last = links.end();
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    /**
     * {@code url} without the tracking keys, or {@code url} itself when it has none, isn't an
     * Instagram web link, or can't be read. Never throws.
     */
    public static String clean(String url) {
        if (url == null) return null;
        try {
            return cleaned(url);
        } catch (Throwable t) {
            // A link this can't take apart goes out as it came.
            return url;
        }
    }

    private static String cleaned(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0) return url;
        String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return url;

        // The fragment starts at the first '#', and a '?' after it is part of the fragment.
        int fragment = url.indexOf('#');
        int end = fragment < 0 ? url.length() : fragment;
        int query = url.indexOf('?');
        if (query < 0 || query > end) return url;
        if (!isInstagramHost(host(url, colon + 1, query))) return url;

        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String pair : url.substring(query + 1, end).split("&", -1)) {
            if (TRACKING.contains(keyOf(pair))) {
                removed = true;
            } else {
                kept.add(pair);
            }
        }
        if (!removed) return url;

        String rest = String.join("&", kept);
        return url.substring(0, rest.isEmpty() ? query : query + 1) + rest + url.substring(end);
    }

    /** The key of a {@code key=value} pair, decoded and lower-cased, or as written when it doesn't decode. */
    private static String keyOf(String pair) {
        int equals = pair.indexOf('=');
        String key = equals < 0 ? pair : pair.substring(0, equals);
        if (key.indexOf('%') >= 0) {
            try {
                key = URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
            } catch (Exception malformed) {
                // Kept as written.
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * The host between {@code //} and the path, the query or the port, lower-cased, or
     * {@code null} when the link has no authority.
     */
    private static String host(String url, int from, int query) {
        if (!url.startsWith("//", from)) return null;
        int start = from + 2;
        int stop = query;
        int slash = url.indexOf('/', start);
        if (slash >= 0 && slash < stop) stop = slash;
        String authority = url.substring(start, stop);
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host : host.substring(0, close + 1);
        } else {
            int port = host.indexOf(':');
            if (port >= 0) host = host.substring(0, port);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean isInstagramHost(String host) {
        if (host == null) return false;
        for (String domain : INSTAGRAM_HOSTS) {
            // The dot keeps "notinstagram.com" from matching "instagram.com".
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static Set<String> keys(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
