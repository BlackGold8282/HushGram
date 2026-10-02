/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the seek bar hooks answer Instagram, and the time label they keep beside the reel's bar:
 * its text, where it sits, that it follows the bar and takes no touches, and that it gives way
 * when the switch is off or anything goes wrong.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class ReelSeekBarTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 400;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SEEK_BAR.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.REEL_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        RuntimeEnvironment.setFontScale(1f);
    }

    /** On, ordinary reels of a second or more get the bar, never the hidden kind; a shorter server minimum stays. */
    @Test
    public void onTheBarIsKeptOnShortReels() {
        assertEquals(1L, ReelSeekBar.minSeconds(30L));
        assertEquals(1L, ReelSeekBar.minSeconds(1L));
        assertEquals(0L, ReelSeekBar.minSeconds(0L));
        assertEquals(-1L, ReelSeekBar.minSeconds(-1L));
        assertFalse(ReelSeekBar.lazy(1));
        assertFalse(ReelSeekBar.lazy(0));
        assertFalse("the hooks were counted", HookStatus.snapshot().isEmpty());
    }

    /** Off, paused or before the settings are read, Instagram's answers stand and the label is hidden. */
    @Test
    public void offPausedAndUnreadyLeaveInstagramsAnswers() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertNotNull(label);
        assertEquals(View.VISIBLE, label.getVisibility());

        Settings.REEL_SEEK_BAR.save(false);
        assertInstagramsAnswers("off", bar, label);
        Settings.REEL_SEEK_BAR.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagramsAnswers("paused", bar, label);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagramsAnswers("unready", bar, label));

        ReelSeekBar.progress(bar, 11_000);
        assertEquals("back on", View.VISIBLE, label.getVisibility());
        assertEquals("0:11 / 0:55", label.getText().toString());
    }

    private static void assertInstagramsAnswers(String what, SeekBar bar, TextView label) {
        assertEquals(what, 30L, ReelSeekBar.minSeconds(30L));
        assertEquals(what, 0L, ReelSeekBar.minSeconds(0L));
        assertTrue(what, ReelSeekBar.lazy(1));
        assertFalse(what, ReelSeekBar.lazy(0));
        ReelSeekBar.progress(bar, 12_000);
        assertEquals(what, View.GONE, label.getVisibility());
    }

    /** A switch that throws leaves Instagram's answers and hides the label, and says which hook threw. */
    @Test
    public void aThrowingSwitchLeavesInstagramsAnswersAndIsReported() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);

        assertEquals(30L, ReelSeekBar.minSeconds(30L, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertTrue(ReelSeekBar.lazy(1, () -> {
            throw new IllegalStateException("settings went away");
        }));
        ReelTimeLabel.update(bar, 11_000, () -> {
            throw new IllegalStateException("settings went away");
        });

        assertEquals(View.GONE, label.getVisibility());
        String missing = HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString();
        assertTrue(missing, missing.contains("'seek bar length'"));
        assertTrue(missing, missing.contains("'hidden seek bar'"));
        assertTrue(missing, missing.contains("'seek bar time'"));
    }

    /** m:ss below an hour and h:mm:ss from one, as Instagram writes its own scrubber times. */
    @Test
    public void timesReadLikeInstagramsOwn() {
        assertEquals("0:00", ReelTimeLabel.time(0));
        assertEquals("0:00", ReelTimeLabel.time(999));
        assertEquals("0:59", ReelTimeLabel.time(59_999));
        assertEquals("1:00", ReelTimeLabel.time(60_000));
        assertEquals("59:59", ReelTimeLabel.time(3_599_999));
        assertEquals("1:00:00", ReelTimeLabel.time(3_600_000));
        assertEquals("25:01:05", ReelTimeLabel.time(90_065_000));
        assertEquals("0:00", ReelTimeLabel.time(-5_000));

        assertEquals("0:10 / 0:55", ReelTimeLabel.text(10_000, 55_000));
        assertEquals("past the end counts as the end", "0:55 / 0:55", ReelTimeLabel.text(70_000, 55_000));
        assertEquals("before the start counts as the start", "0:00 / 0:55", ReelTimeLabel.text(-1, 55_000));
    }

    /** A bar with no length, a live video or one still loading, gets no label, and one it had goes. */
    @Test
    public void aBarWithNoLengthHasNoLabel() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 0);

        ReelSeekBar.progress(bar, 0);
        assertNull(ReelTimeLabel.labelOf(bar));
        assertEquals(1, container.getChildCount());

        bar.setMax(55_000);
        ReelSeekBar.progress(bar, 1_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.VISIBLE, label.getVisibility());

        bar.setMax(0);
        ReelSeekBar.progress(bar, 0);
        assertEquals(View.GONE, label.getVisibility());
    }

    /**
     * In Instagram's FrameLayout container the label is a child of its own, above the end of the
     * bar's track. TalkBack reads it, and it takes no touch, so a drag starting on it reaches the bar.
     */
    @Test
    public void theLabelSitsAboveTheEndOfTheTrackAndTakesNoTouches() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);

        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        ReelSeekBar.progress(bar, 10_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertSame(container, label.getParent());
        assertEquals(2, container.getChildCount());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertEquals("0:10 of 0:55", label.getContentDescription().toString());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, label.getImportantForAccessibility());
        assertFalse(label.isClickable());
        assertFalse(label.isLongClickable());
        assertFalse(label.isFocusable());
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, label.getWidth() / 2f, label.getHeight() / 2f, 0);
        assertFalse("the label took a touch", label.dispatchTouchEvent(down));
        down.recycle();

        assertEquals("the track's end", bar.getRight() - bar.getPaddingRight(), label.getRight());
        assertEquals("above the track", bar.getTop() + bar.getPaddingTop() - gap(bar.getContext()), label.getBottom());
        assertFits(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertNull(bar.getStateDescription());
    }

    /** One label for each bar, however often it's bound to another reel, and its text and width start over. */
    @Test
    public void oneLabelPerBarAcrossRebinds() {
        FrameLayout container = container();
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "1");
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        int shortWidth = label.getLayoutParams().width;

        // Bound to a reel over an hour long: the same label, wider, with the new reel's time.
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "2");
        bar.setMax(3_725_000);
        ReelSeekBar.progress(bar, 0);
        layout(container);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertEquals(2, container.getChildCount());
        assertEquals("0:00 / 1:02:05", label.getText().toString());
        assertTrue("the label grew for the longer time", label.getLayoutParams().width > shortWidth);
        assertFits(label);

        // Another reel of the same length: a new tag alone starts the text over.
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "3");
        ReelSeekBar.progress(bar, 0);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertEquals("0:00 / 1:02:05", label.getText().toString());
        assertEquals(ReelTimeLabel.REEL_TAG_PREFIX + "3", ReelTimeLabel.reelOf(bar));

        // The bar moved to another container: the label goes with it, still one.
        FrameLayout other = container();
        container.removeView(bar);
        other.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        layout(other);
        ReelSeekBar.progress(bar, 5_000);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertSame(other, label.getParent());
        assertEquals("the old container keeps nothing of the bar's", 0, container.getChildCount());
        assertEquals(2, other.getChildCount());

        // Another bar gets a label of its own.
        SeekBar second = bar(container(), 30_000);
        ReelSeekBar.progress(second, 0);
        assertNotSame(label, ReelTimeLabel.labelOf(second));
    }

    /** Right to left the bar fills from the right, so its end and the label are at the left. */
    @Test
    @Config(qualifiers = "ar")
    public void rightToLeftPutsTheLabelAtTheLeftEnd() {
        FrameLayout container = container();
        container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        ReelSeekBar.progress(bar, 10_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.LAYOUT_DIRECTION_RTL, label.getLayoutDirection());
        assertEquals("the track's end", bar.getLeft() + bar.getPaddingLeft(), label.getLeft());
        assertEquals(ReelTimeLabel.text(10_000, 55_000), label.getText().toString());
        assertFits(label);
    }

    /** At twice the text size the longest time still fits on one line, nothing cut off. */
    @Test
    public void twiceTheTextSizeStillFits() {
        RuntimeEnvironment.setFontScale(2f);
        FrameLayout container = container();
        SeekBar bar = bar(container, 36_000_000 - 1_000);
        ReelSeekBar.progress(bar, 35_999_000);
        layout(container);
        ReelSeekBar.progress(bar, 35_999_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        // Android 14 and up scale large text less than linearly, so it's "larger", not exactly twice.
        float scaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ReelTimeLabel.TEXT_SP,
                label.getResources().getDisplayMetrics());
        assertTrue("the text didn't grow", scaled > ReelTimeLabel.TEXT_SP * label.getResources().getDisplayMetrics().density);
        assertEquals(scaled, label.getTextSize(), 0.01f);
        assertEquals("9:59:59 / 9:59:59", label.getText().toString());
        assertFits(label);
    }

    /** A bar not in a view yet gets no label and nothing throws; once it's put in one, the label comes. */
    @Test
    public void aBarWithNoParentGetsNoLabelAndNothingThrows() {
        SeekBar bar = new SeekBar(context());
        bar.setMax(55_000);

        ReelSeekBar.progress(bar, 1_000);
        ReelSeekBar.progress(null, 1_000);
        assertNull(ReelTimeLabel.labelOf(bar));
        assertTrue(HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString(), HookStatus.missing(FamilyNames.REEL_SEEK_BAR).isEmpty());

        FrameLayout container = container();
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        layout(container);
        ReelSeekBar.progress(bar, 2_000);
        assertNotNull(ReelTimeLabel.labelOf(bar));
        assertEquals(View.VISIBLE, ReelTimeLabel.labelOf(bar).getVisibility());
    }

    /** The label hides while the bar is dragged, when Instagram shows its own times, or hidden, and fades with it. */
    @Test
    public void theLabelFollowsTheBar() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);

        bar.setPressed(true);
        ReelSeekBar.progress(bar, 20_000);
        assertEquals("dragged", View.GONE, label.getVisibility());
        bar.setPressed(false);

        bar.setVisibility(View.INVISIBLE);
        ReelSeekBar.progress(bar, 21_000);
        assertEquals("hidden", View.GONE, label.getVisibility());
        bar.setVisibility(View.VISIBLE);

        bar.setAlpha(0.4f);
        ReelSeekBar.progress(bar, 22_000);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals(0.4f, label.getAlpha(), 0.001f);
        assertEquals("0:22 / 0:55", label.getText().toString());
    }

    /**
     * Any other parent, such as the Litho host of the newer scrubber, gets the label on its
     * overlay: not one of its children, above the end of the track, and on Android 11 and up the
     * bar carries its words, which go when the switch does.
     */
    @Test
    public void anotherParentGetsTheLabelOnItsOverlay() {
        LinearLayout host = new LinearLayout(context());
        host.setOrientation(LinearLayout.VERTICAL);
        host.addView(new View(context()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 20, 40, 0);
        bar.setMax(55_000);
        host.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout(host);

        ReelSeekBar.progress(bar, 10_000);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals("the host's children", 2, host.getChildCount());
        assertNotNull(label.getParent());
        assertNotSame(host, label.getParent());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertEquals("the track's end", bar.getRight() - bar.getPaddingRight(), label.getRight());
        assertEquals("above the track", bar.getTop() + bar.getPaddingTop() - gap(context()), label.getBottom());
        assertFits(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("0:10 of 0:55", String.valueOf(bar.getStateDescription()));
        }

        Settings.REEL_SEEK_BAR.save(false);
        ReelSeekBar.progress(bar, 11_000);
        assertEquals(View.GONE, label.getVisibility());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertNull(bar.getStateDescription());
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static int gap(Context context) {
        return Math.round(4f * context.getResources().getDisplayMetrics().density);
    }

    /** Instagram's attached scrubber container: a FrameLayout with the bar at its bottom. */
    private static FrameLayout container() {
        return new FrameLayout(context());
    }

    private static SeekBar bar(FrameLayout container, int max) {
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 20, 40, 0);
        bar.setMax(max);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        layout(container);
        return bar;
    }

    private static void layout(ViewGroup root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
    }

    /** One line, nothing cut off: the text's widest line fits inside the label's padding. */
    private static void assertFits(TextView label) {
        Layout layout = label.getLayout();
        assertNotNull("the label has no layout", layout);
        assertEquals(1, layout.getLineCount());
        assertEquals(0, layout.getEllipsisCount(0));
        float room = label.getWidth() - label.getTotalPaddingLeft() - label.getTotalPaddingRight();
        assertTrue("the text needs " + layout.getLineMax(0) + " px, the label has " + room, layout.getLineMax(0) <= room);
        assertTrue("the label has no height", label.getHeight() > 0);
    }
}
