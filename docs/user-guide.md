# WA Chat Sync to Gmail — User Guide

A friendly, step-by-step guide to backing up your WhatsApp chats into Gmail.
No technical knowledge needed.

---

## 1. What this app does

WhatsApp lets you **export** a chat to a file. On its own, that file is hard to
read and easy to lose.

This app takes those exported chat files and copies the conversations into your
**Gmail**, where they're:

- Searchable, like any other email.
- Organised under labels — one label per chat, all grouped under **WhatsApp**.
- Shown as a tidy, WhatsApp-style conversation, with photos and files included
  (if you exported them).

It **adds** the messages to your mailbox. It never sends anything, never deletes
anything, and never posts anything back to WhatsApp.

---

## 2. One-time setup: authorising Google access

Before the first sync, the app needs your permission to add messages to your Gmail.

1. Open the app.
2. In the top-right corner, click **Connect**.
3. Your web browser opens a Google sign-in page. Choose the Google account you want
   your WhatsApp chats saved into.
4. Google asks whether to allow the app to manage your mail. Approve it.
5. Back in the app, the indicator turns **green** and says **Connected**.

You only do this once. The app remembers your authorisation for next time. To
disconnect later, click **Sign Out** (this revokes access and removes the saved
sign-in).

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
     without changing your Gmail. Great for a first try.
   - **Chunk size** — how much of the conversation goes into each email:
     **day** (default), **hour**, or **week**. "Day" means one email per day of chat.
3. Click **▶ Sync Now**.
4. Watch the progress bar and the log at the bottom. You can click **⏹ Stop** to
   stop after the current file finishes.

When it's done, each chat appears in the list on the left with a green dot and a
message count. Synced files move out of the inbox automatically.

---

## 6. Where your messages appear in Gmail

Open Gmail and look in the labels list (left side). You'll find:

- A **WhatsApp** label.
- Under it, one label per chat, e.g. **WhatsApp/John Doe**, **WhatsApp/Family Group**.

Each chat is a single **conversation thread**. Opening it shows the messages laid
out like WhatsApp, with photos shown inline and other files attached for download.

> **Shortcut:** In the app's chat list, click the **↗** icon next to a chat to jump
> straight to its Gmail thread in your browser.

---

## 7. Troubleshooting & FAQ

**The app says "Not connected" or authorising fails.**
Click **Connect** and complete the Google sign-in in your browser. Make sure you
pick the right Google account and approve the permission request. If it still
fails, click **Sign Out**, then **Connect** again to start fresh.

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
Emails already in Gmail are not removed — you may end up with two threads, so
delete the old one in Gmail if you want a clean result.

**I removed a chat from the list by mistake.**
The **✕** button only removes the chat from the app's list — it does **not** delete
anything from Gmail. Your emails are safe. Just add the export file again to bring
it back.

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
