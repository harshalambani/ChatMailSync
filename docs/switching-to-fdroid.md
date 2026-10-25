# Moving from a GitHub or store build to the F-Droid build

> These steps were tested end to end on a test device, in both directions (from the
> GitHub or store build to a build signed with a different key, and back).

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
   **Save a backup**. Choose a place outside the app: the phone's **Downloads** folder,
   or a cloud folder. The file chooser may open inside your watched folder; switch to
   Downloads instead, so the backup does not sit among your chat exports. The file
   ends in `.cmsbackup`. The screen shows a dated line
   when the backup is done; check it says today.
2. **Make sure the file is somewhere that survives the uninstall.** Anything inside
   the app's own storage is deleted with it. Downloads and cloud folders are not: a
   backup saved in Downloads is still there after the uninstall.
3. **Write down your mail address.** Your app password is *not* in the backup (see
   below), so have it ready, or create a new app password with your provider.
4. **Uninstall the old app** in Android's settings: **Settings -> Apps -> Chat Mail
   Sync -> Uninstall -> OK**.
5. **Install the F-Droid build.**
6. **Restore.** On the first screen tap **Moving from another phone? Restore from a
   backup** (or, once set up, **Settings -> Backup & restore -> Restore from a
   backup**) and pick the `.cmsbackup` file. The app shows a summary of what came back;
   tap **Continue**.
7. **Enter your app password once** to finish. The page is titled "Finish restoring -
   enter your app password" and your mail address is already filled in. Type the
   password, tap **Connect**, wait for the check to finish, then tap **Done**. Then
   **choose the watched folder again** in Settings -> Advanced, if you used one, before
   you rely on Auto-import. Both are listed under "Not restored" on the restore screen.
8. Do a first sync with a chat you have already sent before and check it is not sent
   again. Android may ask to allow notifications on the first sync; either answer
   works.

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
- Android's own system backup may also carry the record on some phones (the app opts
  in for the same file and preferences), but that depends on the phone and your
  settings, so do not rely on it: keep your own `.cmsbackup`.
