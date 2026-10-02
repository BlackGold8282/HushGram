/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The time label of the "Keep a seek bar on Reels" patch: "0:10 / 0:55" above the end of the
 * reel's seek bar.
 *
 * <p>Instagram's reel seek bar keeps the reel's position and length in milliseconds as its
 * progress and max, and every change of either reaches its onProgressChanged, where
 * {@link #update} runs. The label is a TextView made once for each bar and kept with it:
 * <ul>
 *   <li>When the bar's parent is a FrameLayout (Instagram's attached scrubber container), the
 *       label is a child of that parent. TalkBack reads it, and it takes no touches, so a drag
 *       that starts on it still reaches the bar.</li>
 *   <li>Any other parent, such as the Litho host of the newer scrubber, lays out only what it put
 *       there itself, so the label goes on that parent's overlay, which draws over the parent
 *       without joining its children and never takes a touch. TalkBack can't reach an overlay,
 *       so on Android 11 and up the bar itself carries the label's words as its state.</li>
 * </ul>
 *
 * <p>The label follows the bar: hidden while the bar is hidden, faded with it, and hidden while
 * you drag it, when Instagram shows its own times. It's hidden when the bar has no length or no
 * parent, and its text and width start over when the bar is bound to another reel, which shows
 * as a new length or a new reel tag on the bar's container.
 *
 * <p>Runs on the main thread, where a SeekBar calls its listener.
 */
final class ReelTimeLabel {
    /** The start of the tag Instagram 449 puts on a reel's scrubber container, then the reel's id. */
    static final String REEL_TAG_PREFIX = "clips_scrubber_";

    /** The label's text size, the size of Instagram's own scrubber times. */
    static final float TEXT_SP = 12f;

    /** How many views up from the bar the reel's tag is looked for. */
    private static final int TAG_REACH = 8;

    /** The space between the label and the bar's track, and around the label's text. */
    private static final float GAP_DP = 4f;

    /** Each bar's label. Both are weak: the label's parent leads back to the bar. */
    private static final Map<SeekBar, WeakReference<TextView>> LABELS = new WeakHashMap<>();

    /** What a label knows, kept as its tag. Nothing here leads back to the bar. */
    static final class State {
        /** The reel tag and the length the label was laid out for; a change of either starts it over. */
        String reel;
        int max = -1;
        /** The label's width, enough for the longest time this length can show. */
        int width;
        /** The text on the label now, or null after it starts over. */
        String text;
        /** The parent whose overlay holds the label, or null when it's a child. */
        WeakReference<ViewGroup> overlay;
        /** Whether the bar carries the label's words as its state description. */
        boolean described;
    }

    private ReelTimeLabel() {
    }

