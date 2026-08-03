# Android Store Distribution — Phase Plan

**Created:** 2026-08-02
**Priority:** MID. Deliberately gated on the app being battle-tested by the author first.
**Status:** NOT STARTED — scoped only.
**Supersedes in part:** `2026-07-04-playstore-publishing-sop.md`. That SOP is still the best
reference for Play Console mechanics, but its Google OAuth stages (0.3, consent screen, SHA-1
registration) are **obsolete** — the app moved to IMAP + app password (Road B, 2026-07-30) and no
longer uses Google OAuth on Android at all.

---

## 0. Why this phase exists, and why it is mid-priority

The decision to pursue Android store distribution is the author's, taken 2026-08-02 after being
advised to wait. The advice and the decision are both recorded here so neither gets re-litigated.

The gate is explicit: **no store submission until the author has run the app on their own device
long enough to trust it.** As of this writing, end-to-end IMAP delivery has never been verified on
device, and the duplicate-delivery bug fixed in v0.2.1-beta was originally observed on the phone.
Shipping that to strangers' mailboxes is a different class of mistake from shipping it to your own.

Mid-priority means: do the prerequisites that are useful regardless (icons, privacy policy), and
hold the submission itself.

---

## 1. Target stores — the shortlist

| Store | Cost | Artifact | Tester gate | Why |
|---|---|---|---|---|
| **Samsung Galaxy Store** | free | signed APK | none | First listing. Free registration, accepts our own-key APK as-is, no Play-style closed-testing requirement, large Samsung base in India. |
| **Indus Appstore** | free | APK | none | India-focused (PhonePe), zero commission, preinstalled on some India-sold devices. Young — treat reach claims sceptically, but listing cost is near zero. |
| **Xiaomi GetApps** | free | APK | none | Large Xiaomi base in India. Lower priority than the two above; same low-friction character. |
| **Google Play** | US$25 once | AAB | yes (closed testing, personal accounts) | Deferred. Highest reach, highest friction, and the AAB + Play App Signing decision has knock-on effects (see §3). |

### Ruled out, with reasons

- **Amazon Appstore** — Amazon wound down the Android-phone store; Fire tablets only. Not worth it.
- **F-Droid** — needs a FOSS licence (`portable/App/AppInfo/appinfo.ini` currently declares
  `OpenSource=false`, `Shareable=false`) **and** builds from source itself, which the proprietary
  `play-services-auth` dependency blocks. Would require relicensing plus a dependency-free flavour.
  Real work, not a checkbox.
- **Huawei AppGallery** — no meaningful Indian reach. Skip.
- **APKPure / Aptoide / Uptodown** — aggregators, not submission targets. They mirror public APKs
  regardless of what we do.

---

## 2. Non-store alternative worth checking first

If the motive for listing is Google's developer-verification requirement for sideloaded apps, note
that Google's stated position is that **verified developers may continue distributing outside
Play** — verification and Play publishing are separate. If that holds, registering as a verified
developer may deliver the goal with no store listing at all.

**Action:** confirm against Google's current developer documentation before investing in any
listing. This policy is recent and has moved; do not rely on a summary.

---

## 3. The signing trap — decide before the first submission

Signing keys do not travel between stores. If the app is published to Galaxy Store signed with
`android/app/release.jks`, and later to Play with **Play App Signing** (where Google re-signs with
their own key), an install from one store **cannot be updated by the other** — same package name,
different signature, update refused.

Two coherent options:

1. **Own key everywhere** (simpler for multi-store). Means opting out of Play App Signing if Play
   is ever added, which Play increasingly discourages.
2. **Play App Signing, Play only.** Clean, but forecloses the multi-store path.

Recommendation: option 1, given the shortlist above is deliberately non-Play.

Either way, `release.jks` becomes unrecoverable infrastructure the moment a stranger installs the
app. It must be backed up off-machine, with its passwords, before first submission. It is gitignored
and must stay that way.

`applicationId = com.wamailsync.app` is fixed forever from the first publication anywhere.

---

## 4. Prerequisites (do these regardless — they unblock other work too)

- [ ] **Icon assets.** The repo has *none*. Needed: `appicon.ico`, `appicon_16/32/75/128/1024.png`.
      This is the same gap blocking the Windows `.paf.exe` PortableApps installer, so it pays twice.
- [ ] **Privacy policy URL.** Required by all three shortlisted stores. A GitHub Pages page is
      sufficient. Content: the app sends the user's own WhatsApp exports to the user's own mailbox
      via their own IMAP credentials; no data reaches the developer or any third party.
- [ ] **Store artwork** — screenshots (phone), feature graphic, short + long description.
- [ ] **Fix stale `portable/App/AppInfo/appinfo.ini`** — Name still says "WAGmail Sync",
      PackageVersion/DisplayVersion still 1.0.0. Not a store blocker but the same rename sweep.
- [ ] **End-to-end IMAP delivery verified on device.** THE GATE. Nothing ships until this passes.

## 5. Submission steps (Galaxy Store first, once the gate clears)

1. Samsung Developer account registration + seller registration (free tier, free apps).
2. Upload `WAMailSync-v<x>.apk` signed with `release.jks`.
3. Complete content rating, target-age, and data-safety declarations honestly — the app collects
   nothing, which makes these easy while it stays true.
4. Note the review cycle. Once listed, "build, sideload, check the mailbox" stops being how fixes
   ship; every fix goes through review. Budget for that.
5. Repeat for Indus, then GetApps, reusing the same artefacts.

---

## 6. Known constraints carried in

- `arm64-v8a` only (`abiFilters` in `android/app/build.gradle.kts`). Acceptable for modern devices;
  excludes older 32-bit hardware and most emulators. Stores will surface this as reduced device
  coverage.
- `minSdk 24`, `targetSdk 36`, `compileSdk 36`. Stores enforce their own target-SDK floors on an
  annual treadmill — expect a forced bump roughly yearly once listed.
- The user must supply their own IMAP app password. This is a real onboarding cliff for a store
  audience who did not build the app, and the listing copy has to be honest about it.
