# Changelog

Every HushGram release, newest first.

## Unreleased

### 0.0.1

The first set of patches, checked against Instagram 449.0.0.52.84 (build 385511871, arm64-v8a).

* New patch, `Hide ads`. Sponsored posts, reels and stories don't go in, and Instagram is told the ad didn't go in, so it doesn't leave a gap.
* New patch, `Sanitize sharing links`. Copy link and the Android share sheet lose `stkn` (the per-share id Instagram 449 adds to every shared link), `igsh`, `igshid`, `utm_source` and Instagram's other tracking keys, and so do the post and story links Instagram's server hands out for sharing. Keys come off Instagram's own links only.
* New patch, `Disable analytics`. Instagram's usage events go to your phone's loopback address, which refuses them, instead of to Instagram's and Facebook's logging servers.
* New patch, `Remove build expired popup`. A patched Instagram doesn't update itself, and without this Instagram locks it out after a few weeks with a screen that says the version is too old.
* `Restore trust on re-signed builds`, from Hushfacebook. Instagram's own signature checks see Instagram's two original certificates on a build you signed yourself.
* `HushGram settings`, from Hushfacebook: a switch for each feature, Pause, automatic safe mode after three quick crashes in a row, Debug logging and a diagnostic report that leaves out links, IDs, cookies and sign-in tokens. It opens from a shortcut on Instagram's launcher icon, and that works before you sign in too: when Instagram's sign-in screen comes up over it, the settings move in front.
* Runs on Android 9 and newer, which is Instagram 449's own floor. The settings screen, the launcher shortcut, safe mode and the report export all have Android 9 and 10 paths, since Hushfacebook's code expected Android 11.
* German, Spanish, Indonesian, Brazilian Portuguese and Turkish translations for everything HushGram shows.
