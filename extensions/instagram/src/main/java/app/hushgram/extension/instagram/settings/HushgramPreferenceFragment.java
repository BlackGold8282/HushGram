/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.text.Layout;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveControl;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.shared.settings.preference.ClearLogBufferPreference;
import app.hushgram.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.hushgram.extension.shared.settings.preference.ImmediateAction;

/**
 * The preference list, built in code rather than from an XML resource so the bundle adds no
 * resources to Instagram. A switch appears only when its patch is in this build
 * ({@link PatchFamily}); a patch that works entirely at patch time gets a line saying so, and what
 * Pause can't reach is listed under the Pause switch. Switches are keyed by their setting, which is
 * how the shared fragment keeps them in sync with stored values. Every word is read from
 * {@link L10n} in the phone's language; the product names and the address stay as they are.
 */
@SuppressWarnings("deprecation")
public final class HushgramPreferenceFragment extends AbstractPreferenceFragment {
    /** The repository as a link, and as a person reads it. */
    static final String SOURCE_URL = "https://github.com/SysAdminDoc/HushGram";
    static final String SOURCE_ADDRESS = SOURCE_URL.substring(SOURCE_URL.indexOf("://") + 3);
    /** The English of the row listing what Pause can't reach, and its key in {@link L10n}. */
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";
    /** The Before you sign in notice's key, which finds it on the screen. */
    static final String SIGN_IN_NOTICE_KEY = "hushgram_sign_in_notice";

    /** The first row, which says whether HushGram runs now and whether the next start changes that. */
    @Nullable
    private Preference statusCard;

    /** The page's dialogs that may still be on screen, which would outlive it. */
    private final List<Dialog> shownDialogs = new ArrayList<>();

    /** The Downloads section, where the running saves are listed, or null when no download patch is in. */
    @Nullable
    private PreferenceCategory downloads;

    /** The rows of the saves running now, by save number. */
    private final Map<Integer, SaveRow> saveRows = new HashMap<>();

