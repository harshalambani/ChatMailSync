# Rename: "WA Mail Sync" -> "Chat Mail Sync"

Status: **audit + plan only.** Nothing in this document has been executed.
Execution starts in a separate session, as its own PR, after v1.5.0 (logo) is
settled.

Decisions already taken (2026-08-11):

- New product name: **Chat Mail Sync**. "Sync" is kept deliberately - the
  long-term intent is to pivot to an actual sync, so "Archive" would name the
  current limitation rather than the destination.
- **`applicationId` changes**: `com.wamailsync.app` -> `com.chatmailsync.app`.
  This is the expensive half and it is chosen on timing: an `applicationId` is
  frozen forever at first store publication, and the install base today is one
  phone with no listing. The cost is at its all-time minimum and only rises.
- Rationale for the name itself: the mark and the product are no longer
  WhatsApp-specific in intent (Signal / Telegram are plausible sources later),
  and the "WA" prefix would have to be neutralised eventually anyway - the same
  argument that drove the earlier Gmail-neutralisation sweep
  (`2026-08-03-gmail-naming-sweep.md`).

---

## 1. Name research

### 1.1 Is anything already called "Chat Mail Sync"?

No exact collision found.

- Google Play search for `"chat mail sync"` returns **no results** - not a
  single app under that name or a close variant.
- Nothing in general web search matches the full three-word string as a product.

Near neighbours that exist but do not collide:

