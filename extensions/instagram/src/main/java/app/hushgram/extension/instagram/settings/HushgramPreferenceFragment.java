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
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
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

    /** The first row, which says whether HushGram runs now and whether the next start changes that. */
    @Nullable
    private Preference statusCard;

    /** The page's dialogs that may still be on screen, which would outlive it. */
    private final List<Dialog> shownDialogs = new ArrayList<>();

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
        if (build.contains(PatchFamily.DISABLE_ANALYTICS)) {
            privacy.add(toggle(context, Settings.DISABLE_ANALYTICS, L10n.t("Disable analytics"),
                    L10n.t("Instagram's usage events go to an address on this phone that refuses them, instead of "
                            + "to Instagram and Facebook. Restart Instagram after changing it.")));
        }
        if (!privacy.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Ads and privacy"));
            for (Preference row : privacy) section.addPreference(row);
        }

        List<Preference> reels = new ArrayList<>();
        if (build.contains(PatchFamily.FEED_REELS)) {
            reels.add(toggle(context, Settings.HIDE_FEED_REELS, L10n.t("Hide Reels in the feed"),
                    L10n.t("The rows of suggested reels between posts in your home feed. A reel someone you "
                            + "follow posts stays.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.add(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY, L10n.t("Don't send reel watch history"),
                    L10n.t("Instagram isn't told which reels you watched or how far into them you got. It ranks "
                            + "your Reels with that, and nobody else sees it. Reels you've watched may come back.")));
        }
        if (!reels.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Reels"));
            for (Preference row : reels) section.addPreference(row);
        }

        if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
            PreferenceCategory stories = category(screen, L10n.t("Stories"));
            stories.addPreference(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE, L10n.t("Stop Story auto-advance"),
                    L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Instagram's timing.")));
        }

        if (build.contains(PatchFamily.BUILD_EXPIRED_POPUP)) {
            PreferenceCategory updates = category(screen, L10n.t("Updates"));
            updates.addPreference(toggle(context, Settings.REMOVE_BUILD_EXPIRED_POPUP,
                    L10n.t("Remove build expired popup"),
                    L10n.t("Instagram stops showing the screen that says this version is too old. A patched build "
                            + "doesn't update on its own, so this keeps it usable.")));
        }

        if (build.contains(PatchFamily.RESTORE_TRUST)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"));
            patched.addPreference(mark(info(context, L10n.t("Re-signed build fix"),
                    L10n.t("Instagram's own signature checks see its original certificates, so they keep "
                            + "passing on this re-signed build.")), SettingsIcons.BUILD));
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
