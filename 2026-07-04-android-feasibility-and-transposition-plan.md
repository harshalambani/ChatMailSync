# WA Chat Sync to Gmail — Android Feasibility & Transposition Plan

**Version:** 1.0 (2026-07-04)
**Status:** Approved approach: Kotlin + Jetpack Compose UI with the existing Python core embedded via Chaquopy. Personal-use distribution first; public Play Store release as a later phase.
**Companion docs:**
- [2026-07-04-android-app-definition.md](2026-07-04-android-app-definition.md) — what the app is and is not
- [2026-07-04-android-screen-guides.md](2026-07-04-android-screen-guides.md) — screen-by-screen UI spec
- [2026-07-04-playstore-publishing-sop.md](2026-07-04-playstore-publishing-sop.md) — publishing SOP (for later)

---

## 1. Feasibility Verdict

**Transpose or rebuild? Answer: hybrid transpose — and it is feasible.**

The codebase splits cleanly:

| Layer | Lines | Android fate |
|---|---|---|
| `src/` core (parser, sync, state, renderer, media, config) | ~2,700 | **Reuse** (runs unchanged or near-unchanged under Chaquopy) |
| `gui.py` + `gui_worker.py` (Tkinter) | ~1,250 | **Rebuild** in Kotlin/Jetpack Compose |
| `cli.py`, PyInstaller/PowerShell build scripts | ~300+ | **N/A on Android** (Windows keeps them) |
| OAuth flow (`InstalledAppFlow` in `gmail_client.py`, `setup_auth.py`) | ~100 | **Replace** with Android-native OAuth (AppAuth), token handed to Python |

**Dual Windows + Android mode? Answer: yes.** The Windows app keeps working exactly as today. Both platforms consume the same `src/` package from one repository; only the front-end and a thin "platform adapter" differ. iOS is explicitly out of scope (Chaquopy is Android-only — an iOS port would be a genuine rebuild).

### File-by-file reuse map

| File | Reuse % | Changes needed for Android |
|---|---|---|
| `src/parser.py` | 100% | None. Pure stdlib + `dateutil`. Format-cascade logic is platform-agnostic. |
| `src/state.py` | 100% | None. `sqlite3` is in Android's bundled Python. DB path comes from config. |
| `src/media_extractor.py` | 100% | None. `zipfile`/`mimetypes` are stdlib. |
| `src/html_renderer.py` | 100% | None. Pure string/MIME building. |
| `src/sync_manager.py` | ~98% | None functionally; verify `shutil.move` targets stay inside app-private storage (they will — paths come from config). |
| `src/config.py` | ~90% | Generalize root resolution: today `WAGMAIL_ROOT` env var or `Path(__file__).parent.parent`. Add a third source: an explicit `set_root(path)` call so Kotlin can inject Android's `Context.getFilesDir()`. |
| `src/gmail_client.py` | ~70% | Two changes: (1) remove `InstalledAppFlow` — Kotlin does OAuth and injects tokens; (2) replace `googleapiclient.discovery.build()` with direct Gmail REST calls (see §4, size budget). Label/thread/insert/dedup logic is untouched. |
| `gui.py`, `gui_worker.py` | 0% | Rebuilt as Compose screens + Kotlin ViewModel/WorkManager. The screen spec doc maps every existing GUI feature to an Android screen. |
| `cli.py`, `setup_auth.py`, `*.ps1`, `wa-chat-sync.spec` | — | Windows-only; unchanged. |

**Net: roughly 60–65% of the total codebase carries over as-is, and the part that carries over is the hard part** (date-format cascade, dedup, incremental sync, MIME assembly). The rebuild portion is UI, which Android forces anyway.

---

## 2. Target Architecture

