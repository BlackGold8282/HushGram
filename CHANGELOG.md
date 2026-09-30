# Changelog

Every HushGram release, newest first.

## Unreleased

### HushGram v0.0.2

* Added a HushGram logo and README banner in the family's style, using the pink from HushGram's settings.
* New patch, `Clean up Reels`. The Follow button beside a reel's author goes, and so do the pills that push Edits, templates, Meta AI and Ray-Ban Meta glasses, and friends' activity with the comment preview. Each of the three has its own switch under Reels, and they're all on once the patch is in.
* New patch, `Don't send reel watch history`. Instagram isn't told which reels you watched or how far into them you got. It only uses that to rank your Reels, so reels you've already seen may come back.
* New patch, `Hide Reels in the feed`. The rows of suggested reels between posts in your home feed are gone, along with the other units that open the Reels viewer from there. A reel from someone you follow still shows as a post.
* New patch, `Download any reel`. Every reel's more menu has Download, including the shorter menu some accounts get, and a tap saves the reel at your download quality (the best there is by default) without Instagram's watermark. Its switch is under Reels.
* New patch, `Download any story`. The menu on anyone's story has Download. A video story saves at your download quality and a photo story at its largest size, into the same folders as reels. Its switch is under Stories.
* New patch, `Download any video`, off until you pick it. Someone else's post in your feed that is a video gets Download in its menu, first in the short menu most posts open now, including the one under an "About this reel" summary. A tap saves it at your download quality without Instagram's watermark. On your own video, Instagram's own Download row, where it shows one, saves the same way. Its switch is under Downloads.
* New patch, `Tap to play`, off until you pick it. Videos, reels and stories wait for your tap instead of starting on their own, and feed videos show a play button that goes away once your tap starts the video and comes back when it stops. A story or video that takes a few seconds to load after your tap still starts. In Reels, a tap on a reel that's waiting starts it and a tap on a playing one pauses it. A video you started keeps playing when you drag its scrubber. Its switch is in a new Playback section.
* New patch, `Resume long videos`. A video or reel over two minutes that you left partway picks up there the next time it plays, once. Drag the scrubber to start somewhere else. Live videos, ads and anything you watched to the end start as usual. Its switch under Playback starts off, and the spots it keeps are dropped after 30 days.
* New patch, `View stories anonymously`, off until you pick it. Instagram isn't told which stories you watch, so you stay off their viewer lists. Replying or reacting still shows you, and a story you've watched can show as new again. Its switch is in the Stories section.
* New patch, `Remove the advertising ID`. Instagram can't read your phone's advertising ID or tell Android's ad services which ads you saw or tapped, because the permissions for them are taken out of the build. It shows under Set when you patched.
* New patch, `Turn off double tap to like`, off until you pick it. A double tap on a reel or on any post in the feed, whether it's a photo, a carousel or a video, doesn't like it, and the heart doesn't show. A single tap, double tap to skip and the Like button work as before. Its switch is under Reels.
* New patch, `Open links in external browser`, on by default. A web link you tap opens in your default browser instead of Instagram's in-app browser, and a bio or caption link skips Instagram's click tracker on the way. Pages on Instagram, Facebook, Messenger, Threads and Meta stay in the app, and so do ads. Its switch is under Ads and privacy.
* New patch, `Hide the Reels tab`, off until you pick it. Reels leaves the tab bar, and a start or a notification meant for it opens Home, including on accounts Instagram opens on Reels. Reels in your feed and shared reels still play. Its switch is under Reels and takes effect after Instagram restarts.
* New Downloads section in HushGram's settings. It lists the saves that are running, each with a Cancel button, and holds the download quality, the save folder, the video file name and a switch that saves videos other apps can open.
* New patch, `Stop Story auto-advance`. A story stays on screen until you tap or swipe, however long it runs. Turn its switch off for Instagram's timing.
* `Disable analytics` also covers the analytics address Instagram's server can hand its push connection. Before, only the address built into the app went to the loopback address, and a different one from the server went through as is.
* With Debug logging on, the diagnostic report says what `Disable analytics` did with each analytics address Instagram used, and which of Instagram's processes asked for it. Checked on a phone: with the switch on, nothing reached Instagram's logging server at all.
* Release tooling, from Hushfacebook. Before a release, `scripts/build-release-receipt.ps1` patches every Instagram build the catalog declares and writes a receipt saying what applied, which toolchain built the bundle and what the patches changed in the manifest. It asks OSV about every library the bundle carries, and it writes `SHA256SUMS.txt` for the files a release publishes. When the index goes out, the pre-push hook downloads those files and checks them against both. `scripts/validate-release-facts.ps1` keeps the README, the CHANGELOG and the bug form in line with the patch list, and it works before the first release too.
* Morphe Manager and bundle sites like morphe-patches.software show HushGram's own icon next to the source instead of the owner's GitHub picture. The icon is `patches-bundle.png` in the repository's root.
* README now leads with a Before you sign in section that spells out how to keep your account: the sign-in is the moment Instagram checks the app with Play Integrity and your phone's hardware, so use a seasoned account, prefer a Root Mount install if you're rooted, and don't clear the app's data afterward. The account section says plainly that a re-signed build can't pass those checks.

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
