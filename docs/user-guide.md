# WA Mail Sync — User Guide

A friendly, step-by-step guide to backing up your WhatsApp chats into your
mailbox. No technical knowledge needed.

---

## 1. What this app does

WhatsApp lets you **export** a chat to a file. On its own, that file is hard to
read and easy to lose.

This app takes those exported chat files and copies the conversations into
**your mailbox**, where they're:

- Searchable, like any other email.
- Organised under labels/folders — one per chat, all grouped under **WhatsApp**.
- Shown as a tidy, WhatsApp-style conversation, with photos and files included
  (if you exported them).

It **adds** the messages to your mailbox. It never sends anything, never deletes
anything, and never posts anything back to WhatsApp.

---

## 2. One-time setup: connecting to your mailbox

Before the first sync, the app needs permission to add messages to your mailbox.
There are two ways to connect. **Email app password (IMAP)** is the default and
the recommended one.

### Option A — Email app password (IMAP) — recommended

An "app password" is a separate password your email provider generates for one
app. It only works for mail, and you can revoke it at any time without changing
your real password.

1. Create an app password with your provider. For Gmail, go to your Google
   Account → Security → 2-Step Verification → App passwords. (Most providers
   require two-factor authentication to be on before they will issue one.)
2. Open the app, click the **gear icon** (top-right) to open Settings. Settings
   opens inside the main window rather than in a separate one — **Back to sync**
   (top-left), or the Escape key, returns you to the sync view.
3. Under **Mail account**, click **Change…**. That opens the Mail account screen
   the same way, with **Back to settings** to come out of it.
4. Leave **Connect via** set to *Email app password (IMAP)*.
5. Choose your provider (Gmail, Outlook, Yahoo, iCloud, Fastmail, or a custom
   IMAP server — host and port fill in automatically for the known ones).
6. Enter your email address and the app password, then **Save**.
7. The indicator turns **green** and says **Connected**.

This does not expire. To disconnect later, click **Forget saved password**.

### Option B — Google sign-in (Gmail only, hidden by default)

**You will not see this option unless you were already using it.** It is hidden
on a new setup for the reasons below; to show it deliberately, click the version
line at the bottom of Settings seven times. Everyone else should use Option A —
it reaches Gmail perfectly well.

It still works, and comes with two limits that are why it is no longer offered.
The app has not gone through Google's app-verification process, so Google treats
it as being in "Testing" mode:

- Only Google accounts that have been **added as test users** can sign in, and
  there is a limit of 100.
- **Google expires the sign-in about every 7 days**, so you have to reconnect
  roughly weekly. This is Google's rule for unverified apps and cannot be
  extended from within the app.

If that suits you anyway:

1. Open the app.
2. In the top-right corner, click **Connect**.
3. Your web browser opens a Google sign-in page. Choose the Google account you
   want your WhatsApp chats saved into.
4. Google asks whether to allow the app to manage your mail. Approve it.
5. Back in the app, the indicator turns **green** and says **Connected**.

To disconnect later, click **Sign Out** (this revokes access and removes the
saved sign-in).

> **Already using Google sign-in?** Nothing changes for you. The app keeps you on
> Google sign-in and will not ask for an app password, and the option stays
> visible on your setup — including after you try IMAP, so switching is not a
> one-way door. You can switch whenever you like from Settings.

---

## 3. Exporting a WhatsApp chat to a file

Do this on your phone, then move the file to your computer (email it to yourself,
use a USB cable, or any cloud drive).

### On Android

1. Open the chat you want to back up.
2. Tap the **⋮** menu (three dots) in the top-right.
3. Tap **More** → **Export chat**.
4. Choose **Include media** (to keep photos and files) or **Without media** (text
   only — smaller, faster).
5. Choose where to save or how to share the file.

### On iPhone (iOS)

1. Open the chat you want to back up.
2. Tap the contact or group **name** at the top of the screen.
3. Scroll down and tap **Export Chat**.
4. Choose **Attach Media** or **Without Media**.
5. Choose where to save (e.g. **Save to Files**) or how to share it.

You'll get either a **`.txt`** file (text only) or a **`.zip`** file (text plus
media). The app accepts both.

---

## 4. Adding chat files to the app

Once the export file is on your computer:

