# Cutoff date — feature plan

**Status:** specified, not started. All four design decisions (bb3.1–bb3.4) are
settled; this document turns them into an implementation order.

**Written:** 5 September 2026.

---

## 1. What the feature is for

A WhatsApp export always starts at the beginning of the chat. If you archived a
conversation years ago by some other means — or simply do not want the first
eight years of it in your mailbox — there is currently no way to say so. The
app's only floor is `last_synced_ts`, which it discovers for itself from the
previous successful run, and which is `None` on a chat it has never seen.

A cutoff date is a **user-supplied floor**: "never send me anything from before
this date." It is the honest repair for "I only want the recent stuff", which
today can only be achieved by hand-editing the export file.

## 2. The settled decisions

| # | Question | Decision |
|---|---|---|
| bb3.1 | Sticky or per-run? | **Sticky, but permanently visible.** A per-run field is useless to a watched folder that syncs unattended. The danger of a persistent cutoff is forgetting it and silently losing messages forever — so whenever one is set it must be **on the main screen**, not only in Settings. Persistence is fine; invisible persistence is not. |
| bb3.2 | A date, or a range? | **Floor only.** One date, meaning "nothing before this". No ceiling, no windows. |
| bb3.3 | Cutoff vs `last_synced_ts` — which wins? | **The later of the two, always.** |
| bb3.4 | How loudly does it report? | **Loudly, and as its own number** — not folded into the existing skipped count. |

### 2.1 Why "later of the two" is the load-bearing decision

They can disagree. Suppose a chat has synced normally up to **March**, and you
later set a global cutoff of **1 January** to fix a *different* chat.

If the cutoff won, that chat's floor would drop from March back to January, and
every message between them would be sent a second time. The hash check would
catch most of them, but that is precisely the state you do not want to be
relying on.

Taking the later of the two means the cutoff is **ignored for chats already
ahead of it** and applies only where it actually raises the bar. The feature
becomes **incapable of causing a duplicate by construction** — it can only ever
skip more, never re-send.

The cost is that a cutoff can never be used to *re-sync* older history. That is
acceptable: re-syncing is what `reset` is for.

## 3. Two levels

- **3.1 A global cutoff**, held in platform settings, applying to every chat
  that has no opinion of its own. Empty by default — the feature is off until
  someone sets a date.
- **3.2 A per-chat cutoff** that overrides the global for that chat only. Empty
  means "inherit the global".

## 4. Where the state lives

### 4.1 The global cutoff — platform settings, not the database

It is a preference, and it sits beside the other preferences on each platform:

- Windows: a `"cutoff_date"` key in the `gui.py` settings dict (default `""`).
- Android: `AppPrefs.KEY_CUTOFF_DATE` (default `null`), alongside
  `KEY_WATCHED_FOLDER_URI` and friends.

It reaches the core the same way `chunk_size` does — as a `SyncManager`
constructor argument, not by the core reading a settings file.

### 4.2 The per-chat cutoff — a new table, not a column on `chats`

**This is a refinement of the earlier note, and the reason matters.** The
earlier sketch was a `cutoff_ts` column on `chats`, with a row written on demand
for a chat that had never synced. That is wrong: the `chats` table only holds
chats that have synced **at least once**, and `get_sync_summary()` — which feeds
the CLI `status` command and both chat lists on both front-ends — reads straight
from it. Writing a premature `chats` row to hang a date off would put a phantom
chat, with no runs and no mail folder, into every one of those lists.

So instead:

```sql
CREATE TABLE IF NOT EXISTS chat_cutoffs (
    chat_id   TEXT PRIMARY KEY,   -- deliberately NOT a FK to chats
    cutoff_ts TEXT NOT NULL,      -- ISO 8601, midnight local: "2026-01-01T00:00:00"
    set_at    TEXT NOT NULL
);
```

No foreign key, by design: a cutoff can be set on a chat the app has only ever
*seen in an export file* and never synced. The row is inert until that chat's
`chat_id` turns up in a run.

`chat_id` is derived from the export the same way it always is, so the picker UI
is driven by the **discovered export files** in the inbox, not by the `chats`
table.

### 4.3 Migration

`_SCHEMA_VERSION` goes `1 → 2` at `src/state.py:84`. `chat_cutoffs` is created
by adding it to `_DDL` (`CREATE TABLE IF NOT EXISTS`), which covers fresh
installs and existing databases alike — no `ALTER TABLE` needed, so the
`state.py:119` idiom is not required here.

The counter column *does* need it — see 5.3.

## 5. The core change

### 5.1 One new argument

`SyncManager.__init__` (`src/sync_manager.py:156`) gains
`cutoff_date: Optional[str] = None` — the **global** cutoff, ISO, or `None`.
Stored as `self.cutoff_date`. `ProgressSyncManager` inherits it unchanged.

### 5.2 One new rule in `_filter_messages`

`src/sync_manager.py:411`. The effective floor is resolved **per chat**, once,
before the loop:

```python
def _effective_cutoff(self, chat_id: str) -> Optional[str]:
    """The user's floor for this chat: its own override, else the global."""
    return get_chat_cutoff(chat_id, self.db_path) or self.cutoff_date
```

and the loop gains a third rule:

```
Rules (applied in order):
  1. Hash already in message_hashes → skip (exact duplicate).
  2. message_ts <= last_synced_ts   → skip (re-export overlap).
  3. message_ts <  cutoff           → skip (before the user's floor).
```

