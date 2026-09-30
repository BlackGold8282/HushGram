/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.ClipData;
import android.content.Intent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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