- **Drag and drop** the `.txt` or `.zip` file onto the app window, **or**
- Click **Browse Files…** and pick it, **or**
- Click **Open Inbox Folder** and copy the file into that folder yourself.

Added files appear in the **Files in inbox** list, with a count like
"2 files ready to sync". You can add several chats at once.

### Letting the app fetch them for you — the watched folder

If your exports always land in the same place (your Downloads folder, or a folder
your phone syncs to), you can have the app collect them itself.

1. Open **Settings** and click **Choose…** next to **Watched folder**.
2. Pick the folder. Nothing else is required — click **Check watched folder** on
   the main window whenever you want it to look.
3. To have it look on its own, tick **Check it automatically** and pick an
   interval (every 5 minutes up to once a day). Newly found files are imported
   and, if you are connected, synced straight away.
4. **After syncing** decides what happens to the *original* in the watched
   folder: leave it in place (the default), move it into a `synced` subfolder, or
   send it to the Recycle Bin.

Worth knowing:

- Only that one folder is looked at. Subfolders are ignored, so the `synced`
  subfolder never gets re-imported.
- Each file is picked up only once, however often the check runs.
- The **After syncing** rule applies only after a file has genuinely reached your
  mailbox. A sync that fails, or that you stop, leaves your originals untouched.
- The Recycle Bin option is exactly that — recoverable. If the app cannot recycle
  a file (on a network drive, for instance) it leaves it alone and says so in the
  log rather than deleting it for good.
- **The check only runs while the app is open.** There is no background service.
  The Android edition does check in the background, but its shortest interval is
  15 minutes because Android requires it.

---

## 5. Running a sync

1. Make sure the indicator at the top says **Connected** (green).
2. Check the options at the bottom-right:
   - **Dry run** — tick this to do a practice run that reports what *would* happen
     without changing your mailbox. Great for a first try.
   - **Chunk size** — how much of the conversation goes into each email:
     **day** (default), **hour**, or **week**. "Day" means one email per day of chat.
3. Click **▶ Sync Now**.
4. Watch the progress bar and the log at the bottom. You can click **⏹ Stop** to
   stop after the current file finishes.

When it's done, each chat appears in the list on the left with a green dot and a
message count. Synced files move out of the inbox automatically.

---

## 6. Where your messages appear

**If you connected with Gmail**, open Gmail and look in the labels list (left
side). You'll find:

- A **WhatsApp** label.
- Under it, one label per chat, e.g. **WhatsApp/John Doe**, **WhatsApp/Family Group**.

**If you connected with IMAP**, open your mail app and look for a **WhatsApp**
folder, with one subfolder per chat, in the same structure.

Either way, each chat is a single **conversation thread**. Opening it shows the
messages laid out like WhatsApp, with photos shown inline and other files
attached for download.

> **Shortcut (Gmail only):** In the app's chat list, click the **↗** icon next to
> a chat to jump straight to its Gmail thread in your browser.

---

## 7. Troubleshooting & FAQ

**The app says "Not connected" or authorising fails.**
If you're using **Email app password (IMAP)**, double-check the email address
and app password under **Settings → Mail account → Change…**, and that the app
password hasn't been revoked by your provider. If you were already using
**Google sign-in**, click **Connect** and
complete the sign-in in your browser, making sure you pick the right Google
account and approve the permission request. If it still fails, click **Sign
Out**, then **Connect** again to start fresh.

**What stops the app reading or deleting my mail?**
With an **email app password** — how you connect unless you were already using
Google sign-in — no provider can issue a password limited to one operation: any
password that can add mail can technically do anything. There the guarantee is
the app's own code, which uses only four mail commands: list folders, create a
folder, subscribe to it, and add a message. None of them can open or remove a
message, and because the app never opens a folder for reading it never reaches
the state where that would be possible. The source is public if you or anyone
else wants to verify it. On the older **Google sign-in**, Google enforces it
instead: the app is granted permission to insert mail and nothing else, checked
at Google's end on every request, so it would be refused even if the app asked.

**Where is my email app password kept?**
Encrypted at rest, on the machine you entered it on. On Windows it is encrypted
with Windows DPAPI using a key derived from your Windows login, on top of a file
that only your account can open; on Android it is encrypted with a key held in
the phone's Android Keystore. Either way it is never written into the app's
settings file, never shown back to you in the password box after saving, and
never included in a log line or an error message.

