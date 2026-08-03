# Google Play Publishing SOP — WA Chat Sync to Gmail

> **⚠ PARTIALLY SUPERSEDED (2026-08-02) — read `2026-08-02-android-store-distribution-phase.md` first.**
>
> This document is **Google Play only**. Play is now *deferred*; the current plan lists on Samsung
> Galaxy Store first, then Indus Appstore and Xiaomi GetApps. See the phase doc for the store
> shortlist, the cross-store signing trap, and the battle-testing gate.
>
> **Obsolete sections:** everything about Google OAuth — Stage 0.3 (Android OAuth client, SHA-1
> registration), the OAuth consent screen, and the Stage 3 OAuth verification. The app moved to
> IMAP + app password (Road B, 2026-07-30) and no longer uses Google OAuth on Android.
>
> **Still accurate:** Play Console mechanics — account signup, the US$25 fee, Play App Signing,
> closed-testing rules for personal accounts, content/data-safety declarations.
>
> Contents below are left unedited as a historical record.

**Version:** 1.0 (2026-07-04)
**Audience:** first-time publisher. Written as a do-this-then-that guide.
**Scope:** Stage 1 (personal use — plan phase A7), Stage 2 (closed testing — A8), Stage 3 (public production + Google OAuth verification — A9).
**⚠ Policy freshness:** every dated rule below was verified 2026-07-04. Play and Google OAuth policies change frequently — re-check the linked source at the start of each stage.

---

## Stage 0 — One-time accounts & prerequisites

### 0.1 Google Play developer account
1. Go to <https://play.google.com/console/signup>.
2. Choose **Personal** account type (organization requires a D-U-N-S number; personal is right for this project — note the personal-account testing rules in Stage 2 are the trade-off).
3. Pay the **US$25 one-time** registration fee.
4. Complete **identity verification** (government ID + address; can take days — start early). A developer name, contact email, and phone are shown publicly later, so decide what to expose.
5. New personal accounts must also verify a **developer email/phone** and later meet the closed-testing requirement (Stage 2) before any production release — this is by design in the plan.

### 0.2 App signing keys — understand this before first upload
- Use **Play App Signing** (default, recommended): Google holds the release signing key; you keep an **upload key**. If the upload key leaks you can rotate it — far safer for a first-time publisher.
- Generate the upload keystore in Android Studio (`Build → Generate Signed App Bundle`), store the `.jks` + passwords in a password manager, **back it up off-machine**.
- Record the **SHA-1 of both** the upload key and (after first upload) the Play-provided app-signing key — OAuth needs them (Stage 0.3).

### 0.3 Google Cloud Console — Android OAuth client (same project as Windows)
1. Open the existing Cloud project (the one with the Gmail API + Desktop client).
2. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android.**
3. Enter the app's package name (e.g., `com.<yourname>.wagmailsync`) and the **SHA-1** of your **debug** key first (for development), then add the **upload** and **Play app-signing** SHA-1s as additional Android clients when release builds start. *(Miss this and release-build sign-in fails while debug works — the classic first-timer trap.)*
4. No client secret file exists for Android clients — this is expected.
5. **OAuth consent screen:** keep publishing status = **Testing** for Stage 1; add your own Gmail address (and later testers) under **Test users** (max 100).

---

## Stage 1 — Personal use (plan phase A7)

Goal: the app on your own phone with Play-managed updates, no review friction, no verification cost.

