# Demoting Gmail OAuth to a hidden advanced option - plan

Target release: **v1.6.0**. Written 2026-08-12, answering the seven deliverables
requested by `2026-08-10-demote-oauth-plan-prompt.md`. Planning only - no code
in this document is committed anywhere yet.

**Precondition confirmed 2026-08-12.** The restricted `gmail.insert`
verification was not completed: the recurring paid CASA AL1 assessment was
declined, Google confirmed there is no sponsorship or workaround for restricted
scopes, and their scopeless "Brand Verification" offer was declined because a
scopeless flow cannot insert mail. The OAuth client remains in **Testing**
publishing status.

**Demote, do not delete.** The OAuth transports, `setup_auth.py`, `GMAIL_SCOPES`,
the Google dependencies, the Google Cloud project and the OAuth client all stay
exactly as they are. IMAP remains `DEFAULT_MAIL_BACKEND`. Nothing above the
transport Protocol changes. What changes is *visibility*: a person who has never
used OAuth should never be offered it.

---

## 0. What is already true (read from the code, 2026-08-12)

Worth stating first, because a surprising amount of the work is already done and
the plan is smaller than the prompt assumed.

0.1 `src/config.py:109 resolve_mail_backend()` already pins an existing OAuth
user: an explicit saved value wins, otherwise an existing `TOKEN_FILE` returns
`gmail_oauth`, otherwise `DEFAULT_MAIL_BACKEND` (= IMAP). The upgrade guard the
prompt asks for **already exists** and does not need redesigning - it needs
extending to also govern *visibility*.

0.2 `AppPrefs.resolveMailBackend()` (`AppPrefs.kt:156`) mirrors it exactly:
saved value, else a non-null `getConnectedAccountEmail()`, else IMAP. The two
are already documented as mirrors of each other.

0.3 The choice is surfaced in exactly **two** places, one per platform, and they
share their wording deliberately:
  - desktop `gui.py:1823 _BACKEND_LABELS` + the `CTkOptionMenu` at `gui.py:2366`
  - Android `MailAccountScreen.kt:66 BACKEND_LABELS` + its dropdown

0.4 `gui.py:2494 _warn_oauth_is_limited()` already fires a one-time modal when a
user actively picks OAuth, explaining Testing status, the 100-test-user cap and
the 7-day consent expiry. **Android has no equivalent** - an existing parity gap,
and one this release closes for free by removing the entry point entirely.

0.5 `is_gmail_mailbox()` (`config.py:163`) is deliberately *wider* than "backend
== gmail_oauth", because most IMAP users here point at `imap.gmail.com`. Nothing
in this change may narrow it: Gmail-mailbox behaviour is orthogonal to how we
authenticate, and conflating them is how the label-vs-folder bugs happen.

0.6 **`android/app/src/test` and `src/androidTest` do not exist.** There is no
Kotlin test infrastructure of any kind. This is the single biggest constraint on
deliverable 5 and it drives the design in section 3.

---

## 1. Phased plan

### P-a. The gate, in shared config (both languages, same commit)

The decision "is OAuth visible to this person?" becomes one named predicate per
language, defined next to `resolve_mail_backend` / `resolveMailBackend` so the
two cannot drift - the same reasoning that put those two side by side already.

Crucially, each is written as a **pure function of three plain values**, with a
thin platform wrapper that reads those values:

```python
# src/config.py
def oauth_is_visible(saved_backend, token_exists, unlocked):   # pure
def oauth_visible(saved: dict) -> bool:                        # wrapper
```

```kotlin
// AppPrefs.kt
fun oauthIsVisible(savedBackend: String?, connectedEmail: String?, unlocked: Boolean): Boolean  // pure
fun isOauthVisible(context: Context): Boolean                                                    // wrapper
```

The purity is not stylistic. `AppPrefs` needs a `Context` for SharedPreferences,
and with no Kotlin test source set a `Context`-dependent function is untestable
without adding Robolectric. A pure function taking three values is testable by an
ordinary JVM unit test, which is the cheapest way to get the parity assertion
that deliverable 5 needs.

**Visible when any of:**
  a. the saved backend is already `gmail_oauth` - the user chose it;
  b. evidence of prior OAuth use exists - `token.json` on desktop, a non-null
     connected account email on Android;
  c. the advanced unlock flag is set.

**The latch.** The moment (a) or (b) is true, write the unlock flag. Without
this there is a trap: an OAuth user switches to IMAP to try it, the option
vanishes behind them, and they cannot switch back. Latching costs one write and
removes the trap entirely.

### P-b. Desktop - UI and CLI

The dropdown builds its values from the predicate instead of from all of
`_BACKEND_LABELS`. When OAuth is not visible the menu has one entry and the row
collapses to a static label - a one-item dropdown is worse than no dropdown.
`_BACKEND_LABELS_REV`'s fallback to `DEFAULT_MAIL_BACKEND` on an unrecognised
label already protects the Save path and needs no change.

`_warn_oauth_is_limited()` stays. It now only ever fires for someone who has
deliberately unlocked the option, which is exactly who should still see it.