    /**
     * Shows [bar]'s position and length in its label while the switch is on, and hides the label
     * otherwise, or when the bar has no length or no parent. Never throws.
     */
    static void update(SeekBar bar, int progress, BooleanSupplier on) {
        if (bar == null) return;
        TextView label = null;
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            label = labelOf(bar);
            int max = bar.getMax();
            ViewParent parent = bar.getParent();
            if (!on.getAsBoolean() || max <= 0 || !(parent instanceof ViewGroup)) {
                if (label != null) hide(bar, label);
                return;
            }
            if (label == null) label = create(bar);
            show(bar, (ViewGroup) parent, label, progress, max);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar time", failure);
            hideAfterFailure(label);
        }
    }

    /** The bar's label, or null before its first. */
    static TextView labelOf(SeekBar bar) {
        synchronized (LABELS) {
            WeakReference<TextView> held = LABELS.get(bar);
            return held == null ? null : held.get();
        }
    }

    /** "m:ss", or "h:mm:ss" from an hour, like Instagram's own scrubber times, in the phone's digits. */
    static String time(long milliseconds) {
        long seconds = Math.max(0L, milliseconds) / 1000L;
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        Locale locale = Locale.getDefault();
        return hours > 0
                ? String.format(locale, "%d:%02d:%02d", hours, minutes, seconds % 60L)
                : String.format(locale, "%d:%02d", minutes, seconds % 60L);
    }

    /** The label's text: the time played, past the end counted as the end, and the length. */
    static String text(int progress, int max) {
        return time(played(progress, max)) + " / " + time(max);
    }

    private static int played(int progress, int max) {
        return Math.max(0, Math.min(progress, max));
    }

    private static TextView create(SeekBar bar) {
        Context context = bar.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(GAP_DP * density);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.WHITE);
        // Over the video, the shadow keeps white text readable on a bright frame.
        label.setShadowLayer(2f * density, 0f, 0.5f * density, 0x99000000);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        label.setPadding(gap, gap / 2, gap, gap / 2);
        label.setClickable(false);
        label.setLongClickable(false);
        label.setFocusable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        label.setVisibility(View.GONE);
        label.setTag(new State());
        synchronized (LABELS) {
            LABELS.put(bar, new WeakReference<>(label));
        }
        return label;
    }

    private static void show(SeekBar bar, ViewGroup parent, TextView label, int progress, int max) {
        State state = (State) label.getTag();
        String reel = reelOf(bar);
        if (max != state.max || !Objects.equals(reel, state.reel)) {
            state.max = max;
            state.reel = reel;
            state.text = null;
            state.width = widthFor(label, max);
        }
        attach(parent, label, state);
        if (state.overlay == null && state.described) undescribe(bar, state);
        String text = text(progress, max);
        if (!text.equals(state.text)) {
            state.text = text;
            String words = L10n.f("%1$s of %2$s", time(played(progress, max)), time(max));
            label.setText(text);
            label.setContentDescription(words);
            if (state.overlay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bar.setStateDescription(words);
                state.described = true;
            }
        }
        boolean laidOut = bar.getWidth() > 0 && bar.getHeight() > 0;
        if (laidOut) place(bar, parent, label, state);
        label.setVisibility(laidOut && bar.getVisibility() == View.VISIBLE && !bar.isPressed() ? View.VISIBLE : View.GONE);
        label.setAlpha(bar.getAlpha());
    }

    /** Puts the label in [parent], as a child of a FrameLayout or on any other parent's overlay. */
    private static void attach(ViewGroup parent, TextView label, State state) {
        if (parent instanceof FrameLayout) {
            if (label.getParent() == parent && state.overlay == null) return;
            detach(label, state);
            parent.addView(label, new FrameLayout.LayoutParams(
                    state.width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
            return;
        }
        ViewGroup host = state.overlay == null ? null : state.overlay.get();
        if (host == parent && label.getParent() != null) return;
        detach(label, state);
        // A width of its own, so a new text redraws the label without asking for a new layout.
        label.setLayoutParams(new ViewGroup.LayoutParams(state.width, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.getOverlay().add(label);
        state.overlay = new WeakReference<>(parent);
    }

    private static void detach(TextView label, State state) {
        ViewGroup host = state.overlay == null ? null : state.overlay.get();
        if (host != null) host.getOverlay().remove(label);
        state.overlay = null;
        ViewParent current = label.getParent();
        if (current instanceof ViewGroup) ((ViewGroup) current).removeView(label);
    }

    /**
     * Above the end of the bar's track: the right end, or the left in a right-to-left layout,
     * where the bar fills from the right. Layout parameters change only when the place does, so
     * playback doesn't ask the reel for a new layout on every tick.
     */
    private static void place(SeekBar bar, ViewGroup parent, TextView label, State state) {
        int gap = Math.round(GAP_DP * bar.getResources().getDisplayMetrics().density);
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (state.overlay == null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) label.getLayoutParams();
            int bottom = (parent.getHeight() - parent.getPaddingBottom() - bar.getBottom())
                    + bar.getHeight() - bar.getPaddingTop() + gap;
            int end = rtl
                    ? bar.getLeft() + bar.getPaddingLeft() - parent.getPaddingLeft()
                    : parent.getWidth() - parent.getPaddingRight() - bar.getRight() + bar.getPaddingRight();
            end = Math.max(0, end);
            bottom = Math.max(0, bottom);
            if (params.width != state.width || params.bottomMargin != bottom || params.getMarginEnd() != end
                    || params.gravity != (Gravity.BOTTOM | Gravity.END)) {
                params.width = state.width;
                params.bottomMargin = bottom;
                params.setMarginEnd(end);
                params.gravity = Gravity.BOTTOM | Gravity.END;
                label.setLayoutParams(params);
            }
            return;
        }
        ViewGroup.LayoutParams params = label.getLayoutParams();
        if (params != null && params.width != state.width) {
            params.width = state.width;
            label.setLayoutParams(params);
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(state.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = label.getMeasuredHeight();
        int left = rtl ? bar.getLeft() + bar.getPaddingLeft() : bar.getRight() - bar.getPaddingRight() - state.width;
        int top = Math.max(0, bar.getTop() + bar.getPaddingTop() - gap - height);
        label.layout(left, top, left + state.width, top + height);
    }

    /**
     * Wide enough for any time up to [max] in the label's own paint, so a font scale or the
     * phone's digits never cut it off: the longest text, measured as it is and with every digit
     * as each of 0 to 9, whichever is widest.
     */
    private static int widthFor(TextView label, int max) {
        String longest = text(max, max);
        Paint paint = label.getPaint();
        float width = paint.measureText(longest);
        for (char digit = '0'; digit <= '9'; digit++) {
            width = Math.max(width, paint.measureText(withDigits(longest, digit)));
        }
        return (int) Math.ceil(width) + label.getPaddingLeft() + label.getPaddingRight() + 1;
    }

    private static String withDigits(String text, char digit) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isDigit(c) ? digit : c);
        }
        return out.toString();
    }

    /** The tag of the bar's reel container, "clips_scrubber_" and the reel's id, or null. */
    static String reelOf(View bar) {
        View view = bar;
        for (int i = 0; i < TAG_REACH && view != null; i++) {
            Object tag = view.getTag();
            if (tag instanceof String && ((String) tag).startsWith(REEL_TAG_PREFIX)) return (String) tag;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void hide(SeekBar bar, TextView label) {
        State state = (State) label.getTag();
        state.text = null;
        label.setVisibility(View.GONE);
        if (state.described) undescribe(bar, state);
    }

    /** Gives the bar back the state Android describes it with, which the label's words stood in for. */
    private static void undescribe(SeekBar bar, State state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bar.setStateDescription(null);
        state.described = false;
    }

    /** After a failure the label goes, so it can't stay up with a wrong time. Logged if even that fails. */
    private static void hideAfterFailure(TextView label) {
        if (label == null) return;
        try {
            label.setVisibility(View.GONE);
        } catch (RuntimeException alsoFailed) {
            Logger.printException(() -> "Reel seek bar: the label couldn't be hidden", alsoFailed);
        }
    }
}