| Product | What it is | Overlap |
|---|---|---|
| [Chat Sync (Mio)](https://www.m.io/products/chat-sync) | Enterprise Google Chat <-> Microsoft Teams federation | Two-word substring; B2B interop, different category |
| [MailChat](https://www.onlinenic.com/mailchat/) / [MailChat iOS](https://apps.apple.com/us/app/mailchat-email-for-domains/id592687646) | Chat-style email client | Word-order reversal, email-client category |
| [MailTime](https://play.google.com/store/apps/details?id=com.mailtime.android) | Email rendered as chat bubbles | Same *idea inverted* (email as chat, not chat as email) |
| [ChatInbox](https://apps.apple.com/us/app/chatinbox-chat-style-email/id6462772788) | IMAP client with chat-style UI | Category-adjacent |
| [Sync: Secure Chat & Private AI](https://play.google.com/store/apps/details?id=com.syncsuperapp.app) | Secure messenger | Shares "Sync"+"Chat", unrelated function |

### 1.2 The one real flag: **ChatMail**

`ChatMail` is a live, actively-asserted brand in the encrypted-communications
space, and it uses the **(R)** symbol on its own marketing:

- [chatmailsecure.com](https://chatmailsecure.com/) / [chatmailsecure.ch](https://chatmailsecure.ch/) - "ChatMail(R) ... fortified Android(TM) devices, end-to-end encryption, private server hosting", Calgary, Canada, founded 2018. Also resold as [ChatMail Secure PGP](https://www.secureme.chat/).
- Listed as a vendor on [Capterra](https://www.capterra.com/p/276611/ChatMail/), [GetApp](https://www.getapp.com/security-software/a/chatmail/), [Software Advice](https://www.softwareadvice.com/data-privacy/chatmail-profile/) and [Tracxn](https://tracxn.com/d/companies/chatmail/__JPk1rAWII8hsIdZejky5P9tSY_W6j0LrvSELPgtUCDM).
- `chatmail.com` and `chatmail.app` both resolve - registered.

Separately, **"chatmail" has also become a generic-ish technical term** in the
Delta Chat ecosystem: a "[chatmail relay](https://github.com/chatmail/relay)" is
a standard name for a stripped-down MTA that Delta Chat clients use, with its
own [support category](https://support.delta.chat/c/relays/23) and a
[FOSDEM 2026 talk](https://fosdem.org/2026/schedule/event/3F9VTU-deltachat-chatmail-relays-multi-transport/).

Why this matters here, and why it is still survivable:

1. **Do not compress the name.** "ChatMail Sync", "ChatMailSync" as a *displayed
   wordmark*, or a `chatmail.*` domain would sit directly on top of an asserted
   mark in an adjacent category (secure messaging over mail transport). The
   three-word, spaced form **"Chat Mail Sync"** is the safe form and must be the
   canonical display name everywhere - UI, store listing, README, site.
2. **Descriptiveness cuts both ways.** "Chat", "Mail" and "Sync" are each
   descriptive of what the app literally does, which makes the compound weak as
   a trademark (we would struggle to register or enforce it) but also makes it
   hard for anyone else to claim exclusivity over the combination. For a free,
   personal, non-commercial utility that is an acceptable trade - we want
   freedom to operate, not a defensible mark.
3. **Delta Chat's usage helps.** Widespread generic use of "chatmail" as a
   protocol/server term dilutes any claim that the word alone signals one source.
4. **Category distance is real.** ChatMail(R) sells hardened handsets and PGP
   messaging to a security market. This app writes WhatsApp exports into a
   mailbox the user already owns. Different goods, different channel, no
   overlap in customer.

**Not verified, and deliberately so:** I did not confirm an actual USPTO / EUIPO
/ Indian Trade Marks Registry registration number - the search surfaced only
generic "how to check trademark status" pages, and the `(R)` on their own site
is an assertion, not proof. See open question 5.1.

### 1.3 Domains

Checked by DNS NS lookup (absence of NS is a strong but not conclusive signal of
non-registration):

| Domain | Result |
|---|---|
| `chatmailsync.com` | no NS - **likely available** |
| `chatmailsync.app` | no NS - **likely available** |
| `chatmailsync.net` | no NS - **likely available** |
| `chatmailsync.org` | no NS - **likely available** |
| `chatmail.com` | registered |
| `chatmail.app` | registered |

The project's own site is a subdomain we already control:
`wamailsync.ambani.tech` -> **`chatmailsync.ambani.tech`**. No purchase is
required for the rename to proceed. Registering `chatmailsync.com` is optional
and cheap insurance, not a dependency.

### 1.4 Verdict

Proceed with **Chat Mail Sync**, spaced, as the only display form. Reserve
`chatmailsync` (one word, lowercase) for machine identifiers only - package,
domain, AppID, artifact names - where it never appears as branding.

---

## 2. Repo audit

128 tracked files. Case-**sensitive** token counts (an earlier pass conflated
cases and over-reported):

| Token | Files | Hits | Nature |
|---|---:|---:|---|
| `WA Mail Sync` | 25 | 52 | display name |
| `WAMailSync` | 26 | 95 | repo / folder / artifact / PyInstaller bundle |
| `wamailsync` | 28 | 35 | package id, domain, URLs |
| `WAMAILSYNC` | 9 | 21 | `WAMAILSYNC_ROOT` env var |
| `WaMail` | 17 | 24 | Kotlin type/file prefix |
| `wamail_` | 5 | 10 | **on-device storage keys** |
| `wa_chat_sync` | 3 | 3 | legacy internal name |
| `wa-chat-sync` | 14 | 29 | PyInstaller spec filename |

`WhatsApp` appears in 45 files / 253 hits and is **mostly legitimate content**,
not a rename target - it names the actual source of the exports. Only occurrences
that imply "this app is WhatsApp-only *by design*" should change, and only where
the multi-platform door is being deliberately opened.

### 2.1 Tier 1 - identity, breaks installs (must be one atomic change)

| Location | Current | New |
|---|---|---|
| `android/app/build.gradle.kts:29` | `namespace = "com.wamailsync.app"` | `com.chatmailsync.app` |
| `android/app/build.gradle.kts:33` | `applicationId = "com.wamailsync.app"` | `com.chatmailsync.app` |
| `android/app/src/main/AndroidManifest.xml:47` | `android:authorities="com.wamailsync.app.fileprovider"` | `com.chatmailsync.app.fileprovider` |
| `ChatsListScreen.kt:144` | `FileProvider.getUriForFile(context, "com.wamailsync.app.fileprovider", ...)` | must move in the *same commit* as the manifest, or share crashes |
| all 20 Kotlin files | `package com.wamailsync.app` | `package com.chatmailsync.app` |
| source directory | `android/app/src/main/java/com/wamailsync/app/` | `.../com/chatmailsync/app/` |
| `portable/App/AppInfo/appinfo.ini` | `AppID=WAMailSyncPortable` | `AppID=ChatMailSyncPortable` |

The FileProvider authority is the sharpest edge: authority strings are matched
literally at runtime, the manifest and the call site are in different files, and
a mismatch does not fail the build - it throws
`IllegalArgumentException: Failed to find configured root` only when the user
taps Share.

### 2.2 Tier 2 - on-device state (free to rename *now*, expensive later)

| Location | Current |
|---|---|
| `AppPrefs.kt:17` | `PREFS_NAME = "wamail_prefs"` |
| `SecretStore.kt:53` | `PREFS_NAME = "wamail_prefs"` |
| `SecretStore.kt:50` | `KEY_ALIAS = "wamail_imap_key"` |
| `WaMailApplication.kt:38` | `File(context.filesDir, "wamail")` |

**Key finding:** these were renamed only days ago (2026-08-08) and that rename
cost a `pm clear`. Renaming them *again* normally costs another wipe - but a new
`applicationId` gets a brand-new app sandbox regardless, so the old values are
unreachable either way. **Renaming these to `chatmail_prefs` /
`chatmail_imap_key` / `filesDir/chatmail` in this same change is therefore free.**
Doing it later is not. Do it now.

The `WaMailApplication.kt:34` comment about the historical `filesDir/wagmail`
tree becomes a third layer of archaeology - collapse it to one sentence rather
than growing the list.

### 2.3 Tier 3 - Windows build and packaging

| Location | Current |
|---|---|
| `build_portable.ps1:56` | `dist\WAMailSync` (PyInstaller output) |
| `build_portable.ps1:57` | `dist\WAMailSyncPortable` |
| `build_portable.ps1:58` | `App\WAMailSync` |
| `build_portable.ps1:92` | `$AppID = "WAMailSyncPortable"` (must match appinfo.ini) |
| `wa-chat-sync.spec:109,125` | `name="WAMailSync"` |
| `wa-chat-sync.spec` (filename) | -> `chat-mail-sync.spec` |
| `portable/App/AppInfo/Launcher/WAMailSyncPortable.ini` | filename must match AppID |
| `appinfo.ini` `Start=` | `WAMailSyncPortable.exe` |
| produced artifacts | `WAMailSyncPortable_<ver>_English.paf.exe` / `.zip` |

`build_portable.ps1:92` and `appinfo.ini` `AppID` are coupled by an assertion in
the script; the launcher `.ini` filename is coupled to both by the PortableApps
launcher generator. All three move together or the build fails.

### 2.4 Tier 4 - the `WAMAILSYNC_ROOT` environment variable

Referenced in `src/config.py` (4 places), `tests/test_config.py` (4),
`README.md` (2), `portable/help.html`, `build_portable.ps1` (2), and
`portable/App/AppInfo/Launcher/WAMailSyncPortable.ini:84`.

`src/config.py:25` records that a `WAGMAIL_ROOT` fallback was already removed
once and that `WAMAILSYNC_ROOT` is now "the only accepted name". Renaming to
`CHATMAILSYNC_ROOT` makes this the second such migration.

**Recommendation: rename it, with no fallback.** The launcher that sets the
variable and the exe that reads it ship in the same package and are never mixed
across versions, so there is no scenario where an old launcher meets a new exe.
Adding a compatibility fallback would recreate exactly the dead-alias problem
the last sweep spent effort deleting. Invert `tests/test_config.py` in the same
commit, as that sweep did.

### 2.5 Tier 5 - text, docs, and external surfaces

- 20 Kotlin files; four are named `WaMail*.kt` (`WaMailApplication`,
  `WaMailDialog`, `WaMailTheme`, `WaMailTopBar`) with matching type names and a
  `WaMailTopBar()` composable called across most screens. Rename to
  `ChatMail*` - this is mechanical but touches nearly every screen file.
- Legacy `wa_chat_sync` in `gui.py`, `ChatsListScreen.kt`, and
  `Completed/2026-06-08-session-handoff.md`. The first two should go; the
  `Completed/` file is a historical record and **must not be edited**.
- `appinfo.ini` `Description=` still says "Archive WhatsApp chat exports..." -
  the natural place to widen the wording, if the multi-platform door is being
  opened now rather than later.
- `WA-Mail-Sync-Password-Storage.docx` (tracked binary) - filename and internal
  text.
- README, `PLATFORM-PARITY.md:209`, `portable/help.html`, in-app help screens.
- External: repo `harshalambani/WAMailSync` -> `ChatMailSync` (GitHub redirects
  the old URL, so this is low-risk but the `Homepage=` line in `appinfo.ini`
  should point at the new one); site `wamailsync.ambani.tech` ->
  `chatmailsync.ambani.tech`; local folder
  `...\Cowork Playground\WAMailSync` -> `...\ChatMailSync`.

### 2.6 Do **not** rename

- Anything under `Completed/**` or the dated `2026-*.md` plan/brief/handoff
  documents, including this one - they are historical records.
- The 253 `WhatsApp` hits that legitimately describe the export format, the
  source app, or file layouts.
- Git history and existing tags/releases. Published assets keep their old names.

---

## 3. Migration hazards

**3.1 The duplicate-mail hazard (highest severity).** A new `applicationId` is a
new app with an empty sandbox. `sync_state.db` lives under
`filesDir/wamail` and does not carry over. If the user installs Chat Mail Sync
and re-imports the same exports, the app has no record of what it already sent -
**every message would be uploaded to the mailbox a second time.** The old app
also remains installed side by side, so nothing warns the user.

Options, in order of preference:

- (a) Do nothing but document it: uninstall the old app, and on the new one
  re-import only exports newer than the last sync. Cheapest; relies on the user.
- (b) One-shot export/import of `sync_state.db` via the existing share/file
  picker path, documented as a manual step in the release notes.
- (c) A migration reader that pulls from the old app's directory - **not
  possible**; app-private storage is not readable across `applicationId`s
  without root.

Given the install base is one phone, (a) is proportionate, but it must be
written down in the release notes, not left implicit.

**3.2 Credentials must be re-entered.** The Keystore alias is per-app; the IMAP
app password cannot migrate and must be re-entered by hand. This is correct
behaviour, not a defect - and the password field must still never be pre-filled.

**3.3 Windows Data\ folder.** Changing `AppID` changes the PortableApps
directory name. The user must copy `Data\` from
`...\WAMailSyncPortable\Data\` to `...\ChatMailSyncPortable\Data\` before first
launch, or lose sync state the same way. Unlike Android this *is* a simple
folder copy, so it should be an explicit numbered step in the release notes.

**3.4 Signing key.** `release.jks` is unaffected by the `applicationId` change
and must be reused - it is still the only signing identity, still gitignored,
and still has no off-machine backup (open item on the store-distribution phase).

**3.5 Version.** The rename is a bigger step change than the logo was. If v1.5.0
ships first with the logo alone, the rename should be **v2.0.0**, not v1.6.0.

---

## 4. Suggested execution order

Single branch, several commits, one PR. Each step must leave the tree buildable.

1. Tier 3 + Tier 4 - Windows build, spec, `AppID`, launcher `.ini`,
   `WAMAILSYNC_ROOT` -> `CHATMAILSYNC_ROOT`, tests inverted. Windows-only; verify
   with a full `-Installer -Zip` build.
2. Tier 1 Android - package directory move, `namespace`, `applicationId`,
   manifest authority + the `ChatsListScreen` call site together. Build APK.
3. Tier 2 - prefs / keystore alias / `filesDir` names, in the same PR while the
   sandbox is being reset anyway.
4. Kotlin `WaMail*` -> `ChatMail*` type and file renames.
5. Display strings, README, help, `appinfo.ini` `Description`/`Homepage`, docx.
6. Full test suite (282 at last count), Windows build, APK build, device install
   and smoke test.
7. Only after the PR merges: rename the GitHub repo, the site subdomain, and the
   local folder. Doing these first breaks the working tree mid-flight.

Platform parity is mandatory - Windows and Android change in the same batch, not
in sequence across releases.

---

## 5. Open questions

**5.1 Trademark diligence depth.** Should an actual registry search be run
(USPTO TESS / EUIPO / Indian TM Registry) for "CHATMAIL" and "CHAT MAIL", or is
the freedom-to-operate reasoning in section 1.2 sufficient for a free personal
utility with no commercial use? My read: sufficient as long as the name is never
compressed to one word in display, and the app is never sold. If it is ever
listed on a store under a developer account with a commercial intent, revisit.

**5.2 The eyebrow line.** RESOLVED - "CHATS STORED IN YOUR MAILBOX", replacing
"PRIVATE MAIL ARCHIVE". Width needs measuring on a narrow device before it is
committed (see 6.5).

**5.3 How far to open the multi-platform door now.** The rename removes "WA"
from the name, but the code, the import parser, the docs and the help text are
all still WhatsApp-specific and correctly so. Recommendation: change the *name*
and the *marketing description* only; leave every functional WhatsApp reference
alone until a second source actually exists. See section 6 - the name goes
neutral, the copy goes *more* explicit about WhatsApp, not less.

---

## 6. Naming vs. discoverability: where "WhatsApp" goes back in

The rename removes the only word in the product name that told anyone what the
app is for. That is the right call for the name and the wrong outcome for
discovery, so the two have to be solved separately.

### 6.1 The governing rule

**The name is the container; the line underneath does the explaining.**

Every surface carries a two-part lockup - a neutral, ownable name, and a
descriptive line directly beneath it that names WhatsApp explicitly. They are
never merged into one string. This is what lets the name stay trademark-safe
and future-proof while every piece of copy around it stays concrete.

### 6.2 Why "WhatsApp" must not go *in* the name

WhatsApp is a Meta trademark. Descriptive (nominative) use is normal and
accepted - "archives WhatsApp chat exports", "works with WhatsApp" - because it
describes what the software does. What is not accepted is use that suggests
association: the word in the app title, the WhatsApp logo, its green, or the
phone-handset-in-a-speech-bubble glyph. Google Play's impersonation and
misleading-title policies have been enforced against exactly this, including
against "WA"-prefixed utilities.

Two consequences worth recording:

- **Dropping "WA" is itself a risk reduction**, independent of the
  multi-platform argument. The rename makes a future store listing safer, not
  just tidier.
- **The new mark is already clean.** Oxford navy, a chevron and an envelope -
  none of Meta's trade dress. No change needed there.

### 6.3 The word that matters almost as much: "export"

The real search phrase people type is "whatsapp export email" or "whatsapp chat
backup to email". "Export" should ride alongside "WhatsApp" nearly everywhere,
and it does double duty: it is the honest signal that this app consumes a *file
you produce from WhatsApp*, not a login. Copy that says only "syncs your
WhatsApp chats" will attract users who expect it to connect to their account and
who will bounce or complain when it does not.

### 6.4 Surfaces, in order of search weight

The highest-leverage item is free and does not depend on the rename at all:
**the GitHub repo description is empty and it has no topics**
(`gh repo view` returns `"description": ""`, `"repositoryTopics": null`). That is
the single biggest discoverability gap in the project today.

| Surface | Draft copy |
|---|---|
| GitHub description | Archive WhatsApp chat exports into a mailbox you own, over IMAP - each chat becomes a threaded email conversation. Windows + Android. |
| GitHub topics | `whatsapp`, `whatsapp-export`, `whatsapp-backup`, `chat-export`, `imap`, `email-archive`, `gmail`, `android`, `windows`, `python`, `kotlin` |
| Store short description (<=80 ch) | WhatsApp chat exports, archived as threaded email in a mailbox you own. (71) |
| Store long description | Must name WhatsApp in the **first line**, then naturally 4-6 more times across features, requirements and FAQ. Not a keyword list. |
| README first sentence | Already good - it leads with "exported WhatsApp `.txt` (or `.zip`) chats". Keep the structure, update the H1 only. |
| Site `<title>` / meta | Chat Mail Sync - archive WhatsApp chat exports to your own mailbox |
| `appinfo.ini` `Description=` | Archive WhatsApp chat exports into your own mailbox over IMAP, as threaded emails. (keeps WhatsApp; PortableApps directory listings are searched) |
| In-app Help / empty states | Where the user is *asking a question*: "Where do I get an export?", "Does this work with Signal or Telegram?" |
| In-app masthead eyebrow | **No WhatsApp.** See 6.5. |

### 6.5 The one place WhatsApp should *not* appear

The masthead eyebrow line is not a search surface - the user has already
installed the app - and the band is only 88dp with a 56dp badge beside it.
"CHATS STORED IN YOUR MAILBOX" is the chosen line: it keeps the ownership point,
it is honest about today's write-only behaviour without the word "archive"
boxing in the future pivot, and it stays short.

**Open risk:** at 28 characters, 10sp uppercase with 1.4sp tracking, it needs
roughly 235dp. On a narrow device, after the 16dp start inset, the 56dp badge,
the 10dp gap and any action icons, that is borderline. Measure it on the real
device before committing, and have "CHATS IN YOUR MAILBOX" (21 ch) ready as the
fallback.

### 6.6 The anti-stuffing rule

"WhatsApp" goes where it answers a question the reader is actually asking - what
does this take in, where do I get one, what happens to it, what if I use
something else. It never appears as a bare keyword list, and it is never
repeated inside a single sentence. Copy that reads as stuffed damages both
credibility and store ranking.

### 6.7 Reconciling the neutral name with WhatsApp-only reality

The honest framing is temporal, and one word carries it: **"currently"**.

> Currently supports WhatsApp chat exports. The name does not box the app in -
> other chat sources can be added without another rename.

That single sentence belongs in the README, the store long description and the
Help screen. It sets expectations correctly today and pre-explains the neutral
name, so the name stops looking like a mismatch and starts looking deliberate.

---

## 7. Version sequencing

The rename ships as **v1.9.0**, not v2.0.0. v2.0.0 is cut only after v1.9.0 has
been proven clean in real use - a confidence marker on a near-identical tree,
not the release that carries the risk. (v1.5.0, the logo, ships first and
separately.)

**5.4 Register `chatmailsync.com`?** Not required - the site is a subdomain we
already own. Optional defensive registration.
