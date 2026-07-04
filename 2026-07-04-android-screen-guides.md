# WA Chat Sync to Gmail (Android) — Screen-by-Screen Guide

**Version:** 1.0 (2026-07-04)
**Purpose:** UI spec for the Android app. Written as build input for Claude Code sessions (plan phases A2–A5) and as the basis for user help content later.
**Conventions:** Jetpack Compose, Material 3, single-activity navigation. All colors/typography via `WagmailTheme` — no literals in screens (theming hook). `AdSlot(placement)` composables render nothing until ads are activated.

Navigation map:

```
Onboarding (first run only)
   └→ Home ──┬→ Import review → Sync progress ─→ Home (result banner)
             ├→ Chats list → Chat detail
             ├→ Settings → (Appearance | Support the app | About/Help)
             └→ Connect Gmail (modal flow, reachable from Home & Settings)
```

Every feature of the Windows GUI maps to a screen below; the parity checklist is in §11.

---

## 1. Onboarding (first run only)

**Purpose:** set expectations in three swipes; get to Connect fast.

- **Layout:** 3-page horizontal pager + page dots; Skip (top-right), Next/Get started (bottom).
  - Page 1: app icon + "Your WhatsApp chats, archived in your Gmail." One-line privacy note: "No servers in between — your phone talks only to Google."
  - Page 2: illustration of WhatsApp → Export chat → share sheet ("This is how chats get in").
  - Page 3: what it can't do (no live sync, no reading your email) — honesty up front reduces bad reviews later.
- **Actions:** Get started → Connect Gmail screen. Skip → Home (Home shows a "Connect" call-to-action instead).
- **States:** shown once (DataStore flag); reachable again from Settings → Help.

## 2. Connect Gmail

**Purpose:** OAuth. Android equivalent of the Windows GUI **Connect** button + `setup_auth.py`.

- **Layout:** centered card: Google "Sign in" branded button, scope explanation in plain words ("Can add mail and labels to your Gmail. Cannot read, send, or delete anything."), link to privacy note.
- **Flow:** tap → AppAuth Custom Tab → Google consent → return → card flips to "Connected as <email>" with a Disconnect option.
- **States:**
  - *Not connected* (default), *Connecting* (spinner in Custom Tab handoff), *Connected* (email shown, green check), *Error* (denied consent / no network → friendly retry, never a raw stack trace).
  - *Token expired* (Testing-mode 7-day case): banner "Google needs you to reconnect" + one-tap re-auth.
- **Never** show ads here (ad policy + trust).

## 3. Home (dashboard)

**Purpose:** single glance = connection state, what's waiting, last result. Windows GUI's main window equivalent.

- **Layout (top→bottom):**
  1. App bar: title + Settings gear.
  2. Connection chip: "Connected as <email>" or "Not connected — tap to connect".
  3. **Inbox card:** "N export file(s) waiting" with file names/sizes; empty state teaches the share-sheet gesture with a "How do I export?" link (bottom sheet with WhatsApp step-by-step incl. screenshots).
  4. **Sync controls:** chunk-size selector (Day ▾ default / Hour / Week / N messages), Dry-run toggle, big **Sync now** button (disabled with reason if not connected or inbox empty).
  5. **Last sync result banner:** success (chats/messages counts) or failure summary; tap for details.
  6. `AdSlot(HOME_BOTTOM)` — inert until ads phase.
- **Actions:** Sync now → Sync progress screen; inbox card → Import review; "+" FAB → SAF file picker (alternative to share sheet).
- **States:** not-connected, empty-inbox, ready, sync-running (button becomes "View progress"), error banner.

## 4. Import review (share-intent landing)

**Purpose:** confirmation step when a file arrives via share sheet or picker — the moment of trust.

- **Layout:** file card (name, size, type .txt/.zip), parsed preview after a quick local parse: chat name, participant count, message count, date range, media count, detected timestamp format. Warning row if the file looks wrong (not a WhatsApp export, zip too large per `MAX_ZIP_DECOMPRESSED_BYTES`, unparseable dates).
- **Actions:** **Add to inbox** (primary) → back to Home with inbox count bumped; **Add & sync now** (secondary); Discard.
- **States:** parsing (spinner over card), parse-failed (explain what a valid export looks like + "How do I export?" link), duplicate-file detected ("Looks already synced — dry-run to check?").

## 5. Sync progress

**Purpose:** live progress of the WorkManager job; Windows `gui_worker.py` progress pane equivalent.