    /** Keeps the rows in step with the saves while the page is showing. Saves tell it from their own thread. */
    private final SaveControl.Watcher saves = () -> Utils.runOnMainThread(this::showSaves);

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setBackgroundColor(Color.BLACK);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        SaveControl.watch(saves);
        showSaves();
    }

    @Override
    public void onPause() {
        SaveControl.unwatch(saves);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        for (Dialog dialog : new ArrayList<>(shownDialogs)) dialog.dismiss();
        shownDialogs.clear();
        super.onDestroyView();
    }

    @Override
    protected void initialize() {
        // Loads the switches before the shared fragment syncs them to the screen.
        Settings.HIDE_ADS.get();

        // Every row inflates with the theme of the context it was built with. Instagram's activity
        // theme can be light, and its near-black text would vanish on this screen's black page.
        ScreenColors.shown = null;
        Context context = themed(getContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        screen.addPreference(statusCard(context));
        if (!Settings.SIGN_IN_NOTICE_HIDDEN.savedValue()) screen.addPreference(signInNotice(context, screen));
        // The export row below reads this; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        Set<PatchFamily> build = PatchFamily.inThisBuild();

        List<Preference> privacy = new ArrayList<>();
        if (build.contains(PatchFamily.HIDE_ADS)) {
            privacy.add(toggle(context, Settings.HIDE_ADS, L10n.t("Hide ads"),
                    L10n.t("Sponsored posts, reels and stories. Instagram is told no ad went in, so no gap is left.")));
        }
        if (build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
            privacy.add(toggle(context, Settings.SANITIZE_SHARING_LINKS, L10n.t("Sanitize sharing links"),
                    L10n.t("Takes stkn, igsh, utm_source and other tracking keys off the links you copy or share, "
                            + "and opens a bio link without going through Instagram's click tracker. "
                            + "The post, reel or profile a link opens stays the same.")));
        }
        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            privacy.add(toggle(context, Settings.OPEN_LINKS_EXTERNALLY, L10n.t("Open links in external browser"),
                    L10n.t("Web links open in your default browser, without Instagram's click tracker. "
                            + "Instagram and other Meta pages, and ads, still open in the app.")));
        }
        if (build.contains(PatchFamily.DISABLE_ANALYTICS)) {
            privacy.add(toggle(context, Settings.DISABLE_ANALYTICS, L10n.t("Disable analytics"),
                    L10n.t("Instagram's usage events and crash reports go to an address on this phone that "
                            + "refuses them, instead of to Instagram and Facebook. Restart Instagram after "
                            + "changing it.")));
        }
        if (!privacy.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Ads and privacy"));
            for (Preference row : privacy) section.addPreference(row);
        }

        boolean suggestions = build.contains(PatchFamily.FEED_SUGGESTIONS);
        boolean following = build.contains(PatchFamily.FOLLOWING_FEED);
        PreferenceCategory feed = suggestions || following ? category(screen, L10n.t("Feed")) : null;
        if (following) {
            feed.addPreference(toggle(context, Settings.START_ON_FOLLOWING, L10n.t("Start Home on Following"),
                    L10n.t("Home opens on posts from accounts you follow. Tap Following at the top to switch to For you, "
                            + "and Home remembers your pick. Restart Instagram after changing it.")));
        }
        if (suggestions) {
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_ACCOUNTS, L10n.t("Hide suggested accounts"),
                    L10n.t("The rows of accounts, shops and hashtags Instagram suggests you follow.")));
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS, L10n.t("Hide suggested posts"),
                    L10n.t("Posts and reels from accounts you don't follow, marked Suggested for you. Posts from "
                            + "accounts you follow stay.")));
            feed.addPreference(toggle(context, Settings.HIDE_THREADS_POSTS, L10n.t("Hide Threads posts"),
                    L10n.t("The posts, accounts and communities from Threads that Instagram mixes into your feed.")));
        }

        if (build.contains(PatchFamily.META_AI)) {
            PreferenceCategory metaAi = category(screen, L10n.t("Meta AI"));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_SEARCH, L10n.t("Hide Meta AI in search and Home's bar"),
                    L10n.t("The Search tab and the top of your messages get a plain search bar, without Meta AI, "
                            + "search results lose their Ask a follow-up bar, and Home's top bar loses Meta AI's buttons. "
                            + "Restart Instagram after changing it.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_POSTS, L10n.t("Hide Meta AI posts"),
                    L10n.t("Meta AI's videos, chats and pictures of you that Instagram puts in your home feed.")));
        }

        if (build.contains(PatchFamily.EXPLORE_GRID)) {
            PreferenceCategory explore = category(screen, L10n.t("Explore"));
            explore.addPreference(toggle(context, Settings.HIDE_EXPLORE_GRID, L10n.t("Hide the Explore grid"),
                    L10n.t("The posts and reels under the Search tab's bar. Search, your recent searches and "
                            + "search results stay.")));
        }

        List<Preference> reels = new ArrayList<>();
        if (build.contains(PatchFamily.FEED_REELS)) {
            reels.add(toggle(context, Settings.HIDE_FEED_REELS, L10n.t("Hide Reels in the feed"),
                    L10n.t("The rows of suggested reels between posts in your home feed. A reel someone you "
                            + "follow posts stays.")));
        }
        if (build.contains(PatchFamily.REEL_DECLUTTER)) {
            reels.add(toggle(context, Settings.HIDE_REEL_FOLLOW_BUTTON, L10n.t("Hide the Follow button"),
                    L10n.t("The Follow button beside a reel's author. Their profile still has one.")));
            reels.add(toggle(context, Settings.HIDE_REEL_CHIPS, L10n.t("Hide creation and promotion pills"),
                    L10n.t("Pills such as Edits, Use template, Meta AI and Ray-Ban Meta glasses. A live badge "
                            + "and a state-controlled media label stay.")));
            reels.add(toggle(context, Settings.HIDE_REEL_SOCIAL_FOOTER, L10n.t("Hide friends' activity and comment previews"),
                    L10n.t("The bubbles of friends who liked or commented, the comment shown under a reel and the "
                            + "row of friends who saw it. Comments are still a tap away.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.add(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY, L10n.t("Don't send reel watch history"),
                    L10n.t("Instagram isn't told which reels you watched or how far into them you got. It ranks "
                            + "your Reels with that, and nobody else sees it. Reels you've watched may come back.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            reels.add(toggle(context, Settings.DOWNLOAD_REELS, L10n.t("Download on reels"),
                    L10n.t("Adds Download to every reel's more menu, saved at your download quality. Off or paused, "
                            + "Instagram's own menu returns.")));
        }
        if (build.contains(PatchFamily.DOUBLE_TAP_LIKE)) {
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE, L10n.t("Turn off double tap to like"),
                    L10n.t("A double tap on a post or reel no longer likes it or shows a heart. A single tap and "
                            + "the Like button work as before.")));
        }
        if (build.contains(PatchFamily.REELS_TAB)) {
            reels.add(toggle(context, Settings.HIDE_REELS_TAB, L10n.t("Hide the Reels tab"),
                    L10n.t("Takes Reels off the tab bar. Reels in your feed and reels people send you still "
                            + "open. Restart Instagram after changing it.")));
        }
        if (build.contains(PatchFamily.KEEP_REEL_SPEED)) {
            reels.add(toggle(context, Settings.KEEP_REEL_SPEED, L10n.t("Keep the reel speed"),
                    L10n.t("Lock a reel at 2x (hold its edge, then slide down) and the next reels play at 2x too. "
                            + "Slide the lock off, or hold the edge and let go, to go back to normal speed.")));
        }
        if (!reels.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Reels"));
            for (Preference row : reels) section.addPreference(row);
        }

        List<Preference> stories = new ArrayList<>();
        if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
            stories.add(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE, L10n.t("Stop Story auto-advance"),
                    L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Instagram's timing.")));
        }
        if (build.contains(PatchFamily.STORY_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_STORIES_ANONYMOUSLY, L10n.t("View stories anonymously"),
                    L10n.t("Instagram isn't told which stories you watch, so you stay off their viewer lists. "
                            + "Replying or reacting still shows you, and stories you've watched keep showing as new.")));
        }
        if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
            stories.add(toggle(context, Settings.DOWNLOAD_STORIES, L10n.t("Download on stories"),
                    L10n.t("Adds Download to the menu of anyone's story, photo or video, saved at your download quality. "
                            + "Off or paused, Instagram's own menu returns.")));
        }
        if (!stories.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Stories"));
            for (Preference row : stories) section.addPreference(row);
        }

        if (build.contains(PatchFamily.TAP_TO_PLAY) || build.contains(PatchFamily.RESUME_LONG_VIDEOS)
                || build.contains(PatchFamily.PLAYBACK_QUALITY)) {
            PreferenceCategory playback = category(screen, L10n.t("Playback"));
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY, L10n.t("Tap to play"),
                        L10n.t("Videos, reels and stories wait for your tap. Feed videos show a play button, as they do "
                                + "when you use less mobile data.")));
            }
            if (build.contains(PatchFamily.RESUME_LONG_VIDEOS)) {
                playback.addPreference(toggle(context, Settings.RESUME_LONG_VIDEOS, L10n.t("Resume long videos"),
                        L10n.t("Videos and reels over two minutes pick up where you left off. Seek to start elsewhere. "
                                + "Live videos and ads start as usual.")));
            }
            if (build.contains(PatchFamily.PLAYBACK_QUALITY)) {
                playback.addPreference(toggle(context, Settings.DEFAULT_PLAYBACK_QUALITY, L10n.t("Default playback quality"),
                        L10n.t("Videos, reels and stories play at the quality below, starting with the next one you open.")));
                playback.addPreference(playbackQualityRow(context));
            }
        }

        // Any download patch brings this section, so each one that saves joins this condition.
        if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.STORY_DOWNLOAD)
                || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
            PreferenceCategory downloads = category(screen, L10n.t("Downloads"));
            this.downloads = downloads;
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VIDEOS, L10n.t("Download feed videos"),
                        L10n.t("Adds Download to the menu of a post in your feed with a video. Uses the quality below. "
                                + "Off or paused, Instagram's own menu returns.")));
            }
            // Every save reads it, whichever download patch started it, so it's here above the
            // quality it keeps within.
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_COMPATIBLE, L10n.t("Save videos other apps can open"),
                    L10n.t("For WhatsApp, video editors such as CapCut and InShot, or a gallery or player that plays saves "
                            + "without sound. May lower quality.")));
            downloads.addPreference(qualityRow(context));
            downloads.addPreference(folderRow(context));
            downloads.addPreference(fileNameRow(context));
        }

        if (build.contains(PatchFamily.BUILD_EXPIRED_POPUP)) {
            PreferenceCategory updates = category(screen, L10n.t("Updates"));
            updates.addPreference(toggle(context, Settings.REMOVE_BUILD_EXPIRED_POPUP,
                    L10n.t("Remove build expired popup"),
                    L10n.t("Instagram stops showing the screen that says this version is too old. A patched build "
                            + "doesn't update on its own, so this keeps it usable.")));
        }

        if (build.contains(PatchFamily.RESTORE_TRUST) || build.contains(PatchFamily.REMOVE_AD_ID)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"));
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(mark(info(context, L10n.t("Re-signed build fix"),
                        L10n.t("Instagram's own signature checks see its original certificates, so they keep "
                                + "passing on this re-signed build.")), SettingsIcons.BUILD));
            }
            if (build.contains(PatchFamily.REMOVE_AD_ID)) {
                patched.addPreference(mark(info(context, L10n.t("Advertising ID removed"),
                        L10n.t("Instagram can't read your phone's advertising ID or tell Android's ad services "
                                + "which ads you saw or tapped. The permissions for them are gone from this build.")),
                        SettingsIcons.BLOCK));
            }
            patched.addPreference(info(context, L10n.t("Changing these"),
                    L10n.t("They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them.")));
        }

        // Named for its rows: the screen's own title already says HushGram.
        PreferenceCategory hushgram = category(screen, L10n.t("Pause and diagnostics"));
        hushgram.addPreference(mark(toggle(context, BaseSettings.PAUSED, L10n.t("Pause HushGram"),
                L10n.t("From the next start, every switch but Debug logging acts as if it were off. "
                        + "Changes made when you patched stay in, and your choices stay saved.")), SettingsIcons.PATCHED));
        // Debug logging also fills the exported report and turns on error toasts (Logger).
        hushgram.addPreference(mark(toggle(context, BaseSettings.DEBUG, L10n.t("Debug logging"),
                L10n.t("Record patch activity and show errors for a bug report. Leave off during normal use.")), SettingsIcons.BUG));
        ExportDiagnosticReportPreference export = new ExportRow(context);
        export.setTitle(L10n.t("Export diagnostic report"));
        export.setSummary(L10n.t("Copy a quick report or save the full one to Download/Morphe. Links, IDs, cookies "
                + "and sign-in tokens are left out. Check it for other private text before you share it."));
        hushgram.addPreference(mark(export, SettingsIcons.LICENSE));
        ClearLogBufferPreference clear = new ClearRow(context);
        clear.setTitle(L10n.t("Clear diagnostic data"));
        clear.setClearAndUndoSummaries(L10n.t("Empties the log and the hook counts a report would include."),
                L10n.t("Diagnostic data cleared. Tap again to put it back."));
        hushgram.addPreference(mark(clear, SettingsIcons.DELETE));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        if (stays != null) hushgram.addPreference(info(context, L10n.t(STAYS_WHILE_PAUSED), stays));

        PreferenceCategory about = category(screen, L10n.t("About"));
        about.addPreference(mark(info(context, L10n.t("Version"), L10n.f("HushGram %1$s on Instagram %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()))), SettingsIcons.ABOUT));

        Preference source = new Row(context);
        source.setTitle(L10n.t("Source code and issues"));
        source.setSummary(SOURCE_ADDRESS);
        source.setPersistent(false);
        source.setOnPreferenceClickListener(p -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (ActivityNotFoundException | SecurityException missing) {
                // No browser, or none switched on. Uncaught, Android's exception closed Instagram.
                Logger.printInfo(() -> "No app opened the source code link");
                Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                        L10n.isolate(SOURCE_ADDRESS)));
            }
            return true;
        });
        about.addPreference(mark(source, SettingsIcons.OPENING));

        // Section 7b asks that its notice reach the person using the software, and a file in the
        // repository does not reach them.
        Preference licenses = new Row(context);
        licenses.setTitle(L10n.t("Licenses"));
        licenses.setSummary(L10n.t("GPL-3.0, with the notices of the projects this is built on"));
        licenses.setPersistent(false);
        licenses.setOnPreferenceClickListener(p -> {
            showNotice(context);
            return true;
        });
        about.addPreference(mark(licenses, SettingsIcons.LICENSE));
    }

    /**
     * Lists each save running now at the top of Downloads, and takes a finished one away. The
     * notification was the only way to follow or stop a save, and with Instagram's notifications or
     * the saves channel off there wasn't one. A row that stays is changed in place.
     */
    void showSaves() {
        PreferenceCategory group = downloads;
        if (group == null) return;
        List<SaveControl.Running> running = SaveControl.running();
        Set<Integer> now = new HashSet<>();
        for (SaveControl.Running save : running) now.add(save.id);
        for (java.util.Iterator<Map.Entry<Integer, SaveRow>> rows = saveRows.entrySet().iterator(); rows.hasNext(); ) {
            Map.Entry<Integer, SaveRow> row = rows.next();
            if (now.contains(row.getKey())) continue;
            group.removePreference(row.getValue());
            rows.remove();
        }
        for (SaveControl.Running save : running) {
            SaveRow row = saveRows.get(save.id);
            if (row != null) {
                row.show(save);
                continue;
            }
            row = new SaveRow(group.getContext(), save);
            saveRows.put(save.id, row);
            group.addPreference(row);
        }
    }

    /**
     * The first row: whether HushGram is on, and why not when it isn't. Paused by safe mode or the
     * marker file, a tap turns it back on from the next start.
     */
    private Preference statusCard(Context context) {
        Row card = new Row(context);
        card.setPersistent(false);
        boolean paused = HushgramPause.isPaused();
        ScreenColors palette = ScreenColors.DEFAULT;
        card.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette.summary : palette.heading));
        statusCard = card;
        if (!paused) {
            card.setTitle(L10n.t("HushGram is on"));
            card.setSelectable(false);
            showStatus(card, context);
            return card;
        }
        card.setTitle(L10n.t("HushGram is paused"));
        showStatus(card, context);
        // A tap acts at once, taking the pause off the next start, and the card says so in words.
        card.actsAtOnce = true;
        card.setOnPreferenceClickListener(p -> {
            resumeFromOverview();
            return true;
        });
        return card;
    }

    /**
     * How to sign in with the least risk to the account, under the status card until a tap hides
     * it for good. Most reported suspensions of patched Instagram start at the sign-in, and this
     * screen opens before it.
     */
    private static Preference signInNotice(Context context, PreferenceScreen screen) {
        Row notice = new Row(context);
        notice.setPersistent(false);
        notice.setKey(SIGN_IN_NOTICE_KEY);
        notice.setIcon(SettingsIcons.icon(context, SettingsIcons.ABOUT, ScreenColors.DEFAULT.heading));
        notice.setTitle(L10n.t("Before you sign in"));
        notice.setSummary(L10n.t("Most suspensions reported with patched Instagram start at the sign-in. Use an "
                + "account you've had for a while, finish any phone or ID check Instagram asks for, and don't "
                + "clear Instagram's data afterward. On a rooted phone, a Root Mount install keeps you signed in.")
                + " " + L10n.t("Tap to hide this."));
        notice.actsAtOnce = true;
        notice.setOnPreferenceClickListener(row -> {
            Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
            screen.removePreference(row);
            return true;
        });
        return notice;
    }

    void resumeFromOverview() {
        Context context = getContext();
        if (context == null || statusCard == null) return;
        HushgramPause.Reason still = HushgramPause.turnBackOn(context);
        // The switch shows what was kept. Setting it to off here would write off through the
        // preference itself when the store had just refused to.
        Preference pause = findPreference(BaseSettings.PAUSED.key);
        if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(BaseSettings.PAUSED.savedValue());
        if (still == HushgramPause.Reason.MARKER_FILE) {
            String file = L10n.isolate(HushgramPause.MARKER_FILE_NAME);
            String folder = L10n.isolate(markerFolder(context.getPackageName()));
            String left = L10n.f("The file %1$s couldn't be removed. Delete it from %2$s to turn HushGram back on.",
                    file, folder);
            statusCard.setSummary(left);
            Utils.showToastLong(left);
            return;
        }
        if (still != HushgramPause.Reason.NONE) {
            Utils.showToastLong(L10n.t("Couldn't turn HushGram back on. Try again."));
        }
        showStatus(statusCard, context);
    }

    /**
     * The card's line under its title: this start's state, and the next start's when a change on
     * this screen makes it differ.
     */
    private void showStatus(Preference card, Context context) {
        boolean pausedNext = HushgramPause.pausesNextStart(context);
        String status;
        if (!HushgramPause.isPaused()) {
            String version = L10n.f("Version %1$s for Instagram %2$s",
                    L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()));
            status = pausedNext ? version + " " + L10n.t("HushGram pauses when Instagram restarts.") : version;
        } else if (pausedNext) {
            status = pausedSummary(HushgramPause.reason(), context.getPackageName())
                    + " " + L10n.t("Tap to turn it back on.");
        } else {
            status = L10n.t("HushGram turns back on when Instagram restarts.");
        }
        card.setSummary(status);
    }

    @Override
    protected void onRestartPendingChanged() {
        Preference card = statusCard;
        Context context = getContext();
        if (card != null && context != null) showStatus(card, context);
    }

    /**
     * Why this start runs paused, what a pause does and doesn't reach, and that nothing the reader
     * saved has changed.
     */
    static String pausedSummary(HushgramPause.Reason reason, String packageName) {
        String why;
        switch (reason) {
            case CRASH_LOOP:
                why = L10n.t("Instagram crashed or froze within a minute of starting three times in a row, so "
                        + "HushGram paused itself.");
                break;
            case MARKER_FILE:
                why = L10n.f("A file named %1$s in %2$s paused HushGram.",
                        L10n.isolate(HushgramPause.MARKER_FILE_NAME), L10n.isolate(markerFolder(packageName)));
                break;
            default:
                why = L10n.t("You paused HushGram.");
                break;
        }
        return why + " " + L10n.t("Every switch but Debug logging acts as if it were off, and what was set when you "
                + "patched stays in. Your settings stay as they are.");
    }

    /** Where the marker file goes, the way a file manager shows it. */
    static String markerFolder(String packageName) {
        return "Android/data/" + packageName + "/files";
    }

    /** The dark Material theme every row and dialog on this screen is built with. */
    static Context themed(Context base) {
        return new ContextThemeWrapper(base, ScreenColors.themeFor(base));
    }

    /** The recovery page draws on the same page as the rows, so it gets the same theme. */
    @Override
    protected Context pageContext(Activity activity) {
        return themed(activity);
    }

    @Override
    protected ErrorActionStyler errorActionStyler() {
        return ScreenColors::recoveryAction;
    }

    @Override
    protected void styleInitializationMessage(View row) {
        ScreenColors.recoveryMessage(row);
    }

    private static PreferenceCategory category(PreferenceScreen screen, String title) {
        PreferenceCategory category = new Heading(screen.getContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new Toggle(context);
        preference.setKey(setting.key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    /**
     * The quality a video save asks for. The list's values are the setting's own names, which is
     * what the shared page syncs a list by, and the summary says what the choice does.
     */
    static QualityRow qualityRow(Context context) {
        QualityRow row = new QualityRow(context);
        row.setKey(Settings.DOWNLOAD_QUALITY.key);
        row.setTitle(L10n.t("Download quality"));
        row.setDialogTitle(L10n.t("Download quality"));
        // A list's dialog keeps DialogPreference's Cancel, which Android fills in the activity's
        // language unless it's set here, as the folder and file name rows' are.
        row.setNegativeButtonText(L10n.t("Cancel"));
        DownloadQuality[] qualities = DownloadQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = qualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.DOWNLOAD_QUALITY.savedValue().name());
        return row;
    }

    /** What the list shows for [quality]: a word for the two ends, the label itself between them. */
    static String qualityLabel(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Best");
            case SMALLEST:
                return L10n.t("Smallest");
            default:
                return L10n.isolate(quality.ceilingLabel());
        }
    }

    /**
     * What a save does with [quality], for the row's summary. A cap takes the best rendition at or
     * under it, and only a video with nothing that low goes above it ({@link DownloadQuality}), so
     * the sentence names both directions: "the closest quality" alone read as 1080p beating 240p
     * under a 720p cap, and the save picks 240p.
     */
    static String qualitySummary(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Each video saves at the best quality the player streams.");
            case SMALLEST:
                return L10n.t("Each video saves at its lowest quality, for the smallest file.");
            default:
                return L10n.f("Each video saves at %1$s or the closest quality below it. A video with nothing "
                        + "that low saves at the closest quality above.", L10n.isolate(quality.ceilingLabel()));
        }
    }

    /**
     * The quality videos play at. Like the download quality's row, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static PlaybackQualityRow playbackQualityRow(Context context) {
        PlaybackQualityRow row = new PlaybackQualityRow(context);
        row.setKey(Settings.PLAYBACK_QUALITY.key);
        row.setTitle(L10n.t("Playback quality"));
        row.setDialogTitle(L10n.t("Playback quality"));
        // Android's own Cancel follows the activity's language, as the download quality's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        PlaybackQuality[] qualities = PlaybackQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = playbackQualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.PLAYBACK_QUALITY.savedValue().name());
        return row;
    }

    /** What the list calls [quality]: Auto for Instagram's own choice, and a ceiling by its label. */
    static String playbackQualityLabel(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Data saver");
            case P480:
            case P720:
                return L10n.f("Up to %1$s", L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Highest");
            default:
                return L10n.t("Auto");
        }
    }

    /**
     * What a video does with [quality], for the row's summary. The rungs are the ones Instagram
     * offers for each video, so the summary says the video has to offer the quality rather than
     * promise it.
     */
    static String playbackQualitySummary(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Videos play at the lowest quality Instagram offers for each.");
            case P480:
            case P720:
                return L10n.f("Videos play at the best quality up to %1$s that Instagram offers for each, or the closest above.",
                        L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Videos play at the highest quality Instagram offers for each.");
            default:
                return L10n.t("Instagram picks the quality as each video plays, from your connection.");
        }
    }

    /** The two quality rows' summaries are sentences of their own rather than the chosen entry. */
    @Override
    protected void updateListPreferenceSummary(ListPreference listPreference, Setting<?> setting) {
        if (listPreference instanceof QualityRow) {
            ((QualityRow) listPreference).showSummary();
        } else if (listPreference instanceof PlaybackQualityRow) {
            ((PlaybackQualityRow) listPreference).showSummary();
        } else {
            super.updateListPreferenceSummary(listPreference, setting);
        }
    }

    /**
     * The folder every save goes to. What's typed is cleaned before it's kept, so the row, the
     * setting and the next save all show the one folder name the save will use.
     */
    static FolderRow folderRow(Context context) {
        FolderRow row = new FolderRow(context);
        row.setKey(Settings.SAVE_FOLDER.key);
        row.setTitle(L10n.t("Save folder"));
        row.setDialogTitle(L10n.t("Save folder"));
        row.setDialogMessage(L10n.f("Choose a folder name under Movies and Pictures. Invalid characters become "
                + "underscores. Leave it blank to use the default folder, %1$s.", L10n.isolate(SaveFolder.DEFAULT)));
        row.setPositiveButtonText(L10n.t("Save"));
        // Unset, Android fills in its own Cancel in the activity's language, which can differ
        // from Instagram's, and the dialog read "Speichern" next to "Cancel".
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("Folder name"));
        row.setText(Settings.SAVE_FOLDER.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = SaveFolder.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean name in place of what was typed. The store changes, and the shared
            // page reads the setting from the row as it does for any change.
            ((FolderRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("Folder set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /**
     * The name every saved video gets, next to the folder it goes to. What's typed is cleaned the
     * way the folder is, and held to the gallery's naming, so the row, the setting and the next
     * save all show the one template the save will use.
     */
    static FileNameRow fileNameRow(Context context) {
        FileNameRow row = new FileNameRow(context);
        row.setKey(Settings.FILENAME_TEMPLATE.key);
        row.setTitle(L10n.t("Video file name"));
        row.setDialogTitle(L10n.t("Video file name"));
        row.setDialogMessage(L10n.f("%1$s becomes the date and time of the save, %2$s the video's number on "
                        + "Instagram, %3$s who posted it and %4$s the day it was posted. What a save doesn't know is "
                        + "left out, and a name with none of these gets the date added. When the name is already in "
                        + "the folder, the time of the save goes on the end. Invalid characters become underscores. "
                        + "Leave it blank to use the default, %5$s.",
                L10n.isolate(FileNameTemplate.DATE), L10n.isolate(FileNameTemplate.VIDEO_ID),
                L10n.isolate(FileNameTemplate.OWNER), L10n.isolate(FileNameTemplate.POSTED),
                L10n.isolate(FileNameTemplate.DEFAULT)));
        row.setPositiveButtonText(L10n.t("Save"));
        // Android's own Cancel follows the activity's language, as the folder row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("File name"));
        row.setText(Settings.FILENAME_TEMPLATE.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = FileNameTemplate.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean template in place of what was typed, as the folder row does.
            ((FileNameRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("File name set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /**
     * "Videos are named IG_VID_{date}. Photos are always named IG_IMG_ followed by the date and
     * time." for [template]. The photo prefix is HushGram's, not Instagram's, so it isn't called
     * Instagram's own naming.
     */
    static String fileNameSummary(String template) {
        return L10n.f("Videos are named %1$s. Photos are always named %2$s followed by the date and time.",
                L10n.isolate(template), L10n.isolate(FileNameTemplate.PHOTO_PREFIX));
    }

    /** "Videos go to Movies/Instagram and photos to Pictures/Instagram." for the folder [leaf]. */
    static String folderSummary(String leaf) {
        String videos = Environment.DIRECTORY_MOVIES + "/" + leaf;
        String photos = Environment.DIRECTORY_PICTURES + "/" + leaf;
        return L10n.f("Videos go to %1$s and photos to %2$s.", L10n.isolate(videos), L10n.isolate(photos));
    }

    private static Preference mark(Preference row, String icon) {
        row.setIcon(SettingsIcons.icon(row.getContext(), icon, ScreenColors.DEFAULT.heading));
        return row;
    }

    private static Preference info(Context context, String title, String summary) {
        Preference preference = new Row(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        return preference;
    }

    /**
     * Shows a dialog over this page and remembers it, so it goes with the page. Nothing shows once
     * the view is gone or the activity is finishing.
     */
    private void show(AlertDialog.Builder builder) {
        Activity activity = getActivity();
        if (getView() == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = builder.show();
        ScreenColors.dialog(dialog);
        shownDialogs.add(dialog);
        dialog.setOnDismissListener(shownDialogs::remove);
    }

    /**
     * Lets a row's title and summary wrap to as many lines as they need. Android draws a row's
     * title on one line and cuts its summary at ten.
     */
    static void showAllText(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) summary.setMaxLines(Integer.MAX_VALUE);
    }

    /** A section title, which a screen reader announces as a heading. */
    static final class Heading extends PreferenceCategory {
        Heading(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            ScreenColors.heading(view);
        }
    }

    /** A row of text, which a screen reader calls a button when a tap does something. */
    static final class Row extends Preference implements ImmediateAction {
        /** Set on a row whose tap does what it says at once, which then goes without a chevron. */
        boolean actsAtOnce;

        Row(Context context) {
            super(context);
        }

        @Override
        public boolean actsOnTap() {
            return actsAtOnce;
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(isSelectable() ? new RowSemantics(this, Button.class) : null);
        }
    }

    /** A switch row, which a screen reader calls a switch and reads as on or off. */
    static final class Toggle extends SwitchPreference {
        Toggle(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Switch.class));
        }
    }

    /**
     * A running save: what it is, what it's doing, how far it has got, and a Cancel button. Its text
     * changes in place: a row rebuilt under a finger loses the tap on its button.
     */
    static final class SaveRow extends Preference {
        /** Before every setting of the section, oldest save first. */
        private static final int FIRST = Integer.MIN_VALUE / 2;

        final int id;
        private final boolean video;
        private String status;
        @Nullable
        private View bound;

        SaveRow(Context context, SaveControl.Running save) {
            super(context);
            id = save.id;
            video = save.video;
            status = SaveControl.status(save);
            setKey("running_save_" + save.id);
            setPersistent(false);
            setSelectable(false);
            setOrder(FIRST + save.id);
            setTitle(video ? L10n.t("Saving a video") : L10n.t("Saving a photo"));
        }

        @Override
        public CharSequence getSummary() {
            return status;
        }

        void show(SaveControl.Running save) {
            String next = SaveControl.status(save);
            if (next.equals(status)) return;
            status = next;
            TextView summary = bound == null ? null : bound.findViewById(android.R.id.summary);
            if (summary != null) summary.setText(next);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            bound = view;
            showAllText(view);
            ScreenColors.row(view, this);
            android.view.ViewGroup frame = view.findViewById(android.R.id.widget_frame);
            if (frame == null) return;
            frame.removeAllViews();
            Button cancel = new Button(getContext());
            cancel.setText(L10n.t("Cancel"));
            // Two saves can be listed at once, so the button says whose it is.
            cancel.setContentDescription(video ? L10n.t("Cancel saving this video") : L10n.t("Cancel saving this photo"));
            cancel.setAllCaps(false);
            cancel.setTextSize(14);
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            cancel.setTextColor(colors.heading);
            cancel.setBackgroundColor(Color.TRANSPARENT);
            int touch = Math.round(48 * view.getResources().getDisplayMetrics().density);
            cancel.setMinWidth(touch);
            cancel.setMinimumWidth(touch);
            cancel.setMinHeight(touch);
            cancel.setMinimumHeight(touch);
            cancel.setPadding(touch / 4, 0, touch / 4, 0);
            cancel.setOnClickListener(ignored -> {
                cancel.setEnabled(false);
                SaveControl.cancel(id);
            });
            frame.addView(cancel, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            frame.setVisibility(View.VISIBLE);
        }
    }

    /**
     * An edit dialog shrinks to fit above the keyboard instead of going under it. Android's own
     * EditTextPreference only asks for the keyboard, so at a large font size a long message pushed
     * the dialog's Save and Cancel behind the keyboard, where nobody could reach them. The dialog's
     * keyboard state is kept; only how it adjusts changes.
     */
    static void fitAboveKeyboard(Dialog dialog) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        int mode = window.getAttributes().softInputMode;
        window.setSoftInputMode((mode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /**
     * The save folder's row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class FolderRow extends EditTextPreference {
        FolderRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(folderSummary(SaveFolder.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The video file name's row. Its summary follows its text, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import. Its dialog shows what the typed
     * template names a video, as it's typed.
     */
    static final class FileNameRow extends EditTextPreference {
        @Nullable private TextView preview;
        private java.util.Date previewDate;

        FileNameRow(Context context) {
            super(context);
            getEditText().addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (preview != null) preview.setText(previewName(text.toString(), previewDate));
                }
                @Override public void afterTextChanged(android.text.Editable text) { }
            });
        }

        static String previewName(String text, java.util.Date when) {
            return FileNameTemplate.videoName(FileNameTemplate.sanitize(text), when, "123456") + ".mp4";
        }

        @Override protected View onCreateDialogView() {
            Context context = getContext();
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout content = new android.widget.LinearLayout(context);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            content.setPadding(pad, pad / 2, pad, pad);
            EditText field = getEditText();
            if (field.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) field.getParent()).removeView(field);
            content.addView(field, new android.widget.LinearLayout.LayoutParams(-1, -2));
            TextView label = new TextView(context);
            label.setText(L10n.t("Example without post details"));
            label.setTextSize(12);
            label.setTextColor(colors.summary);
            label.setPadding(0, pad, 0, pad / 4);
            content.addView(label);
            previewDate = new java.util.Date();
            preview = new TextView(context);
            preview.setTextSize(14);
            preview.setTextColor(colors.title);
            preview.setText(previewName(getText(), previewDate));
            content.addView(preview);
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, pad, 0, 0);
            content.addView(help);
            ScrollView scroll = new ScrollView(context);
            scroll.addView(content);
            return scroll;
        }

        @Override protected void onBindDialogView(View view) {
            // The input is already in the custom scroll container, before its explanatory text.
            getEditText().setText(getText());
        }

        @Override protected void onDialogClosed(boolean positive) {
            super.onDialogClosed(positive);
            preview = null;
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(fileNameSummary(FileNameTemplate.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the folder's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The download quality's row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class QualityRow extends ListPreference {
        QualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            DownloadQuality quality = DownloadQuality.BEST;
            for (DownloadQuality candidate : DownloadQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(qualitySummary(quality));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The playback quality's row. Its summary follows its value, whoever sets it: the person or the
     * shared page syncing it from the setting.
     */
    static final class PlaybackQualityRow extends ListPreference {
        PlaybackQualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            PlaybackQuality quality = PlaybackQuality.AUTO;
            for (PlaybackQuality candidate : PlaybackQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(playbackQualitySummary(quality));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class ExportRow extends ExportDiagnosticReportPreference {
        ExportRow(Context context) {
            super(context);
        }

        /** The two choices as cards, each saying what it does under its name. */
        @Override
        protected ListAdapter choices(Context dialogContext) {
            return new ChoiceCards(ScreenColors.DEFAULT,
                    new CharSequence[]{L10n.t(getContext(), "Copy quick report"),
                            L10n.t(getContext(), "Save full report")},
                    new CharSequence[]{L10n.t(getContext(), "Copy a short report to the clipboard."),
                            L10n.t(getContext(), "Save the full report in Download/Morphe.")});
        }

        /** The cards bring their own spacing and ripple, so the list draws no divider of its own. */
        @Override
        protected void onDialogCreated(AlertDialog dialog) {
            ListView list = dialog.getListView();
            if (list != null) {
                list.setDivider(null);
                list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            }
            ScreenColors.dialog(dialog);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    static final class ClearRow extends ClearLogBufferPreference {
        ClearRow(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /**
     * What a screen reader hears about a row a tap acts on. The list gives such a row its place and
     * a click action and no role, and the switch drawn in a switch row can't take focus, so on its
     * own a switch row said neither that it was a switch nor whether it was on.
     */
    static final class RowSemantics extends View.AccessibilityDelegate {
        private final Preference preference;
        private final Class<? extends View> role;

        RowSemantics(Preference preference, Class<? extends View> role) {
            this.preference = preference;
            this.role = role;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            AbsListView list = listOf(host);
            int position = list == null ? AdapterView.INVALID_POSITION : list.getPositionForView(host);
            if (position != AdapterView.INVALID_POSITION) {
                list.onInitializeAccessibilityNodeInfoForItem(host, position, info);
            }
            info.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                info.setCheckable(true);
                info.setChecked(((TwoStatePreference) preference).isChecked());
            }
            if (preference.isEnabled() && !info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                event.setChecked(((TwoStatePreference) preference).isChecked());
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            AbsListView list = listOf(host);
            if (action == AccessibilityNodeInfo.ACTION_CLICK && list != null && preference.isEnabled()) {
                int position = list.getPositionForView(host);
                if (position != AdapterView.INVALID_POSITION) {
                    return list.performItemClick(host, position, list.getItemIdAtPosition(position));
                }
            }
            return super.performAccessibilityAction(host, action, arguments);
        }

        @Nullable
        private static AbsListView listOf(View row) {
            return row.getParent() instanceof AbsListView ? (AbsListView) row.getParent() : null;
        }
    }

    /**
     * The notice itself stays in English, as the licence texts it carries are: a translation of
     * the GPL is not the licence.
     */
    private void showNotice(Context context) {
        TextView text = new TextView(context);
        // NOTICE is hard-wrapped for a source file. Reflow prose on a narrow screen while keeping
        // blank lines, headings, lists and the generated notice itself intact.
        String notice = LicenseNotice.TEXT.replaceAll("(?<=[\\p{L}.,;])\\n(?=\\p{L})", " ")
                .replaceAll("(?m)^(  .+?) {2,}(https?://[^\\n]+)$", "$1\n$2\n");
        text.setText(notice);
        text.setTextIsSelectable(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setLineSpacing(Math.round(2 * context.getResources().getDisplayMetrics().density), 1.04f);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        text.setTextColor(ScreenColors.DEFAULT.summary);
        text.setLinkTextColor(ScreenColors.DEFAULT.heading);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Licenses"))
                .setView(scroll)
                .setPositiveButton(L10n.t("OK"), null));
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return L10n.t(context, "HushGram settings couldn't open");
    }
}
