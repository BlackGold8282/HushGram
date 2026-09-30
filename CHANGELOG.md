# Changelog

Every HushGram release, newest first.

## Unreleased

### HushGram v0.0.2

* Added a HushGram logo and README banner in the family's style, using the pink from HushGram's settings.
* New patch, `Clean up Reels`. The Follow button beside a reel's author goes, and so do the pills that push Edits, templates, Meta AI and Ray-Ban Meta glasses, and friends' activity with the comment preview. Each of the three has its own switch under Reels, and they're all on once the patch is in.
* New patch, `Don't send reel watch history`. Instagram isn't told which reels you watched or how far into them you got. It only uses that to rank your Reels, so reels you've already seen may come back.
* New patch, `Hide Reels in the feed`. The rows of suggested reels between posts in your home feed are gone, along with the other units that open the Reels viewer from there. A reel from someone you follow still shows as a post.
* New patch, `Stop Story auto-advance`. A story stays on screen until you tap or swipe, however long it runs. Turn its switch off for Instagram's timing.

### HushGram v0.0.1

The first set of patches, checked against Instagram 449.0.0.52.84 (build 385511871, arm64-v8a).

* New patch, `Hide ads`. Sponsored posts, reels and stories don't go in, and Instagram is told the ad didn't go in, so it doesn't leave a gap.
* New patch, `Sanitize sharing links`. Copy link, the Android share sheet and the app buttons in Instagram's own share sheet (WhatsApp, SMS and the rest, which skip Android's) lose `stkn` (the per-share id Instagram 449 adds to every shared link), `igsh`, `igshid`, `utm_source` and Instagram's other tracking keys, and so do the post and story links Instagram's server hands out for sharing. Keys come off Instagram's own links only. A link in someone's bio opens its page directly instead of going through `l.instagram.com`, Instagram's click tracker.
* New patch, `Disable analytics`. Instagram's usage events go to your phone's loopback address, which refuses them, instead of to Instagram's and Facebook's logging servers.
* New patch, `Remove build expired popup`. A patched Instagram doesn't update itself, and without this Instagram locks it out after a few weeks with a screen that says the version is too old.
* `Restore trust on re-signed builds`, from Hushfacebook. Instagram's own signature checks see Instagram's two original certificates on a build you signed yourself.
* `HushGram settings`, from Hushfacebook: a switch for each feature, Pause, automatic safe mode after three quick crashes in a row, Debug logging and a diagnostic report that leaves out links, IDs, cookies and sign-in tokens. It opens from a shortcut on Instagram's launcher icon or from a row at the top of Instagram's own Settings and activity screen, and the shortcut works before you sign in too: when Instagram's sign-in screen comes up over it, the settings move in front.
* Runs on Android 9 and newer, which is Instagram 449's own floor. The settings screen, the launcher shortcut, safe mode and the report export all have Android 9 and 10 paths, since Hushfacebook's code expected Android 11.
* German, Spanish, Indonesian, Brazilian Portuguese and Turkish translations for everything HushGram shows.
* Checked beside a patched Threads signed with the same key: neither declares anything the other does, and both open and run side by side.
* Every patched build is checked before it ships. Its resource table has to match the original's, each hook may only use registers that were free where it went in, and each call a patch adds has to be where the patch put it, once.
