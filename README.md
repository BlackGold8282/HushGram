<p align="center">
  <img src="https://img.shields.io/badge/version-0.0.1-E1306C" alt="Version 0.0.1">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84" alt="Platform Android 9+">
  <img src="https://img.shields.io/badge/Instagram-449.0.0.52.84-E1306C" alt="Instagram 449.0.0.52.84">
  <img src="https://img.shields.io/badge/for-Morphe%20Manager%201.32.0%2B-8A2BE2" alt="For Morphe Manager 1.32.0 or newer">
</p>

# HushGram

HushGram is a Morphe patch bundle for Instagram on Android. It hides the ads, keeps the tracking keys off the links you share, and stops Instagram from sending its usage events home.

It's the Instagram member of a small family. [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) does the same job for Facebook, and HushGram is built on its foundation: the same settings screen, pause switch, diagnostics and checks.

There's no release yet. Version 0.0.1 is the first set of patches, and until a release is published you build the bundle yourself (see [Building from source](#building-from-source)).

This project has no connection to Meta or to the Morphe project. Neither endorses it, and neither wrote it.

## Why use it

- **No sponsored posts.** Ads in the feed, Reels and Stories don't go in, and Instagram doesn't leave a gap where they would have been.
- **Cleaner links.** When you copy a link or share one, through Android's share sheet or straight to WhatsApp or another app from Instagram's own, `stkn` (the per-share id Instagram adds now), `igsh`, `utm_source` and the other tracking keys come off. The link still opens the same post. A link in someone's bio opens its page directly, not through `l.instagram.com`, Instagram's click tracker.
- **Less sent home.** Instagram's usage events go to an address on your own phone that refuses them.
- **A build that keeps working.** A patched Instagram doesn't update itself, and Instagram locks out an old build after a few weeks. HushGram stops that lockout screen.

Every feature has its own switch, and one Pause switch turns them all off at once when you want to see whether HushGram is behind something odd.

## Install

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) 1.32.0 or newer.
2. Build the bundle (below) and copy the `.mpp` file to your phone. In Morphe Manager, add it as a patch source from your phone's storage.
3. Get Instagram 449.0.0.52.84 from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/). Take the variant labelled (arm64-v8a) (640dpi) (Android 9.0+), build 385511871. That's the one these patches are checked against. APKMirror carries other arm64-v8a builds of the same version, and Morphe Manager warns about those because they haven't been checked yet.
4. Uninstall the Instagram you got from the Play Store. The patched app is signed with your own key, so Android won't install it over Meta's. Uninstalling signs you out, so have your password (and your two-factor codes) ready.
5. In Morphe Manager, pick the Instagram file, keep the default patch selection or change it, and patch.

HushGram supports arm64-v8a phones on Android 9 and newer, which is what Instagram 449 itself asks for.