**I moved the app to another PC and it says the saved password can't be
decrypted.**
That is expected, not a fault. Windows encrypts the saved app password with a
key tied to your Windows account on that particular machine, so a copy of the
app carried to a different PC — or opened under a different Windows user —
cannot unlock it. Nobody who picks up the folder can read your password either,
which is the point. Your app password is still valid at your provider: just
enter it again under **Settings → Mail account** on the new machine.

**The sync said some media was "too large to email".**
Every email provider caps how big a single email can be — 25 MB at Gmail,
Outlook and Yahoo, 20 MB at iCloud, more at some others. The app works within
that cap by splitting a busy day across several emails, so in almost every case
you will never notice it. One case cannot be split: a *single* file, usually a
long video, larger than the whole cap on its own. No email anywhere can carry
it.

When that happens the message is still archived — the text, the sender, the
time, its place in the conversation — and the email carries a note where the
video would have been, naming the file and its size. Only the file itself is
left out, and it will be left out on every future sync too, which is why the
sync summary lists it by name rather than letting you discover it years later.
**The video is not lost**: it is still in the WhatsApp export you imported, and
still on your phone. If you want it in your mailbox, send it to yourself
separately, or shrink it first.

One thing that surprises people: a 20 MB video does not make a 20 MB email.
Email cannot carry raw files, so everything is re-encoded on the way out, which
adds about a third — a 20 MB video arrives as a roughly 27 MB email. That is why
the practical ceiling for a single file is around 18 MB on a 25 MB provider.

**My file doesn't show up in the inbox.**
Only `.txt` and `.zip` files are accepted. Make sure you exported the chat (not a
screenshot or contact card), and that the file actually copied over. Click the
**⟳** refresh button to re-check the inbox folder.

**Will I get duplicate messages if I export and sync the same chat again?**
Not on the same device. The app remembers what it has already saved and only adds
new messages, so you can safely re-export a chat later to pick up newer messages —
the overlap is skipped automatically. That memory belongs to this instance of the
app, though, not to your mailbox; see the next question.

**Can two instances of the app archive into the same mailbox?**
They can, but they will not know about each other, and you will get duplicates.
This is not about Windows versus Android — **any** two instances behave this way:
two PCs, two phones, one of each, or even two copies of the portable app on the
same PC, since each copy carries its own `Data\` folder.

The record of what has already been archived lives in that instance's own
`sync_state.db` — nothing about it is stored in the mailbox. A second instance
signed in to the same account therefore starts from zero knowledge and re-files
every chat you give it, even chats the first one archived months ago. The app can
add mail but never remove it, so clearing the duplicates afterwards is manual work
only you can do.

> **Use one instance per mailbox.** If you want to archive from more than one place,
> give each instance its own mailbox or its own account. Replacing an instance is a
> different case — carry `sync_state.db` across and the new one picks up exactly
> where the old one stopped.

**I want to re-do a chat from scratch.**
Click the **↺** (re-sync) icon next to the chat in the list. This clears the app's
record of that chat and moves its file back to the inbox so you can sync it again.

**Delete the old mail first.** This app can only add mail, never remove it, so if
the earlier messages are still in your mailbox the next sync files a second copy
alongside them. When a chat has already been archived, the app names the folder to
clear and asks you to confirm you have cleared it before it will reset.

> **On Gmail this needs care.** Gmail has no real folders, only labels — deleting
> the label just unlabels the messages and leaves them in All Mail, where the next
> sync still counts them as duplicates. Open the label, select every conversation,
> delete them, then empty the Bin.

**I removed a chat from the list by mistake.**
The **✕** button only removes the chat from the app's list — it does **not** delete
anything from your mailbox. Your emails are safe. Just add the export file again
to bring it back.

**The times on some messages look off.**
WhatsApp exports don't include a timezone. If your phone's timezone changed between
exports, some timestamps can appear shifted. This is a known limitation of WhatsApp
exports, not a bug in the app.

**My photos/files didn't come through.**
You probably exported **Without media**. Re-export the chat choosing **Include
media** / **Attach Media** (this produces a `.zip`), then sync that file.

**Can I keep a copy of my chat list?**
Yes — click the **CSV** button above the chat list to save a spreadsheet of all
your synced chats.