- **Layout:** overall progress bar; per-chat rows appearing as processed (chat name, messages pushed/skipped, spinner→check/cross); live log line (collapsible "details" for the curious); Cancel button (finishes current email chunk, then stops cleanly — files stay in inbox until fully done, matching the desktop's only-move-on-success rule).
- **Behavior:** screen-off safe (foreground notification mirrors progress); returning to app deep-links back here while running.
- **End states:**
  - *Success:* summary card (X chats, Y messages, Z skipped as duplicates, W media attached) + "Open Gmail" button + Done.
  - *Partial/failure:* what succeeded, what failed and why (no network / auth expired / quota), with "Retry failed" button. Files for failed chats remain in inbox (guaranteed by shared-core semantics).
  - *Dry-run end:* clearly badged "DRY RUN — nothing was written", with would-sync counts.

## 6. Chats list

**Purpose:** every chat the app has ever synced; Windows GUI chat browser equivalent.

- **Layout:** search field; list rows = chat name, status chip (Synced / Pending / Error), last-synced time, message count. Sort by name/recent. Overflow menu: **Export list as CSV** (parity with Windows) via share sheet.
- **Actions:** row tap → Chat detail. Pull-to-refresh re-reads state DB.
- **States:** empty ("Nothing synced yet" + pointer to Home), populated, filtered-no-results.
- `AdSlot(CHATS_BOTTOM)` — inert until ads phase.

## 7. Chat detail

**Purpose:** per-chat status + management actions.

- **Layout:** header (chat name, participants if group); stat rows (messages synced, first/last message dates, last sync run, Gmail label name, thread exists ✓); action list:
  - **Open in Gmail** — deep-link to the thread (Gmail app intent, browser fallback).
  - **Re-sync from scratch (Reset)** — destructive-styled; confirmation dialog explains exactly what Windows CLI `reset` explains: local state cleared, Gmail mail untouched, re-import the export to rebuild into a new thread. If the original file is in `processed/`, offer one-tap "Move back to inbox".
- **States:** healthy, error-on-last-sync (show error + Retry), reset-pending ("Waiting for re-import").

## 8. Settings

**Purpose:** configuration + future-feature surface.

- **Sections & items:**
  - **Account:** connected address, Disconnect, Reconnect.
  - **Sync defaults:** chunk size default, dry-run default off, label parent name (`WhatsApp/` — advanced, warn on change).
  - **Appearance:** theme = System/Light/Dark; Material You dynamic color toggle (Android 12+). *(Future: theme/skin gallery slots in here — §5.3 of the master plan.)*
  - **Support the app:** *(future — §5.2)* tip-jar products + optional external donate link; hidden entirely until activated.
  - **Data:** view storage used; "Clear processed files"; "Delete all local data" (confirmation; Gmail untouched).
  - **About/Help:** version, licenses (Chaquopy/AOSP notices), Help (→ §9), privacy note, link to project page.
- **States:** items greyed with explanation when not applicable (e.g., Account items when not connected).

## 9. Help

**Purpose:** Android edition of `help.html` / `docs/user-guide.md`.

- **Layout:** searchable FAQ accordion. Must-have entries: how to export from WhatsApp (with media vs without), why messages aren't "sent" anywhere (insert vs send), dedup/re-export behavior, timezone caveat, reset semantics, "why do I reconnect weekly?" (Testing-mode token note, personal-use phase only), what the app cannot do.
- **Source of truth:** generate from one markdown file shared with the repo docs, so Windows and Android help never drift.

## 10. Home-screen widget (future phase — spec reserved)

- **Type:** Glance AppWidget, 2×2 default, resizable.
- **Content:** last sync ("2h ago ✓"), inbox count ("1 file waiting"), **Sync** button (enqueues SyncWorker directly; shows minimal running state), tapping anywhere else opens Home.
- **States:** idle / running / attention (auth expired → opens Connect).
- **Prerequisite already in the build:** sync status mirrored to DataStore; worker invocable without Activity.

## 11. Windows-GUI parity checklist (A5 exit gate)

| Windows GUI feature | Android home |
|---|---|
| Connect to Gmail | Connect screen (§2) |
| Drag-drop export files into inbox | Share sheet + SAF picker (§3, §4) |
| Chunk size / dry-run choice | Home sync controls (§3) |
| Run sync with live progress | Sync progress (§5) |
| Browse synced chats | Chats list (§6) |
| Open chat's Gmail thread | Chat detail → Open in Gmail (§7) |
| Reset / re-sync chat | Chat detail → Reset (§7) |
| Export chat list to CSV | Chats list overflow (§6) |
| Help button | Settings → Help (§9) |