```
┌────────────────────────────── Android app ──────────────────────────────┐
│  Kotlin / Jetpack Compose (Material 3)                                  │
│  ├── Screens (see screen-guides doc)                                    │
│  ├── ViewModels  ── UI state, progress events                           │
│  ├── AuthManager ── OAuth2 via AppAuth (Custom Tabs), token store in    │
│  │                  EncryptedSharedPreferences / Android Keystore       │
│  ├── ImportManager ─ receives WhatsApp "Export chat" share intents      │
│  │                  + SAF file picker; copies into data/inbox/          │
│  ├── SyncWorker  ── WorkManager: runs sync off the UI thread,           │
│  │                  survives process death, posts notifications         │
│  ├── AdSlot      ── empty composable + config flag (future, §5.1)      │
│  ├── ThemeRegistry ─ Material 3 theming hook (future, §5.3)            │
│  └── GlanceWidget ─ home-screen widget entry point (future, §5.4)      │
│                                                                          │
│  ── Chaquopy bridge (Python 3.13 runtime, MIT-licensed, free) ──        │
│                                                                          │
│  Python core (same files as Windows)                                     │
│  ├── src/parser.py · state.py · media_extractor.py · html_renderer.py  │
│  ├── src/sync_manager.py                                                │
│  ├── src/gmail_client.py  (REST transport; tokens injected)             │
│  ├── src/config.py        (root injected by Kotlin)                     │
│  └── android_api.py       (NEW: thin façade Kotlin calls — sync(),      │
│                             status(), reset(), progress callback)       │
└──────────────────────────────────────────────────────────────────────────┘
Storage: Context.getFilesDir()/wagmail/{auth,data/{inbox,processed},sync_state.db}
         — app-private, no storage permissions needed, auto-removed on uninstall.
```

### Key design decisions

1. **OAuth moves to Kotlin.** `InstalledAppFlow` spins up a localhost server + desktop browser — impossible on Android. Use **AppAuth-Android** with an **Android-type OAuth client** (package name + SHA-1 signing fingerprint registered in Google Cloud Console). Kotlin performs the flow in a Custom Tab, stores the refresh token in EncryptedSharedPreferences, refreshes access tokens, and passes a bearer token string into Python before each sync. `gmail_client.py` gains a `set_token(access_token)` entry point; everything downstream is unchanged.
   - Note: Android OAuth clients don't use a `credentials.json` secret (public clients). The desktop app keeps its Desktop client; the Android app gets a sibling Android client in the *same* Cloud project — one consent screen, one verification story later.
2. **Drop `google-api-python-client`, call Gmail REST directly.** The discovery client bundles megabytes of static discovery documents and pulls `httplib2`, `uritemplate`, `protobuf` chains. The app uses exactly six operations: `users.labels.list/create`, `users.messages.insert`, `users.threads.get`, and (optionally) `users.messages.list`. Rewriting the transport layer of `gmail_client.py` as ~100 lines over `requests` (or stdlib `urllib`) cuts APK size sharply and drops the whole google-client dependency tree on Android. **Do this refactor in the shared core behind a transport interface so Windows can keep or adopt it too** (recommended: adopt — one code path).
3. **Share-intent ingestion replaces the drop folder.** WhatsApp → chat → Export chat → share sheet → this app. The app registers an intent filter for `text/plain`/`application/zip` `SEND` intents, lands the file in `data/inbox/`, and offers "Sync now". A SAF file picker covers exports saved to Downloads/Drive. This is a UX upgrade over the desktop drop folder.
4. **WorkManager for the sync run.** Long syncs must survive screen-off and process death. `SyncWorker` calls the Python façade, relays progress via `setProgress` + a foreground notification. Maps 1:1 to what `gui_worker.py` does with threads today.
5. **SQLite state DB is shared logic, not shared data.** Each device has its own `sync_state.db`. Dedup across devices still works at the Gmail level the same way it does today (Message-ID hashing), so syncing the same export from phone and PC will not double-post — document this in testing.

---

## 3. Dual-Platform Repository Strategy

Single repo, restructured minimally so both front-ends import the same core:

