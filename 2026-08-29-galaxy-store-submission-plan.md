# Samsung Galaxy Store — Submission Plan

**Created:** 2026-08-29
**Status:** PLANNING. Nothing submitted, no account registered.
**Updated 2026-08-29:** both decisions in section 2 are taken and executed - the OAuth
strip (2.1a) and the 2.0.0 submission version (2.2) shipped together as
[v2.0.0](https://github.com/harshalambani/ChatMailSync/releases/tag/v2.0.0), commit
`86630aa`. Sequence steps 1 and 2 are done; the phase now starts at step 3.
**Builds on:** `2026-08-02-android-store-distribution-phase.md` (store shortlist, ruled-out
stores, and the signing trap are settled there and are not re-argued here). That document is a
historical record and is not edited; this one carries what has changed since.

---

## 1. What has changed since the 2026-08-02 phase plan

| Then | Now |
|---|---|
| `applicationId = com.wamailsync.app` | **`com.chatmailsync.app`** — fixed forever from first publication |
| Privacy policy: not written | **Published** at `https://chatmailsync.ambani.tech/privacy.html` (last updated 6 Aug 2026) |
| `appinfo.ini` stale at "WAGmail Sync" 1.0.0 | Current: Chat Mail Sync, **2.0.0** (`versionCode` 31) |
| THE GATE: "end-to-end IMAP delivery verified on device" | **Cleared.** Verified repeatedly on `RZCY81Q6WZV` across v1.x; 17 minor releases since |
| Icons: 512-ceiling noted, no store 512 | Unchanged — still no `play_store_icon_512.png`, still a 512 source ceiling |

The gate that held this phase back is open. What remains is asset work and two decisions.

---

## 2. Decisions needed before any upload

### 2.1 DECIDED 2026-08-29 - option (a), the OAuth path is stripped

> **Decision (aj3.3):** **(a) Strip it.** Shipped in v2.0.0, commit `86630aa` (PR #43), on
> **both** platforms in one branch - the Android-only version was refused, because the
> data-safety declaration follows what ships rather than what the UI reaches, and
> `PLATFORM-PARITY.md` exists to refuse exactly that divergence.
>
> Gone: `play-services-auth` from `build.gradle.kts`, the three Google pip packages from
> `requirements.txt` and the lock, every OAuth path in `src/mail_client.py`, `gui.py`,
> `gui_worker.py`, `src/config.py` and the eleven Kotlin screens and workers, plus the
> five stale Google hidden imports in `chat-mail-sync.spec`. The backend selector is
> removed from both front ends rather than reduced to a one-entry dropdown.
>
> The "strands the one existing OAuth user" cost was paid deliberately, not silently: a
> **one-time** notice on first start after the upgrade, on both platforms, tells that user
> the route is gone and asks for an app password. `config.is_legacy_oauth_user()` is the
> single shared test both front ends ask. `sync_state.db` is untouched, so nothing already
> archived is re-sent.
>
> It is reversible on paper only: `docs/RESTORING-OAUTH.md` carries the per-file removal
> tables and `git revert 86630aa`, but the Google Cloud project, its OAuth client and
> `auth/credentials.json` were never in this repository - restoring the code does not
> restore the ability to sign in.
>
> **Effect on this phase:** every "does it collect / does it access a Google account"
> question in the store declaration is now genuinely **no**, with no code shipping that
> could contradict it. The APK also drops from 22.4 MB to 22.1 MB, and the Windows
> installer from 25.7 MB to 12.7 MB.

The original argument, kept because it is what a later reviewer question is answered with:


`play-services-auth:21.6.0` is still a dependency, and the OAuth code is live in
`MainActivity.kt`, `WatchFolderWorker.kt`, `MailAccountScreen.kt` and `SettingsScreen.kt`. It is
not dead code: `2026-08-12-demote-oauth-plan.md` was implemented, so OAuth is **gated, not
removed** - `AppPrefs.isOauthVisible()` / `config.oauth_visible()` show it only to someone who
already chose it, already has a token, or has set the advanced unlock flag. A fresh install from a
store therefore never sees it.

Why that still matters at a store, and did not before:

- A store's data-safety / permissions declaration is made against what the app *can* do, not what
  a fresh install happens to show. "No Google account access" while shipping live `GoogleAuthUtil`
  calls is the kind of mismatch a reviewer can fail the app on.
- It is also the CASA-audit exposure that Road C was abandoned over. A *published* app with a
  reachable OAuth path re-opens that question with a third party involved.
- It is dead weight for every store user, in a 22.4 MB APK.

Options:

- **(a) Strip it for the store build.** Smallest APK, simplest honest declaration, finishes what
  the demotion started. **Cost is not just code:** the gate exists specifically so an existing
  OAuth user is not trapped, and removing it strands anyone still on that backend. Nobody else is
  using the app today, so the blast radius is one known user - but the decision has to be taken
  deliberately, not as a side effect of a store checklist.
- **(b) Keep the gate and declare it honestly.** No code work. The declaration has to say the app
  can use a Google account, which is true and slightly awkward for an app whose whole pitch is
  that nothing goes anywhere near the developer.

Recommendation: **(a)**, done as its own change with its own device verify, *before* the store
work - and only after confirming the desktop side has no live OAuth user either.

### 2.2 DECIDED 2026-08-29 - submit 2.0.0

> **Decision (aj3.4):** **2.0.0**, and it is already released - `versionCode` 31,
> `versionName` 2.0.0, `appinfo.ini` PackageVersion 2.0.0.0, bumped on both platforms in
> the same commit per the parity rule. The store's first listing will read 2.0.0.



The plan's own backlog has a **v2.0.0 confidence bump** queued ahead of the store phase. A store
listing's first version number is the one strangers see. Either ship 2.0.0 as the submission build,
or accept that the first public listing reads 1.17.x. Recommendation: submit **2.0.0**, so the
store's first entry is the confidence marker rather than a mid-series patch.

---

## 3. Assets to produce

Galaxy Store's Seller Portal asks for all of these before it will accept a binary. These
are now the whole of the remaining work, and they are all against the **2.0.0** build that
is already published - no further code change is queued ahead of them.

| Asset | Spec | Status |
|---|---|---|
| App icon | 512x512 PNG, no alpha edge tricks | **TODO** — derive from `portable/App/AppInfo/appicon_1024.png`; a 512 downscale is honest, the 1024 is an upscale |
| Screenshots, phone | min 4, 16:9 or 9:16, ≥1080 on the long edge | **TODO** — capture on `RZCY81Q6WZV`; Home, Import picker, Sync log, Mail account, Settings |
| Feature graphic / banner | 1024x500 | **TODO** — needs artwork, not a screenshot |
| Short description | ~80 chars | **TODO** |
| Long description | ~4000 chars | **TODO** — must state the app-password onboarding cliff plainly (§6 of the 2026-08-02 plan) |
| Privacy policy URL | public https | **DONE** — `chatmailsync.ambani.tech/privacy.html` |
| Content rating | questionnaire | Trivial — the app has no content |
| Support email | public | **DECIDE** — the personal address, or a new one |

> **Note:** the 16px icon problem from the earlier plan is a Windows `.ico` concern only. Store
> icons never render that small; the queued "simplified 16/32px cut" is independent of this phase.

---

## 4. Sequence

1. ~~**§2.1 OAuth strip** (its own PR, its own device verify).~~ **DONE 2026-08-29** -
   PR #43, squashed to `86630aa`. Device verify of the one-time legacy notice is the single
   loose end: the phone was not attached at release time, and it is the only path that
   cannot be exercised on a fresh install.
2. ~~**v2.0.0 release** on both platforms, per the usual batch rules.~~ **DONE 2026-08-29** -
   tag `v2.0.0`, three assets published (APK + `.paf.exe` + `.zip`).
3. **Samsung Developer account** — free registration, then seller registration for free apps.
   Individual seller: expect identity verification and a few days' lead time. Start this in
   parallel with step 1, since it is a queue, not work.
4. **Assets** (§3) against the 2.0.0 build.
5. **Upload** `app-release.apk`, signed with `android/app/release.jks` — the **own-key-everywhere**
   option settled in the 2026-08-02 plan §3. Do not let any store re-sign.
6. **Declarations** — content rating, target age, data safety. All easy while §2.1 is done and the
   answer to every "does it collect" question is genuinely no.
7. **Submit, then wait.** After this, "build, sideload, check the mailbox" stops being how a fix
   reaches a user. Every fix goes through review.

---

## 5. Constraints carried forward, unchanged

- **arm64-v8a only** (`defaultConfig.ndk.abiFilters`). The store will report reduced device
  coverage. Adding `armeabi-v7a` back would grow the APK by roughly the Chaquopy payload again;
  not worth it for the audience.
- **minSdk 24 / targetSdk 36.** Stores run an annual target-SDK treadmill; once listed, expect a
  forced bump roughly yearly.
- **`release.jks` is irreplaceable infrastructure** and already load-bearing (public beta APKs are
  signed with it). Backup procedure lives in `CREDENTIALS-BACKUP.md`. It stays gitignored.
- **The app-password onboarding cliff is real** and the listing copy must be honest about it: a
  store visitor did not build this app and will meet an IMAP app password on the second screen.
