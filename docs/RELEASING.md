# Releasing Chat Mail Sync

Written 2026-09-07, the day the Xiaomi GetApps listing went live. Before that,
a release was "tag it and upload a zip"; now there are stores downstream that
only learn about a new version because someone tells them, and a signing key
that can never be replaced. Both of those fail quietly, months later, which is
exactly the kind of thing a checklist is for.

Nothing here is automated on purpose. Store uploads need a human at a console,
and the key must never travel through CI.

## The two one-way doors

Read these before anything else. Neither is recoverable.

1. **`android/app/release.jks` can never be replaced.** Every store that
   carries the app pins the signing certificate. Lose the keystore and the app
   cannot be updated anywhere - not on GetApps, not for sideloaders, not
   through Obtainium. The only path forward would be a new `applicationId`, a
   new listing and zero users. It lives at `android/app/release.jks`, is
   gitignored, and is archived off-machine at
   `G:\My Drive\Documents\Recoveries\WAGS` alongside its `keystore.properties`.
   Verified identical 2026-09-07 (SHA-256 `c76550dd...b667036`).
2. **`versionCode` only ever goes up.** GetApps rejects an upload whose
   `versionCode` is not greater than the published one, and there is no way to
   reuse a number. 37 was 2.1.1. Never edit it downwards to "redo" a release -
   burn the number and move on.

## Before the release

- [ ] Working tree clean, on `main`, everything intended is merged.
- [ ] Bump the version in **both** places, they are not linked:
      `android/app/build.gradle.kts` (`versionCode` **and** `versionName`) and
      `portable/App/AppInfo/appinfo.ini` (`PackageVersion` is four-part,
      `DisplayVersion` is three).
- [ ] Python suite green: `python -m pytest tests/ -q` (PowerShell - it is
      denied under Bash here).
- [ ] Android suite green: `cd android && ./gradlew.bat :app:testDebugUnitTest
      --console=plain`.
- [ ] FAQ parity still holds - `tests/test_faq_parity.py` covers `help.html`,
      `docs/user-guide.md` and `HelpScreen.kt`. `portable/help.html` is not a
      parity surface.
- [ ] If any user-visible behaviour changed, both front-ends changed. See
      `PLATFORM-PARITY.md`; this is a rule, not a preference.

## Build

- [ ] `cd android && ./gradlew.bat :app:assembleRelease --console=plain`
      Output: `android/app/build/outputs/apk/release/app-release.apk`.
      **Not** `dist/` - the APK has never lived there.
- [ ] Confirm what you actually built, rather than what you meant to:
      `C:/Android/Sdk/build-tools/36.0.0/aapt2.exe dump badging <apk>`
      (`aapt2`, not `aapt` - `aapt` silently prints nothing).
      Check package, `versionCode`, `versionName`.
- [ ] Windows portable: `.\build_portable.ps1 -Installer -Zip`.
      Never `-SeedCredentials`, never `-InstallCert`, and never zip
      `dist\ChatMailSyncPortable` by hand - it carries real credentials and
      real chats in `Data\`.

## Publish

- [ ] Tag and push: `git tag vX.Y.Z && git push --tags`.
- [ ] GitHub release with the APK and the portable artifacts attached.
      Use `gh release create ... --notes-file` - `--body-file` is rejected.
      `gh` is on the PowerShell PATH only.
- [ ] This is the whole release for **Obtainium** users. It reads the GitHub
      releases page directly and needs nothing further.

## Stores - the step that is easy to forget

Every store below is a manual push. None of them watch GitHub.

### Xiaomi GetApps (live since 2.1.1)

Console: https://global.developer.mi.com - listing at
https://global.app.mi.com/details?id=com.chatmailsync.app

- [ ] Upload the same signed APK. Same package, same key, higher `versionCode`.
- [ ] Update the release notes; leave the listing text alone unless it changed.
- [ ] Submit for review. 2.1.1 passed first time, same day.
- [ ] Auto-update is on, so approval is what actually ships it to users.

If the console asks again for signature verification, it supplies an *unsigned*
stub APK: sign it with the release key and hand it back **under Xiaomi's
original filename** - that is the convention, a `-signed` suffix is a local
nicety only.

### Huawei AppGallery / Indus Appstore

Not carrying the app yet. When they do, they join the list above with the same
rule: same APK, same key, manual upload, separate review.

## After

- [ ] Update `README.md` and `docs/index.html` if the set of places the app can
      be installed from has changed. They name the routes explicitly, and a
      stale install section is a support problem.