```
wa-chat-sync/
├── core/                    # renamed from src/ (or keep src/ — see note)
│   └── ... (the 7 shared modules + android_api.py)
├── windows/
│   ├── gui.py, gui_worker.py, cli.py, setup_auth.py
│   ├── wa-chat-sync.spec, build_portable.ps1, sign_exe.ps1
├── android/
│   ├── app/ (Gradle module: Kotlin, Compose, Chaquopy plugin)
│   │   └── src/main/python/ → **symlink or Gradle sourceSet pointing at ../core**
│   ├── build.gradle.kts, settings.gradle.kts
└── docs/, README.md, ...
```

- Chaquopy's Gradle config supports `python { srcDirs }` — point it at `core/` so there is **one copy of the core, zero duplication**.
- Note: moving `src/` is optional. Lowest-risk alternative: leave `src/` where it is and have the Android Gradle sourceSet reference `../../src`. Decide at Phase A0; the plan assumes the low-risk option first, restructure later if it gets messy.
- Windows release cadence is unaffected; CI (if added later) runs the shared-core unit tests once for both platforms.

---

## 4. App Size Budget (explicit focus area)

The app's own logic is tiny; size is dominated by the Python runtime. Targets and levers:

| Lever | Effect |
|---|---|
| `abiFilters` → ship **arm64-v8a only** (add armeabi-v7a only if a real old-device need appears) | Biggest single win; each extra ABI duplicates the entire native runtime. |
| Drop `google-api-python-client` (+httplib2, uritemplate, cachetools, pyasn1 chain) for direct REST (§2.2) | Removes the largest Python dependency tree. Keep only `requests` + `certifi` (or pure stdlib `urllib` for zero extra deps) and `python-dateutil`. |
| Android App Bundle (.aab) + Play per-device delivery | Users download only their ABI/density split. Chaquopy docs note standard splits help less than usual, so pair with product-flavor ABI filtering per official guidance. |
| R8 minify + resource shrinking on the Kotlin side | Standard; Compose apps shrink well. |
| No bundled fonts/emoji; use system emoji rendering in HTML preview | Avoids MB-scale assets. |
| Audit `pip install` output at build time (Chaquopy prints installed package sizes) | Add a build-fails-if-over-budget check later. |

**Realistic expectation:** Chaquopy's Python runtime costs roughly 10–20 MB per ABI before your code. With one ABI, direct-REST transport, and AAB delivery, a **~20–30 MB download size** is a realistic target; without these levers it could exceed 60 MB. Set the budget gate at **≤35 MB download size** and track it every phase. If size ever becomes unacceptable, the fallback is porting the core to Kotlin (Phase F, not planned — the reuse map above is the porting spec if it ever happens).

---

## 5. Future-Feature Hooks (design room reserved now, built later)

### 5.1 Ads — abstraction slot, network decided later
- Define `AdSlot(placement: AdPlacement)` composable that renders nothing when `BuildConfig.ADS_ENABLED = false` (the default).
- Reserve placements now so layouts don't reflow later: bottom of Home screen, bottom of Chats list. **Never** on the Connect/auth screen (Google ads policy friction) and never inside sync-progress (interruption risk).
- When activated: add the chosen SDK (AdMob is the default candidate) behind a single `AdProvider` interface; declare ads in the Play *Data safety* + *Ads* forms; ads SDKs add ~3–6 MB — re-check the size budget then.

