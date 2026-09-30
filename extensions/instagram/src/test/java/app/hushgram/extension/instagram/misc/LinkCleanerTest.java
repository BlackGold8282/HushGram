/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowActivity;

import app.hushgram.extension.shared.SettingsContextRule;

/**
 * What a shared Instagram link loses. The first two links are what Copy link and the share
 * sheet gave on Instagram 449 on a phone, 2026-09-29: a per-share stkn and no igsh.
 */
@RunWith(RobolectricTestRunner.class)
public class LinkCleanerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void copyLinkOn449LosesItsShareToken() {
        assertEquals("https://www.instagram.com/p/DdbkeGLCNhO/",
                LinkCleaner.clean("https://www.instagram.com/p/DdbkeGLCNhO/?stkn=MWJkaHlkendyazhjbQ=="));
    }

    @Test
    public void shareSheetOn449LosesItsShareToken() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://www.instagram.com/p/DdzLinuo3pE/?stkn=NzlxbDd5dGp2bHl5");
        assertEquals("https://www.instagram.com/p/DdzLinuo3pE/",
                LinkCleaner.sanitizedShare(share).getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void clipboardCopyLosesItsShareToken() {
        ClipData clip = ClipData.newPlainText("link", "https://www.instagram.com/reel/C1/?stkn=abc&igsh=xyz");
        assertEquals("https://www.instagram.com/reel/C1/",
                LinkCleaner.sanitizedClip(clip).getItemAt(0).getText().toString());
    }

    /** WhatsApp's button in Instagram's share sheet: an ACTION_SEND for one package, no chooser. */
    @Test
    public void aShareSentStraightToOneAppLosesItsTrackingKeys() {
        Application app = RuntimeEnvironment.getApplication();
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, "https://www.instagram.com/p/C1/?stkn=abc&igsh=xyz");
        LinkCleaner.startActivity(app, share);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals("com.whatsapp", started.getPackage());
        assertEquals("https://www.instagram.com/p/C1/", started.getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void aShareStartedWithOptionsKeepsThemAndLosesItsTrackingKeys() {
        Application app = RuntimeEnvironment.getApplication();
        Bundle options = new Bundle();
        options.putString("marker", "kept");
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, "Look https://instagram.com/someone?igsh=xyz");
        LinkCleaner.startActivity(app, share, options);
        ShadowActivity.IntentForResult started = shadowOf(app).getNextStartedActivityForResult();
        assertEquals("Look https://instagram.com/someone", started.intent.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals("kept", started.options.getString("marker"));
    }

    /** Only a share changes. Anything else Instagram starts keeps its text, links and all. */
    @Test
    public void anythingElseStartedGoesAsItCame() {
        Application app = RuntimeEnvironment.getApplication();
        String link = "https://www.instagram.com/p/C1/?igsh=xyz";
        Intent view = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, link);
        LinkCleaner.startActivity(app, view);
        assertEquals(link, shadowOf(app).getNextStartedActivity().getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void aProfileLinkLosesItsTrackingKeys() {
        assertEquals("https://www.instagram.com/someone", LinkCleaner.clean("https://www.instagram.com/someone?igsh=abc&utm_source=qr"));
    }

    @Test
    public void theOlderKeysGoAndEverythingElseStaysInOrder() {
        assertEquals("https://instagram.com/stories/someone/123?hl=en&x=1#top",
                LinkCleaner.clean("https://instagram.com/stories/someone/123?igsh=a&hl=en&utm_source=ig_story_item_share"
                        + "&x=1&igshid=b&fbclid=c#top"));
    }

    @Test
    public void everyInstagramHostIsCleaned() {
        assertEquals("https://instagr.am/p/C1/", LinkCleaner.clean("https://instagr.am/p/C1/?stkn=abc"));
        assertEquals("https://ig.me/m/someone", LinkCleaner.clean("https://ig.me/m/someone?igsh=abc"));
    }

    @Test
    public void otherSitesAreLeftAlone() {
        String link = "https://example.com/p/C1/?stkn=abc&igsh=xyz";
        assertEquals(link, LinkCleaner.clean(link));
    }

    @Test
    public void aCleanLinkComesBackAsTheSameString() {
        String link = "https://www.instagram.com/p/C1/?hl=en";
        assertSame(link, LinkCleaner.clean(link));
    }

    @Test
    public void linksInsideSharedTextAreCleanedAndTheTextKept() {
        assertEquals("Look at this https://www.instagram.com/p/C1/ and that.",
                LinkCleaner.cleanText("Look at this https://www.instagram.com/p/C1/?stkn=abc and that."));
    }
}