CLI: **nothing to change** - see 7.1. `cli.py` never selects a backend; it reads
the settings file the GUI wrote. The env-var override therefore exists for
headless convenience only, not as a gate the CLI has to enforce.

Unlock surface: `self._version_label` at `gui.py:2144`, bound on `<Button-1>`.

### P-c. Android parity - same batch, not a follow-up release

Same predicate, same collapse-to-static-label behaviour in `MailAccountScreen`,
plus the unlock gesture (section 3). `SyncWorker` and `WatchFolderWorker` are
untouched: they receive `KEY_MAIL_BACKEND` and must keep honouring
`gmail_oauth`, because a latched existing user still runs that path.

### P-d. Copy and docs

The demotion is not real until the writing stops presenting OAuth as a peer.

`README.md` first paragraph currently ends *"Gmail (OAuth) and IMAP ... are both
supported backends"* - exactly the peer billing this removes. Rewrite to lead
with IMAP as the way the app connects, with OAuth named only as a legacy path.
Then `portable/help.html`, `HelpScreen.kt:61` and `:101` (both already describe
OAuth's limits, so they need re-scoping rather than rewriting), and the release
notes.

---

## 2. Files, per phase

**P-a**
- `src/config.py` - the predicate, the flag constant, its default in the
  settings dict
- `android/app/src/main/java/com/wamailsync/app/AppPrefs.kt` - mirror predicate,
  `KEY_OAUTH_UNLOCKED`, getter/setter

**P-b**
- `gui.py` - `_BACKEND_LABELS` consumption at `:2361`/`:2367`, the row-collapse,
  `_on_backend_changed` at `:2472`, the settings-defaults dict at `:106`
- `gui_worker.py` - only if it reaches for the label map; the transport build is
  keyed off the resolved backend and should not change
- `cli.py` - **no change** (7.1)
- `setup_auth.py` - **no change**; it stays runnable for a latched user

**P-c**
- `MailAccountScreen.kt` - `BACKEND_LABELS` consumption, dropdown, static-label
  fallback
- `MainActivity.kt` - the mail-account summary line (`:882`, `:915`)
- `SettingsScreen.kt` - the existing version row at `:214`-`:224` gains the
  tap-count unlock gesture
- `SyncWorker.kt`, `WatchFolderWorker.kt` - **no change**, asserted not assumed

**P-d**
- `README.md`, `portable/help.html`, `HelpScreen.kt`, release notes

**Not touched, deliberately:** `src/gmail_client.py`, `src/mail_client.py`,
`GMAIL_SCOPES`, the Google dependencies, the Cloud project.

---

## 3. Gate mechanism - recommendation

**Recommended: a persisted flag, opened by a version-row tap/click gesture on
both platforms, plus an environment-variable override on desktop only.**

The prompt floated `WAMAILSYNC_ENABLE_OAUTH=1`. It is the right shape for
desktop - `WAMAILSYNC_ROOT` already establishes that prefix as this app's env
convention - but **Android has no user-settable environment variable**, so an
env-only gate cannot reach the phone at all. That is disqualifying on its own
under the parity rule.

Rejected alternatives:

- **Debug-build-only gate (`BuildConfig.DEBUG`).** Simplest to write, but it
  means OAuth cannot be reached on the shipped APK for any reason - not to
  diagnose a latched user's problem, not to demonstrate it. Recovery would
  require building and sideloading a debug APK. Too brittle for the one path we
  are keeping precisely *because* someone might still need it.
- **Hand-editing SharedPreferences.** Not reachable on a release build without
  root (`adb run-as` requires a debuggable app), so it is the debug-build gate
  wearing a disguise.

The tap gesture is the standard Android idiom (it is how the platform's own
developer options are unlocked), it works identically on a release build, and it
is undiscoverable by accident. Mirroring it as a click on the desktop version
label costs almost nothing and keeps the two apps describing the same capability
the same way - the parity rule asks for functional parity with wording as close
as practical, and here even the gesture can match. The desktop env var stays as
a convenience for headless and CLI use, where there is no version label to click.

Once unlocked, the flag persists. There is no re-lock UI; clearing app data or
deleting the settings file is the reset, and that is proportionate for a
maintainer-facing switch.

---

## 4. "Existing OAuth user keeps working" - the design, both languages

The rule: **visibility is gated; behaviour is not.** Nothing in this change may
alter which transport a resolved backend produces.

Python, at `src/config.py`:

- `resolve_mail_backend()` is **unchanged**. It already returns `gmail_oauth`
  for a settings file with no `mail_backend` key but an existing `token.json`.
- `oauth_visible(saved)` returns True when `saved["mail_backend"] ==
  gmail_oauth`, or `TOKEN_FILE.exists()`, or the unlock flag is set. Note it
  reads `TOKEN_FILE` from module globals at call time, for the same reason
  `resolve_mail_backend` does - `_apply_root()` rebinds it when
  `WAMAILSYNC_ROOT` moves the storage root.

Kotlin, at `AppPrefs.kt`:

- `resolveMailBackend()` is **unchanged**.
- `isOauthVisible(context)` returns True when `getSavedMailBackend() ==
  MAIL_BACKEND_GMAIL_OAUTH`, or `getConnectedAccountEmail() != null`, or the
  unlock flag is set.

`token.json` and the connected-account email are the same evidence expressed in
each platform's own storage - the correspondence the existing mirrored comments
already assert.

> **Warning:** these two must change together or one platform is silently
> wrong - the same hazard the prompt flags for `resolve_mail_backend` /
> `resolveMailBackend`. A comment on each pointing at the other is not
> sufficient; the parity test in section 5 is what actually enforces it.

---

## 5. Test strategy

Three cases, both platforms:

- **(a) fresh user never sees OAuth** - no saved backend, no token/connected
  account, flag unset -> predicate False, dropdown has one entry
- **(b) gated maintainer does** - flag set -> predicate True, dropdown has both
- **(c) connected OAuth user unaffected** - token/connected account present ->
  predicate True, `resolve_mail_backend` still returns `gmail_oauth`, and the
  transport built is still the OAuth transport
- plus **(d) the latch** - a user in state (c) who switches to IMAP still has
  the option available afterwards

**Python.** Extends what exists. `tests/test_config.py` covers the predicate and
the latch; `tests/test_gui_backend.py` already drives `_backend_var` /
`_on_backend_changed` directly and already asserts label-map invariants
(`:1080`-`:1085`), so the dropdown-contents assertions belong there.
`tests/test_oauth_recovery.py` covers case (c) end to end.

**Kotlin - this is the real gap.** There is no `src/test` or `src/androidTest`
source set. Two options:

- **Recommended:** add a minimal JVM unit-test source set (`src/test`, JUnit
  only, no Robolectric) and test `oauthIsVisible(savedBackend, connectedEmail,
  unlocked)` - the pure three-argument function. It needs no `Context`, so it
  needs no Android test runtime, and it is the function that actually encodes
  the decision. This is why P-a splits pure from wrapper.
- Rejected: Robolectric or instrumented tests, to test one boolean. The setup
  cost and build-time cost are out of proportion to the surface.

The thin `Context` wrappers stay untested by machine and are covered by the
device checklist below. That is an honest limit, stated rather than papered
over.

**Cross-language parity test.** A Python test asserting that the Kotlin source
contains the same three conditions is the kind of brittle string-matching that
rots. Instead: a single table of the four cases above, written once in this
document, executed as Python unit tests **and** as the device checklist, with
the Kotlin unit test covering the same four rows. If the rows diverge, the
review catches it.

---

## 6. Parity checklist (run on both before merge)

| # | Check | Windows | Android |
|---|---|---|---|
| 1 | Fresh profile: no OAuth entry anywhere in the mail-account UI | | |
| 2 | Backend row shows a static label, not a one-item dropdown | | |
| 3 | Unlock gesture reveals both options | | |
| 4 | Wording of both backend labels identical across platforms | | |
| 5 | Latched OAuth user: option present, sync runs on the OAuth transport | | |
| 6 | Switch latched user to IMAP, reopen: OAuth still offered | | |
| 7 | Help/README text no longer presents OAuth as a peer backend | | |
| 8 | `is_gmail_mailbox` behaviour unchanged for an IMAP-to-Gmail user | | |

Row 8 is not incidental: it is the one place where narrowing a check would
silently change label-vs-folder behaviour for the *majority* configuration.

---

## 7. Open questions

7.1 **CLOSED - `cli.py` has no backend argument.** Its only two matches are
`_load_mail_backend_settings` and `is_gmail_mailbox`, both at `:222`-`:226`,
used to word the mailbox-clear warning correctly. The CLI reads whatever the
settings file already says and never selects a backend, so it cannot be a
bypass. **P-b's CLI item is dropped** - there is nothing to change in `cli.py`.
What remains of the env-var override is a convenience for headless use, not a
gate the CLI has to enforce.

7.2 **CLOSED - the desktop unlock surface exists.** `gui.py:2144` builds
`self._version_label` from `version_label()` and packs it at `:2148`. A
CTkLabel binds `<Button-1>` directly, so the click handler attaches there with
no new widget.

7.3 **CLOSED - the Android version row exists.** `SettingsScreen.kt:214`-`:224`
renders `"WA Mail Sync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"`.
The tap gesture attaches to that existing row - no new UI is added, which is the
right outcome for a release otherwise about removing something.

> **Note:** 7.1-7.3 together mean the gate needs **no new UI on either
> platform** and **no change to `cli.py`**. Both unlock surfaces already exist
> and already show the version, which is also the natural place for a
> maintainer-facing gesture to live.

7.4 **Should the unlock flag be per-profile or global on desktop?** It lives in
`.settings.json` under the resolved root, so `WAMAILSYNC_ROOT` already makes it
per-profile. Probably correct; noting it because it is a consequence rather than
a decision.

7.5 **Ordering against v1.9.0.** This release touches `README.md`,
`HelpScreen.kt` and `portable/help.html` - three of the same files the rename
sweeps. Doing the copy work twice is wasteful, but merging the two releases
makes both harder to verify. Recommendation: keep them separate, and in P-d
change only the sentences that make OAuth a peer, leaving all other wording for
the rename pass to handle in one sweep.
