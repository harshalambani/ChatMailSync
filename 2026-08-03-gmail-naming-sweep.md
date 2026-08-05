# "Gmail" / "WAGmail" Naming Sweep — Future To-Do

**Created:** 2026-08-03
**Status:** NOT STARTED — scoped only.
**Priority:** LOW. Cosmetic/correctness debt, no user-visible breakage. Do it in one deliberate
pass rather than opportunistically, so the judgement calls get made consistently.
**Related:** the "no longer Gmail-only" renaming phase (P2), `2026-08-02-pending-status.md` §B5.

---

## 1. Why

The app was Gmail-only until Road B (2026-07-30) moved it to IMAP + app password. IMAP is now the
**default** backend. The name "WA Mail Sync" and the package `com.wamailsync.app` already reflect
that, but the vocabulary underneath does not: 704 occurrences of "gmail" across 52 files, and
"wagmail" across 25 files. Some are still perfectly correct — the Gmail OAuth backend genuinely
still exists. Most are stale.

This is not a find-and-replace. It is a per-occurrence relevance judgement, which is exactly why it
needs its own pass.

---

## 2. Two traps — read before touching anything

### 2a. Frozen runtime identifiers — renaming these destroys user data

These contain the string "wagmail" and **must never be renamed**, in any sweep, ever:

| Identifier | Where | What breaks if renamed |
|---|---|---|
| `wagmail_prefs` | `AppPrefs.kt:8` — SharedPreferences file name | Every saved Android setting is orphaned |
| `wagmail_imap_key` | Keystore alias | The user's encrypted IMAP password becomes **permanently undecryptable** |
| `wagmail` | Python root dir name on Android | Existing inbox/processed/state paths are orphaned |

Already documented at `PLATFORM-PARITY.md:113`. A sweep that "helpfully" tidies these is a
data-loss bug, not a rename.

Windows has an equivalent: `WAGMAIL_ROOT` (see §B5 of the pending-status doc). Check whether it is
persisted anywhere or purely an env var before touching it — env-var-only is safe to rename, a
persisted path is not.

### 2b. Historical records must not be rewritten

`Completed/**` and the dated `2026-*.md` plan/brief/handoff docs are historical records of what was
decided and when. They said "Gmail" because the app *was* Gmail-only at the time — that is accurate
history, not stale text. **Do not rewrite them.** This alone removes a large share of the 704 hits:
`Completed/2026-05-27-architecture.md` (44), `2026-07-04-android-feasibility-and-transposition-plan.md`
(38), the two Road B subagent briefs (30 + 27), and so on.

The only legitimate edit to a historical doc is a *superseded* banner at the top, and one already
exists as precedent on `2026-07-04-playstore-publishing-sop.md`.

---

## 3. Categories to sort each hit into

1. **Still correct — leave.** The Gmail OAuth backend is real and still shipped:
   `AppPrefs.MAIL_BACKEND_GMAIL_OAUTH`, `GMAIL_SCOPES`, `google-api-python-client` usage,
   `labels_list()` as an actual Python method name, Gmail-specific IMAP host defaults
   (`imap.gmail.com`), and anything describing how to get a *Gmail* app password specifically.
2. **Stale generic — change to "mail" / "email".** Anything implying the app only talks to Gmail:
   user-facing copy, log lines, comments, variable names like `gmail_*` that are actually
   backend-agnostic. Recent example already fixed: `"Test connection (labels.list)"` and the
   `"Error calling labels_list()"` strings (2026-08-03).
3. **`wagmail` -> `wamail`.** Identifiers, file names, paths — **except** the frozen set in §2a.
4. **Historical — leave.** Per §2b.

---

## 4. Known specific items already on the books

- `portable/App/AppInfo/appinfo.ini` — `Description` still says "to Gmail". Inaccurate post-IMAP.
  Also flagged in the store-distribution doc §4.
- `docs/CNAME` is `wagmail.ambani.tech`. Renaming it is a **separate decision** — it needs DNS work
  and it is the privacy-policy URL the app-store listings will point at. Do not fold it into a
  code sweep.
- `docs/privacy.html` (21 hits) — this is the public privacy policy. Wording here is user-facing and
  store-visible; treat it as copy, not code.
- `help.html` (17 hits) — shipped user help, same treatment.
- `README.md` (15 hits).
- Windows build artifacts (§B5): `build_portable.ps1`, `wa-chat-sync.spec`, the `.bat` launcher,
  `WAGMAIL_ROOT`. Partly addressed on branch `rename/wamailsync-build-artifacts`.
- The public remote is `https://github.com/harshalambani/WAGMailSync.git`. Renaming a GitHub repo
  leaves a redirect, but it is a separate decision with its own blast radius.

---

## 5. Method

Run from the repo root:

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
git grep -Ioni "gmail"
git grep -Ioni "wagmail"
```

Suggested sequence:

1. Exclude `Completed/` and the dated `2026-*.md` docs from the working list first — that is the
   single biggest reduction and it is mechanical.
2. Exclude the §2a frozen identifiers explicitly, by hand, before any bulk edit.
3. Sort what remains into the four categories in §3, code first, then user-facing copy.
4. Code changes and copy changes are separate commits — they have different review needs.
5. Re-run the test suite (158 passing as of 2026-08-03) and `gradlew.bat assembleRelease`. Any string
   that turns out to be load-bearing will surface as a test failure rather than at runtime.
6. Windows and Android must land together — `PLATFORM-PARITY.md` makes head-to-head parity a written
   rule.

---

## 6. Scale, as of 2026-08-03

- `gmail`, case-insensitive: **704 hits across 52 files**
- `wagmail`, case-insensitive: **25 files**
- Heaviest code files: `src/mail_client.py` (63), `tests/test_gui_backend.py` (41), `gui.py` (38),
  `src/state.py` (31), `MainActivity.kt` (24), `src/config.py` (23)

`src/mail_client.py` topping the list is expected and mostly category 1 — it is where the Gmail
backend actually lives. Do not assume the biggest number is the biggest problem.
