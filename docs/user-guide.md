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
2. Open the app, click the **gear icon** (top-right) to open Settings.
3. Leave **Connect via** set to *Email app password (IMAP)*.
4. Choose your provider (Gmail, Outlook, Yahoo, iCloud, Fastmail, or a custom
   IMAP server — host and port fill in automatically for the known ones).
5. Enter your email address and the app password, then **Save**.
6. The indicator turns **green** and says **Connected**.

This does not expire. To disconnect later, click **Forget saved password**.

### Option B — Google sign-in (OAuth)

This still works, but comes with two limits worth knowing before you pick it.
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
> Google sign-in and will not ask for an app password. You can switch to IMAP
> whenever you like from Settings.

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
and app password in Settings, and that the app password hasn't been revoked by
your provider. If you're using **Google sign-in**, click **Connect** and
complete the sign-in in your browser, making sure you pick the right Google
account and approve the permission request. If it still fails, click **Sign
Out**, then **Connect** again to start fresh.

**What stops the app reading or deleting my mail?**
It depends on which connection you use, and the difference is worth knowing.
With **Google sign-in**, Google enforces it: the app is granted permission to
insert mail and nothing else, checked on every request at Google's end, so it
would be refused even if the app asked. With an **email app password** (the
default), no provider can issue a password limited to one operation — any
password that can add mail can technically do anything. There the guarantee is
the app's own code, which uses only four mail commands: list folders, create a
folder, subscribe to it, and add a message. None of them can open or remove a
message, and because the app never opens a folder for reading it never reaches
the state where that would be possible. The source is public if you or anyone
else wants to verify it.

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
enter it again in Settings on the new machine.

**My file doesn't show up in the inbox.**
Only `.txt` and `.zip` files are accepted. Make sure you exported the chat (not a
screenshot or contact card), and that the file actually copied over. Click the
**⟳** refresh button to re-check the inbox folder.

**Will I get duplicate messages if I export and sync the same chat again?**
No. The app remembers what it has already saved and only adds new messages. You can
safely re-export a chat later to pick up newer messages — the overlap is skipped
automatically.

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
