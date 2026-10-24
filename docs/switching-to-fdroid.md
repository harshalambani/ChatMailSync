# Moving from a GitHub or store build to the F-Droid build

> **Not yet verified on a device.** This guide is written from what the app's code
> offers (Settings -> Backup & restore), not from a rehearsal. The key-switch
> rehearsal (LEG-04) will check every step on a real phone; until it has, treat the
> steps as a plan. Do not link this page from the README or the stores yet.

## Why this needs care

F-Droid builds the app itself and signs it with its own key. Android refuses to
install an app over an existing copy that was signed with a different key, so you
cannot update from the GitHub or store build to the F-Droid build. You have to
**uninstall the old app first**, and uninstalling removes everything the app keeps on
the phone, including the record of what it has already sent to your mailbox.

Your chats are not at risk: they are in your mailbox, and the app never removes mail.
What you would lose is the record. Without it, the new app mails every chat a second
time, into a mailbox that cannot tell the copies apart. A backup avoids that.

## Steps

1. **Save a backup in the old app.** Open **Settings -> Backup & restore** and tap
   **Save a backup**. Choose a place outside the app (the phone's Downloads folder, or
   a cloud folder). The file ends in `.cmsbackup`. The screen shows a dated line
   when the backup is done; check it says today.
2. **Make sure the file is somewhere that survives the uninstall.** Anything inside
   the app's own storage is deleted with it. Downloads and cloud folders are not.
3. **Write down your mail address.** Your app password is *not* in the backup (see
   below), so have it ready, or create a new app password with your provider.
4. **Uninstall the old app** in Android's settings.
5. **Install the F-Droid build.**
6. **Restore.** On the first screen tap **Moving from another phone? Restore from a
   backup** (or, once set up, **Settings -> Backup & restore -> Restore from a
   backup**) and pick the `.cmsbackup` file. The app shows what came back.
7. **Enter your app password once** to finish, and **choose the watched folder again**
   in Settings -> Advanced, if you used one. Both are listed under "Not restored" on
   the restore screen.
8. Do a first sync with a chat you have already sent before and check it is not sent
   again.

## What the backup holds, and what it leaves out

- **Included:** the record of which messages were already sent (per chat), per-chat
  cutoff dates, and your preferences (theme, email grouping, check interval, mail
  provider and address, and similar).
- **Not included, by design:** the **IMAP app password**. The app keeps it encrypted
  with a key that never leaves the phone, so it cannot be moved in a file. The restored
  app asks for it once.
- **Not included:** the watched folder (Android grants folder access per install), and
  your chat export files. Add the exports again if you still need to send them.

Restoring merges rather than replaces, and restoring the same backup twice does
nothing the second time.

## If something goes wrong

- If you uninstalled before saving a backup, your chats are still in the mailbox. The
  first sync will send them again; delete the duplicates in the mail app, or turn on
  the cutoff date for each chat before syncing.
- Android's own system backup may also carry the record (the app opts in for the same
  file and preferences), but do not rely on it: keep your own `.cmsbackup`.
