# "Gmail" / "WAGmail" Naming Sweep — Future To-Do

**Created:** 2026-08-03
**Status:** DONE — 2026-08-06, branch `sweep/gmail-naming-final`. The scoping below is left as
written, for the record; see §7 at the bottom for what was actually done and what was left alone.
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

> **SUPERSEDED 2026-08-08 — all three were renamed.** The table below is left as
> written, for the record. Two things changed. First, the premise expired: these
> protect an *existing* install, and the only install was the test device, which
> was cleared for a fresh sync with a new app password — there was nothing left
> to orphan. Second, the "permanently undecryptable" claim in row 2 was simply
> wrong, and reading the code before acting is what caught it.
> `SecretStore.getSecret()` wraps its decrypt in a catch that clears the dead
> blob and returns null, which every caller already reads as "no password
> saved"; it was written for the Keystore-wiped and restored-to-another-device
> cases, and an alias rename lands on exactly that path. Nothing throws, nothing
> is unrecoverable, the user re-enters a password.
>
> Row 3 is the one with a real residue, and it is not the one this table warned
> about: renaming the python root leaves the old `filesDir/wagmail` tree — chat
> exports, `sync_state.db` — sitting on the device, referenced by nothing. The
> rename is only finished once app storage is cleared or the app reinstalled.
>
> New names: `wamail_prefs`, `wamail_imap_key`, `filesDir/wamail`. The reason it
> happened now rather than never is timing — after the Galaxy Store listing the
> same edit would silently wipe every user's settings on update, with no error
> to explain it. That made the pre-store window the last cheap one. The general
> caution stands for anything added to this class later: a storage identifier is
> not a display string, and the cost of renaming one is paid by users who
> already have data, not by the person doing the sweep.

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

---

## 7. What was actually done — 2026-08-06

Executed on branch `sweep/gmail-naming-final`, three commits (code / copy / docs), with the §5
gates: 171 tests passing and `gradlew.bat assembleRelease` producing an APK.

**Less was outstanding than §6 implied.** `rename/wa-mail-sync` and
`rename/wamailsync-build-artifacts` had already merged, `WAMAILSYNC_ROOT` was already the live env
var (with `WAGMAIL_ROOT` kept as a deliberate fallback for portable installs built before the
rename, `src/config.py:28`), appinfo.ini's `Description` was already fixed, and README and
`docs/user-guide.md` were already dual-framed. Only `wa-chat-sync.spec:3` still carried the old
product name.

**One real user-facing defect, and the hit-count table did not surface it.**
`WatchFolderWorker.triggerAutoSync` returned `"syncing to Gmail..."` from *after* the backend
if/else, so an IMAP user archiving into Fastmail was told their mail was on its way to Gmail. That
one line was the whole category-2 harvest in the code; the rest were comments and docstrings.

**Two things §4 got wrong about the copy.** First, the `help.html` that ships is the 13 KB root
file — bundled by `wa-chat-sync.spec:40`, opened by `gui.py:_help_html_path()` — not
`portable/help.html`, which is the 5.7 KB PortableApps stub and was already renamed. The shipped
file contradicted itself: its setup section had been updated for IMAP while its title, framing and
§6 still said Gmail-only. Second, `docs/privacy.html` was not merely stale wording; it had a real
gap. It described only the Google path and said nothing about the app password, where that password
is kept, or that the app talks to a third-party IMAP server at all — wrong since IMAP became the
default, and it is the policy linked from Google's consent screen. A new IMAP section was added and
the transport-neutral sections generalised; **every Google-scope description and the registered
consent-screen name "WhatsApp Chat Sync to Gmail" were left untouched**, the latter because that is
the string Google actually displays.

**A third staleness class this doc never anticipated.** The reset FAQ in `help.html`,
`HelpScreen.kt` and `docs/user-guide.md` still described *pre-v0.2.4* behaviour ("you may end up
with two threads, so delete the old one"), which the shipped confirmation gate now contradicts. All
three were rewritten, including the Gmail caveat that deleting a label only unlabels the messages
and leaves them in All Mail.

**On the `gmail_thread_id` / `gmail_label_id` columns:** §3 would have read these as category 2,
but `PLATFORM-PARITY.md:102-116` had already settled it the other way — keep the columns, no
migration. They are now self-documenting via a comment in `src/state.py`'s `_DDL` and in the
docstrings that touch them.

**Deliberately left, and still open:** `docs/CNAME` (`wagmail.ambani.tech`, needs DNS work per §4),
the GitHub repo name, and the on-disk repo folder name. `sign_exe.ps1`'s `CN=WAGmailSync Dev` cert
subject is frozen and carries its own in-file comment saying so. The ~460 remaining hits outside the
historical docs are all category 1 or §2a.

**Update 2026-08-08 — most of that paragraph has since closed.** `docs/CNAME` is
`wamailsync.ambani.tech` and the remote is `WAMailSync.git`; the cert subject was
changed to `CN=WAMailSync Dev` on 2026-08-06, so only the old
`CN=WAGmailSync Dev` certificate still sitting in this machine's certificate
stores keeps the name alive, and it is kept solely so already-shipped v0.2.x
releases still verify locally. The §2a identifiers were renamed (see the banner
there). Genuinely still open: the on-disk repo folder name, and `WAGMAIL_ROOT`,
which is now closed too — the fallback was removed from `src/config.py` on
2026-08-08 and `WAMAILSYNC_ROOT` is the only name honoured. What remains of
"wagmail" in the tree is historical records under `Completed/**` and the dated
`2026-*.md` docs, plus comments that exist specifically to explain these
renames.
