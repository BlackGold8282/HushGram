# Contributing

Bug reports, fixes for a new Instagram build, new patches and pull requests are all welcome.

If you open an issue, include:

- the Instagram version and build number you patched (APKMirror shows both, for example 449.0.0.52.84, build 385511871)
- the Morphe Manager version and the HushGram version
- the patches you selected
- what you expected and what happened, with steps to get there
- a diagnostic report, or screenshots if it's visual. Take private messages and account details out of screenshots first.

GitHub doesn't let the person who opened an issue reopen it once a maintainer closes it, so a closing comment always says how to get it reopened: comment there and we'll reopen it.

## When a new Instagram build breaks a patch

Instagram renames most of its code every week, so a patch never looks for a method by its name. Each one anchors on something Instagram keeps from build to build: a log string, a server field name, a manifest component, or a call into Android itself. When patching stops, the message names the anchor it couldn't find.

To fix it, find where that anchor went in the new build and tighten the fingerprint so it matches exactly one method again. A fingerprint that matches two methods fails too, on purpose: a guess that lands on the wrong method gives you an app that looks patched and does nothing, or worse.

## Building and checking

Read the README's build section first. Gradle needs `GITHUB_ACTOR` and `GITHUB_TOKEN` (a token with `read:packages`) to fetch the Morphe patcher. Run `:patches:generatePatchesList` before `:patches:buildAndroid`.

Before a change goes in:

- `./gradlew :patches:test :extensions:instagram:testDebugUnitTest` with `HUSHGRAM_FIXTURE_DIR` set, so the tests that read real Instagram builds run instead of skipping.
- `./gradlew :extensions:instagram:lint :extensions:shared:library:lint`. Instagram runs on Android 9, so a call Android added later needs a version check, and lint catches the ones that don't have it.
- `scripts/verify-all-patches.ps1` on every build the catalog declares. It applies every patch in one run without forcing anything, checks the CLI's own report, and compares the patched manifest to Meta's against `scripts/manifest-delta-allowlist.txt`, which approves nothing today.

`scripts/install-hooks.ps1` installs a pre-push hook that runs those tests and lints when a push changes `extensions/` or `patches/`. Set `HUSHGRAM_SKIP_PRE_PUSH=1` to push without it.

## Settings for your machine

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do that instead, and none of them has a default:

- `HUSHGRAM_FIXTURE_DIR` is the folder holding the Instagram builds the fixture tests and scripts read. They're hundreds of megabytes each, so they aren't in the repository.
- `HUSHGRAM_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHGRAM_WORKDIR` or a jar under `build/morphe-tools` works too.
- `HUSHGRAM_DEVICE_SERIAL` is the adb serial of a test phone for `scripts/patch-for-device.ps1`. Keep your own phone out of it: a re-signed Instagram can't install over the Play Store copy without uninstalling it, which signs you out.

## Source notices

Keep every existing copyright, license, author credit and source-origin notice when you modify or move a file, and don't remove a notice unless the code it covers is gone from the file. Most of this code came from Hushfacebook and, before it, from Morphe and ReVanced, all GPL-3.0. In this ecosystem a missing notice has already ended in DMCA takedowns more than once.

New source written for this project may use:

```text
/*
 * Copyright <year> HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
```

Code taken from another project keeps its notices and gets a `Forked from:` line with the file's URL at the commit it came from. Record it in `provenance.json` too. A rule naming a single file wins over the folder rule around it. `ProvenanceTest` fails when a shipped file matches no rule or two, when a rule names an upstream that NOTICE doesn't, or when a file's header doesn't link a repository of its rule.

Code can only come from a source that `sources/instagram-sources.json` lists as adopted. That takes the commit the code came from, a licence that works with GPL-3.0, the source in NOTICE and its rule in `provenance.json`. `scripts/test-instagram-sources.ps1` refuses the ledger without any of them. A source the ledger calls behavior-only is never copied from, only read for what it does. When you find a new source, run `scripts/audit-instagram-sources.ps1`, which reports what moved since the last census.