### 5.2 Donate — dual mechanism
- Reserve a **"Support the app"** item in Settings (spec'd in the screen guide).
- **Mechanism 1 — Play Billing tip-jar:** 2–3 one-time in-app products ("Small/Large tip"). Fully policy-safe everywhere; Google fee ~15%.
- **Mechanism 2 — external donate link** (UPI/BuyMeACoffee/PayPal): historically prohibited for digital-goods payments, but Google's **March 2026 US policy change** (post-Epic settlement) now permits guiding users to external payment systems for users in the US, and pure no-reward donations have an existing exemption. **Rule for the build:** link must be a plain donation with zero unlocked features, and re-verify the current Payments policy text at implementation time — this area is moving quarter to quarter.
- Both can coexist: tip-jar buttons + a small "other ways to support" link.

### 5.3 Themes/skins
- Build on Material 3 theming from day one: all colors/typography flow from a single `WagmailTheme` composable — no hardcoded colors in screens (this costs nothing now and is the whole trick).
- Day-one: light/dark/system + Material You dynamic color on Android 12+.
- Later: a `ThemeRegistry` (list of named `ColorScheme` sets, one being "WhatsApp-ish green") surfaced in Settings → Appearance; optionally a paid theme pack via the same Play Billing plumbing as 5.2.

### 5.4 Home-screen widget — sync status + quick-sync
- Implement later with **Glance** (Compose-based AppWidget API).
- Shows: last sync time, count of files waiting in inbox, one **Sync now** button that enqueues `SyncWorker` directly (no app launch needed) and an area that opens the app.
- Architecture room reserved now: `SyncWorker` is already invocable outside the Activity, and sync status is written to a small `DataStore` the widget can read. That's the only prerequisite — build it in any later phase.

---

## 6. Phased Roadmap (sized for Claude Code sessions)

Each phase is one or a few Claude Code sessions with a testable exit gate. Do them in order; A0 happens on Windows before any Android work.

| Phase | Work | Exit gate |
|---|---|---|
| **A0 — Core prep (Windows)** | (1) `config.py`: add `set_root()` injection; (2) `gmail_client.py`: split transport behind an interface, add direct-REST implementation + `set_token()`; (3) add `android_api.py` façade; (4) unit tests for parser/state/renderer if not present. **Windows app must still pass a full sync after this.** | Windows CLI + GUI sync works unchanged with refactored core. |
| **A1 — Android skeleton** | Android Studio project, Kotlin + Compose + Material 3, Chaquopy plugin (Python 3.13), `abiFilters arm64-v8a`, Gradle sourceSet → shared core, theme scaffold (§5.3), empty AdSlot (§5.1). | App builds; a button calls `android_api.ping()` and shows a Python-computed string. Record baseline APK size. |
| **A2 — Parse & state on device** | Wire ImportManager: share-intent + SAF picker → `data/inbox/`. Call parser via façade; show parsed chat summary. SQLite state read/write on device. | Share a real WhatsApp export from WhatsApp → app shows chat name, message count, date range. Dry-run works. |
| **A3 — OAuth** | Android OAuth client in the existing Cloud project (package + SHA-1). AppAuth flow in Custom Tab, EncryptedSharedPreferences token store, refresh handling, `set_token()` bridge. Add self as test user on the consent screen. | Connect button → Google consent → "Connected as <email>"; token survives app restart; Gmail `labels.list` succeeds from Python. |
| **A4 — End-to-end sync** | `SyncWorker` + foreground notification + progress relay to UI. Full pipeline: inbox → parse → dedup → insert → processed. Error surfaces (no network, token expired, quota). | A real chat export lands in Gmail under `WhatsApp/<Chat>` identically to a Windows-produced sync; re-sync pushes nothing (dedup proof); sync same file on PC → also nothing (cross-device dedup proof). |
| **A5 — Full UI** | All screens per the screen-guides doc: Home, Chats list, Chat detail (open Gmail thread, reset), Settings, Help. Polish empty/error/loading states. | Every Windows GUI feature has a working Android equivalent (checklist in screen doc). |
| **A6 — Hardening & size** | R8, size audit vs ≤35 MB gate, timezone notice, large-zip guardrails (`MAX_ZIP_DECOMPRESSED_BYTES` already exists), battery/Doze behavior, Android 15/16 (API 35, then 36 by Aug 31 2026) target check. | Release build signed, size within budget, 3 device/OS combos tested. |
| **A7 — Personal distribution** | Play Console developer account ($25 one-time) + **internal testing track** (≤100 testers, instant, no review friction) — this is the "personal use" mode. Sideload APK as interim option. OAuth consent screen stays in *Testing* with you as test user. **Caveat:** Testing-mode OAuth refresh tokens expire every 7 days → either re-consent weekly or move the consent screen to Production-unverified (100-user cap, "unverified app" warning, but durable tokens). Document both; pick after living with it. | App on your phone via Play internal track; sync used daily. |
| **A8 — Closed testing (pre-public)** | Per current policy for personal accounts: **12 opted-in testers for 14 consecutive days** (testers must actually engage) before production access can be requested. Recruit friends/family; fix what they hit. | Production access granted by Play. |
| **A9 — Public release (later, big step)** | Full SOP doc: store listing, data safety, content rating, **restricted-scope OAuth verification + annual CASA Tier 2 assessment** (real money: commonly ~$500–$5k+/yr depending on assessor; budget before committing), privacy policy + homepage + demo video. Activate donate (§5.2) and optionally ads (§5.1) here or later. | App live on Play Store. |

**Suggested Claude Code session grouping:** A0 (1–2 sessions) · A1+A2 (1–2) · A3 (1) · A4 (1–2) · A5 (2–3) · A6 (1) · A7+A8 (mostly non-coding) · A9 (SOP-driven).

---

## 7. Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| CASA/verification cost makes public release uneconomic for a free app | High (public phase only) | Personal-first plan defers it; ads/tip-jar revenue can be sized against CASA cost before committing to A9. Scopes are already minimal (`gmail.insert`, `gmail.labels`) — never widen them. |
| 7-day token expiry in OAuth Testing mode annoys daily use | Medium | A7 documents the two options; Production-unverified with 100-user cap is the likely resolution until A9. |
| APK size creep | Medium | §4 levers + hard budget gate each phase. |
| Chaquopy abandonment | Low | MIT open source since 12.x, active (17.x, Python 3.13, 2026); worst case the reuse map doubles as a Kotlin-port spec. |
| WhatsApp export format drift | Low | Same risk as Windows today; parser cascade is the defense, and it's shared code — one fix serves both platforms. |
| Play policy changes (testing rules, payments) | Medium | SOP doc flags every policy-dated claim; re-verify at each phase start. |
| Background sync killed by aggressive OEM battery managers | Medium | Foreground-service WorkManager + user guidance; syncs are user-initiated and short, so exposure is limited. |

---

## 8. Sources (verified 2026-07-04)

- Chaquopy open source / MIT since 12.0.1; current 17.x, Python 3.13: [chaquo.com/chaquopy](https://chaquo.com/chaquopy/), [Licensing](https://chaquo.com/chaquopy/license/), [GitHub](https://github.com/chaquo/chaquopy)
- Chaquopy size guidance (abiFilters, bundles/flavors): [chaquopy issue #448](https://github.com/chaquo/chaquopy/issues/448), [issue #618](https://github.com/chaquo/chaquopy/issues/618)
- Play closed-testing 12 testers / 14 days for personal accounts: [Testers Community guide](https://www.testerscommunity.com/google-play-closed-testing), [policy change note](https://primetestlab.com/blog/google-play-changed-20-to-12-testers)
- Target API level: 35 now; 36 from 2026-08-31: [Android Developers](https://developer.android.com/google/play/requirements/target-sdk), [Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- Restricted-scope verification + CASA annual reassessment: [Google Identity docs](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification), [Security Assessment help](https://support.google.com/cloud/answer/13465431?hl=en), [CASA overview](https://deepstrike.io/blog/google-casa-security-assessment-2025)
- OAuth Testing mode: 100-user cap, unverified warning, 7-day refresh-token expiry: [Unverified apps](https://support.google.com/cloud/answer/7454865?hl=en), [Manage App Audience](https://support.google.com/cloud/answer/15549945?hl=en)
- Payments policy, donations exemption, March 2026 US billing-choice change: [Payments policy](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en), [Android Developers Blog, Mar 2026](https://android-developers.googleblog.com/2026/03/a-new-era-for-choice-and-openness.html)