### 1.1 Create the app in Play Console
1. Play Console → **Create app**: name "WA Chat Sync to Gmail", default language, **App** (not game), **Free**. Free is one-way — a free app can never become paid (in-app purchases are still allowed, which is what the tip-jar uses later).
2. Complete the mandatory **App content** declarations (Dashboard walks you through): privacy policy URL (required even for testing tracks — a simple GitHub Pages page works; write it from the app-definition doc §6), ads declaration ("No" until ads activate), content rating questionnaire (→ Everyone), target audience (18+ to skip child-directed complexity), data safety form (see 3.4 — fill honestly now, it's easier while the app collects nothing).

### 1.2 Internal testing track (this is "personal use" mode)
1. Build an **.aab**: Android Studio → `Build → Generate Signed App Bundle` with the upload key. Confirm `targetSdk` meets the current requirement — **API 35 now; API 36 required for new submissions from 2026-08-31** (Wear/TV differ).
2. Play Console → **Testing → Internal testing → Create release** → upload the `.aab` → release notes → **Start rollout**. Internal testing supports up to 100 testers, is available within minutes, and does not require the closed-testing gauntlet.
3. Add your Google account under **Testers**, open the opt-in link on the phone, install from Play.
4. **OAuth reality in this stage:** consent screen is in Testing → refresh tokens expire every **7 days** (weekly re-consent), OR flip the consent screen to **In production** *without* verification → persistent tokens but an "unverified app" warning at consent and a 100-user lifetime cap. For daily personal use, most people prefer the second. Both are acceptable while users are just you + people you know (Google's documented personal-use exception).

**Sideloading alternative:** for pure personal use you can also `adb install` / share the APK and skip Play entirely — no updates, no tester plumbing. Reasonable interim while iterating, but do set up the internal track; it exercises the pipeline you'll need anyway.

---

## Stage 2 — Closed testing (plan phase A8) — mandatory gate for personal accounts

Personal developer accounts created after 2023-11-13 **cannot access production** until they run a closed test with **at least 12 opted-in testers for 14 consecutive days**. Verified 2026-07: the count is 12 (reduced from 20 in Dec 2024); Google now also watches tester *engagement* — testers who never open the app can be flagged inactive, so recruit people who'll actually use it.

1. **Testing → Closed testing → Create track** → upload the same (or newer) `.aab` → Start rollout.
2. Recruit 12+ real people with real Android devices (friends/family/colleagues; emulators and throwaway accounts don't count). Add their emails to the tester list (or a Google Group) and send the opt-in link.
3. Add each tester's Gmail address as a **Test user** on the OAuth consent screen too, or they can't connect to Gmail. (100-slot cap is plenty here.)
4. The 14-day clock starts once the release is approved **and** 12 testers have opted in. Keep them engaged: ask each to run at least one real sync and to open the app a few times across the fortnight.
5. Collect feedback; ship fixes to the same track (updates don't reset the clock).
6. After 14 days, **Dashboard → Apply for production access**: a questionnaire about your app and testing learnings. Approval typically takes days.

---

## Stage 3 — Public production release (plan phase A9)

Two independent gates run in parallel. Start the Google OAuth one first — it's the slow, expensive one.

### 3.1 Gate A: Google OAuth restricted-scope verification (the big one)

The app uses `gmail.insert` and `gmail.labels` — **restricted scopes**. A public app must pass:

1. **Brand verification:** consent screen with app name, logo, support email, and a **homepage you own** (domain-verified via Search Console), plus a **privacy policy hosted on that domain** that matches what the app actually does.
2. **Scope justification + demo video:** written explanation of why each restricted scope is needed (our story is strong and narrow: insert-only archiving; never reads mail) and a YouTube-linked video demonstrating the OAuth flow and scope usage in the app.
3. **CASA Tier 2 security assessment:** annual third-party assessment via an App Defense Alliance authorized lab. Budget realistically: commonly **~US$500–$5,000+/year** depending on assessor and how automated their process is (quotes vary wildly — get 2–3; costs many times that are quoted for complex/multi-app setups). Re-verification is required **every 12 months** from the Letter of Assessment date. **Decision checkpoint:** confirm you're willing to pay this annually *before* doing any other Stage 3 work. If not, the app simply stays in Stage 1/2 forever — fully functional for you and up to 100 known users.
4. Timeline: brand verification days-to-weeks; restricted-scope review + CASA typically **several weeks to a few months** end-to-end. The 100-user cap lifts and the unverified warning disappears when granted.
5. Scope hygiene: never add scopes beyond the two; any scope change re-triggers review.

### 3.2 Gate B: Play production listing

1. **Store listing assets:** app name (30 chars), short description (80), full description (4000 — write from the app-definition doc: lead with "your own Gmail, no third-party servers"), app icon 512×512 PNG, feature graphic 1024×500, ≥2 phone screenshots (8 max; take from real device per screen-guides doc), optional promo video.
2. **Forms recap** (mostly done in Stage 1, now they're load-bearing): content rating, data safety (declare: no data collected by developer; data transmitted to Google only — and **redo this form if/when ads activate**, ad SDKs collect device data), ads declaration (update when ads go live), target audience.
3. **Countries/regions:** start with a handful you can support; expand later.
4. **Production → Create release** → upload `.aab` → review summary → **Start rollout to production**. First production review can take up to ~7 days; subsequent updates are usually faster. Use **staged rollout** (e.g., 10% → 50% → 100%) once installs matter.

### 3.3 Monetization activation (when chosen — see master plan §5)

- **Tip-jar:** Play Console → **Monetize → In-app products** → create 2–3 one-time products; requires a linked payments profile (tax/banking forms — allow days). Implement with Play Billing Library behind the already-reserved Settings entry. Google's fee at small scale is 15%.
- **External donate link:** the Payments policy has a donations exemption, and the **March 2026 US policy change** loosened external-payment steering for US users — but the details are jurisdiction-specific and in flux. **Re-read the Payments policy on the day you implement**, and keep the link a pure no-reward donation.
- **Ads:** add the SDK behind the `AdProvider` interface, update Ads declaration + Data safety, add an ads privacy disclosure to the policy page, re-check APK size budget.

### 3.4 Post-launch obligations checklist

- [ ] Annual CASA re-assessment (calendar it from the LOA date — lapsing kills Gmail access for all users)
- [ ] Target API level bumps (every year around Aug 31; API 36 from 2026-08-31)
- [ ] Play policy inbox: respond to policy emails fast — apps get removed for ignored deadlines
- [ ] Keep privacy policy in sync with actual behavior (esp. after ads)
- [ ] Upload key hygiene: keystore backed up, passwords managed

---

## Quick reference — the three stages side by side

| | Stage 1 Internal | Stage 2 Closed | Stage 3 Production |
|---|---|---|---|
| Audience | You (+≤100) | 12+ real testers, 14 days | Public |
| Play review | Minimal | Standard | Full (up to ~7 days) |
| OAuth state | Testing or unverified-production (100-user cap) | Same | Verified: brand + scopes + CASA |
| Recurring cost | $0 | $0 | CASA ~$0.5k–5k+/yr |
| Purpose in our plan | Daily personal use (A7) | Production-access gate (A8) | Public launch (A9) |

## Sources (verified 2026-07-04)

- Play Console signup & account types: <https://play.google.com/console/signup>
- Target API level requirement (35 now; 36 from 2026-08-31): <https://developer.android.com/google/play/requirements/target-sdk> · <https://support.google.com/googleplay/android-developer/answer/11926878>
- Closed testing 12 testers/14 days (personal accounts): <https://www.testerscommunity.com/google-play-closed-testing> · <https://primetestlab.com/blog/google-play-changed-20-to-12-testers>
- Restricted-scope verification: <https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification>
- CASA / security assessment (annual, Tier 2): <https://support.google.com/cloud/answer/13465431> · <https://deepstrike.io/blog/google-casa-security-assessment-2025>
- Unverified apps / 100-user cap / testing-mode limits: <https://support.google.com/cloud/answer/7454865> · <https://support.google.com/cloud/answer/15549945>
- Payments policy + donations exemption: <https://support.google.com/googleplay/android-developer/answer/10281818>
- March 2026 billing-choice change (US): <https://android-developers.googleblog.com/2026/03/a-new-era-for-choice-and-openness.html>