Instagram ships a new version every week and renames most of its code each time. Each patch finds what it changes by things Instagram keeps from one build to the next (log strings, server field names, manifest components and Android's own calls) rather than by the names that change. When one can't find its target, patching stops with a message saying what's missing, instead of giving you an app that quietly does nothing. Please report a stop like that.

## Keep your signing key

Morphe Manager signs the patched Instagram with a key it makes on your phone. Android only installs an update over your patched Instagram when the update carries that same key, so the key is what lets you update without losing Instagram's data.

- **Back it up right after your first patch.** In Morphe Manager, open Settings → System → Import & export → Signing key and tap Export. Keep the `Morphe.keystore` file somewhere private, because anyone who has it can sign an APK your phone will take as an update.
- **On a new phone, import it before you patch anything.** Reinstalling Morphe Manager or clearing its storage makes a new key, and without your exported copy nothing you patched earlier can be updated in place.
- **A different key means starting over.** Android refuses an update signed with another key, so the only way forward is to uninstall the patched Instagram. That deletes its data and signs you out.

Morphe's own guide is [Backup and keystore](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md).

## Patches

| Patch | What it does |
|---|---|
| `Disable analytics` | Sends Instagram's usage events to an address on your phone that refuses them, instead of to Instagram's and Facebook's logging servers. Restart Instagram after changing the switch. |
| `Don't send reel watch history` | Stops telling Instagram which reels you watched and how far into them you got. It's used to rank your Reels, and nobody else sees it. Reels you've already watched may come back. |
| `Hide ads` | Hides sponsored posts, reels and stories. Instagram is told the ad didn't go in, so no gap is left where it would have been. |
| `HushGram settings` | Adds HushGram settings to Instagram. Long-press Instagram's launcher icon and pick HushGram settings, or tap HushGram settings at the top of Instagram's Settings and activity, to turn features on or off, pause HushGram and export diagnostics. The licenses are there too. |
| `Remove build expired popup` | Stops Instagram from locking you out with a screen that says this version is too old. A patched build doesn't update on its own, so without this it would stop working after a few weeks. |
| `Restore trust on re-signed builds` | Lets Instagram's own signature checks pass on a re-signed build, so the parts of the app that check who signed it keep working. A Root Mount install doesn't need this patch. |
| `Sanitize sharing links` | Takes stkn, igsh, utm_source and Instagram's other tracking keys off the links you copy or share, and opens a bio link without going through Instagram's click tracker. The post, reel, story or profile a link opens stays the same. |

The other patches keep their switches in `HushGram settings`, so Morphe Manager includes it whenever any of them is picked. Any of the rest can be left out when you patch.

## Settings

Long-press Instagram's icon on your home screen and tap **HushGram settings**. Or, inside Instagram, open **Settings and activity** from the menu on your profile and tap **HushGram settings** at the top. The screen opens over Instagram, and the shortcut works before you sign in too.

<p>
  <img src="assets/settings-switches.png" alt="HushGram settings: the on card and the Ads and privacy switches" width="270">
  <img src="assets/settings-pause-and-diagnostics.png" alt="HushGram settings: Set when you patched, Pause and Debug logging" width="270">
</p>

At the top, a card says whether HushGram is on or paused. Below it:

- **Ads and privacy** holds the switches for Hide ads, Sanitize sharing links and Disable analytics.
- **Reels** holds the switch for Don't send reel watch history.
- **Updates** holds the switch for the build expired screen.
- **Set when you patched** lists what was fixed at patch time and can't be switched off here, such as the re-signed build fix.
- **Pause and diagnostics** has the Pause switch, Debug logging, and the diagnostic report. Copy a quick report, or save the full one to Download/Morphe (on Android 9, a Download/Morphe folder inside Instagram's own folder, and the message says where). Links, IDs, cookies and sign-in tokens are left out, but read it over for other private text before you share it.
- **About** shows the version and the licenses, with a link to this page.

Pause turns off every feature a switch controls, all at once and without losing your choices. It's the quickest way to tell whether HushGram is behind a problem.

If Instagram crashes within a minute of starting three times in a row, HushGram pauses itself and the card says why. Turn it back on from the same screen once you've patched again or left out the patch at fault. When Instagram won't stay open long enough to reach the settings, create an empty file named `hushgram-safe-mode` in `Android/data/com.instagram.android/files` (a computer or a file manager can reach it), and HushGram starts paused until you delete it.

## Known limitations

- Sanitize sharing links covers Copy link, the Android share sheet, the app buttons in Instagram's own share sheet, a profile's share link and the post and story links Instagram's server hands out. Bio links open without Instagram's click tracker. Links in messages and story link stickers haven't been checked on a phone yet.
- Don't send reel watch history keeps reels out of the list from the moment it's on. A list Instagram saved before you patched can still go out once.
- Disable analytics covers the event uploads Instagram and Facebook's logging endpoint receive. Instagram has other reporting paths, and this patch doesn't claim to stop every one.
- A patched Threads signed with the same key can't offer "Continue as" your HushGram account yet. It asks you to log in with your password instead.
- Only one Instagram build has been checked so far. Expect a patch to stop on a newer one until it's checked.

## Troubleshooting

### Package conflict or App not installed

The Play Store Instagram is still on the phone. Android won't replace an app signed with Meta's key by one signed with yours. Uninstall it, then install the patched one.

### Unsupported Version

Morphe Manager says this when your Instagram file isn't the build these patches were checked against. Use 449.0.0.52.84, build 385511871. Patching a different build may still work, but no one has checked it.

### Patching stops on one patch

Instagram changed the part that patch looks for. Leave that patch out to get a working build now, and please open an issue naming the patch and your Instagram version.

## Your Instagram account

**Can Meta tell?** Assume it can. A patched Instagram is signed with your key, not Meta's, and Instagram checks that signature in places, which is why `Restore trust on re-signed builds` exists. With `Disable analytics` on, Instagram's usage events stop reaching Meta too, and Meta could notice that.

**What stays the same?** Your feed, stories and reels still come from Meta's servers, and HushGram decides on your phone which of them to show. It doesn't post, like, follow or message for you, and it doesn't change how you sign in.

**Could my account be suspended?** Nobody can promise it won't be. Meta's [Terms of Use](https://help.instagram.com/581066165581870) don't allow modified versions of its apps, and Meta can disable accounts that break them. If you'd rather not risk the account you care about, try HushGram with a spare account first.

## Getting help

For something that's broken, use the [bug form](https://github.com/SysAdminDoc/HushGram/issues/new?template=bug_report.yml) and attach the diagnostic report it asks for, since it answers most of what we'd need to know. Ideas go on the [feature form](https://github.com/SysAdminDoc/HushGram/issues/new?template=feature_request.yml). When Morphe Manager misbehaves with every app, not only Instagram, [Morphe's own tracker](https://github.com/MorpheApp/morphe-manager/issues) is the place.

## Privacy

HushGram doesn't collect anything and has no server. Nothing HushGram adds to Instagram opens a network connection of its own. Its code names just two addresses:

- `github.com`, for the link to this page in settings. It opens in your browser, and only when you tap it.
- `127.0.0.1`, your phone's own loopback address. Disable analytics hands it to Instagram in place of its logging servers. Nothing sent there leaves the phone, and nothing on the phone answers.

The diagnostic report stays on your phone until you copy or share it yourself.

## Where the patches come from

| Source | What came from it |
|---|---|
| [SysAdminDoc/Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) at `c15d4f7` | The Gradle build, the shared extension library with its settings screen, pause and diagnostics, the bytecode helpers, the link cleaner, the launcher shortcut and the checks that apply every patch to a real Instagram build. |
| [andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches) at `5db2e57`, by way of Hushfacebook | The fix for re-signed builds, pointed here at Instagram's own two signing certificates. |
| [SysAdminDoc/hushfeed](https://github.com/SysAdminDoc/hushfeed), [tiktok-patches-for-morphe](https://github.com/icysymmetra/tiktok-patches-for-morphe), [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) | Where Hushfacebook's foundation came from: the patcher, the patch template and the shared library. |

Hide ads, Disable analytics, Remove build expired popup, Don't send reel watch history and the Instagram side of Sanitize sharing links were written here.

Every source file says where it came from in its header, and [provenance.json](provenance.json) maps each file to the project and commit it came from, with its licence. [docs/sources.md](docs/sources.md) covers the other Instagram patch sources and what each one does. The ledger behind it, [sources/instagram-sources.json](sources/instagram-sources.json), pins each source's licence, and code is only ported from a source it lists as adopted.

## Building from source

You need JDK 17 or newer and the Android SDK. The Morphe patcher comes from GitHub Packages, so you also need a GitHub token with `read:packages`.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<a token with read:packages>
./gradlew :patches:generatePatchesList
./gradlew :patches:buildAndroid
```

The bundle lands in `patches/build/release/patches-<version>.mpp`, beside its SHA-256 and a CycloneDX SBOM (`patches-<version>.cdx.json`) listing every library that goes into it. Run `generatePatchesList` before `buildAndroid`, or the bundle loses its Android payload.

Tests: `./gradlew :patches:test :extensions:instagram:testDebugUnitTest`. Set `HUSHGRAM_FIXTURE_DIR` to a folder holding Instagram builds to run the tests that read real ones. Without it they skip and say so.

To apply every patch to a real build and check the result, run `scripts/verify-all-patches.ps1 -Apk <instagram .apks> -DesktopJar <morphe-desktop jar> -WorkDir <scratch folder>`. It patches without forcing anything, then compares the patched manifest to Meta's. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

## License

[GPL-3.0](LICENSE), with the Morphe section 7 notices carried in [NOTICE](NOTICE). Instagram, Facebook and Meta are trademarks of Meta Platforms, Inc.