**The two comparison operators are deliberately different and must stay that
way.** Rule 2 is `<=` because `last_synced_ts` names a message that *was already
sent*. Rule 3 is `<` because the cutoff names an *instant*, and a message
timestamped exactly at midnight on the cutoff date is on the day the user asked
for. Getting this backwards silently drops one day's first message.

Timestamps are ISO 8601 strings compared lexicographically throughout the
codebase; the cutoff is normalised to `YYYY-MM-DDT00:00:00` on the way in so it
compares correctly against `msg.timestamp_iso`. There is no timezone conversion
anywhere in the parser and none is introduced here — the export's local time is
the only clock in play.

### 5.3 Its own counter

`_filter_messages` currently returns `(new, skipped)` and lumps rule 1 and rule 2
together into `n_skipped` (`sync_manager.py:432`). It becomes
`(new, skipped, cutoff_skipped)`.

- `SyncStats` (`sync_manager.py:110`) gains `messages_cutoff: int = 0` and prints
  it on its own line in `__str__` **only when non-zero** — a zero line on every
  run for a feature nobody turned on is noise.
- `sync_runs` gains `messages_cutoff INTEGER NOT NULL DEFAULT 0` via the
  `ALTER TABLE` idiom at `state.py:119`, guarded by the same
  `except sqlite3.OperationalError: pass`.
- The Sync log run detail shows it as a distinct line, worded as a fact rather
  than a warning: *"142 messages before your cutoff date"*.

This is bb3.4. The whole point is that the number the cutoff is responsible for
is never confusable with the number the deduplicator is responsible for.

## 6. The front-ends — both, in the same batch

Per `PLATFORM-PARITY.md`, functionality parity is non-negotiable and wording
should be as close as practical.

### 6.1 Settings — set the global cutoff

- **Windows** (`gui.py`): a date field in the settings area beside the watched
  folder controls, with a "Clear" affordance. Empty = off.
- **Android** (`SettingsScreen.kt`): the same, using the Material date picker,
  and reached through `MainActivity`'s existing settings plumbing.

Both write only the settings key; neither touches the database.

### 6.2 Main screen — the visibility that makes 3.1 safe

**This is not optional and it is not a Settings-screen line.** Whenever a cutoff
is set, the main screen says so, with the date, and offers one tap to clear it:

> **Only syncing messages from 1 January 2026 onwards.**  *Change*

- **Android:** a card in `HomeScreen.kt`, above the inbox card, styled like
  `BackgroundHealthCard` — `surfaceVariant`, not the error red. Nothing has gone
  wrong; the user is being reminded of a choice they made.
- **Windows:** the equivalent strip in the main window.

Absent entirely when no cutoff is set.

### 6.3 Per-chat override

Reached from the chat's own screen (`ChatDetailScreen.kt` on Android, the chat
row on Windows) and from the import preview, so a date can be set on a chat
before its first ever sync. Both write to `chat_cutoffs`.

Shows what it inherits when it has no override of its own: *"Using the app-wide
cutoff, 1 January 2026."*

### 6.4 CLI

`cli.py` gains `--cutoff YYYY-MM-DD` on the sync command, passed straight to
`SyncManager(cutoff_date=...)`. Per-chat cutoffs are read from the database as
usual, so the CLI honours them without needing a flag of its own.

## 7. Tests

Against `tmp_path` fixtures only, per the standing rule.

1. **A cutoff skips only what is below it** — messages either side of the date,
   exact counts both ways.
2. **Midnight on the cutoff date is included** — the `<` versus `<=` guard.
3. **`last_synced_ts` beats an earlier cutoff** — the March/January case from
   2.1. The assertion that matters: **nothing is re-sent.**
4. **A cutoff beats an earlier `last_synced_ts`** — the floor rises.
5. **A per-chat cutoff overrides the global**, and an absent one inherits it.
6. **A cutoff on a never-synced chat applies on its first run** — the reason
   `chat_cutoffs` has no foreign key.
7. **The counter is separate** — a run with both a hash duplicate and a
   cutoff skip reports each under its own number.
8. **No cutoff set anywhere behaves exactly as today** — the regression guard.
9. **Migration:** a v1 database opens, gains both the table and the column, and
   its existing runs still read back.

## 8. Order of work

1. `state.py` — table, column, `_SCHEMA_VERSION` bump, `get_chat_cutoff` /
   `set_chat_cutoff` / `clear_chat_cutoff`, plus test 9.
2. `sync_manager.py` — the argument, the rule, the counter; tests 1–8.
3. `cli.py` — `--cutoff`.
4. `android_api.py` — the thin wrappers the Kotlin side needs.
5. Both front-ends together: Settings field, main-screen banner, per-chat
   override.
6. Help and FAQ on all three surfaces (`help.html`, `docs/user-guide.md`,
   `HelpScreen.kt`) — parity is test-enforced by `tests/test_faq_parity.py`, so
   any new question lands in all three in the same order or the suite fails.

Steps 1–3 are shippable on their own and change nothing for anyone who does not
set a date. Step 5 is what the user actually sees, and step 6 is what stops it
becoming a mystery six months later.

## 9. Explicitly out of scope

- No ceiling and no date ranges (bb3.2).
- No re-syncing of history below a cutoff — that is `reset`.
- No automatic cutoff suggestion based on export contents.
- No per-chat *global* defaults beyond the single app-wide one.
