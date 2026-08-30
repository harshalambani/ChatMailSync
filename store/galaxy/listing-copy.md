# Galaxy Store listing copy — Chat Mail Sync 2.0.0

Drafted 2026-08-30. Nothing here is submitted. Character counts are measured, not
estimated; re-measure after any edit.

---

## App name

`Chat Mail Sync`  (15 chars)

## Short description — 75 chars

> Mail your WhatsApp chat exports into your own mailbox. One way, no account.

Alternates, if the portal rejects the first for naming another app:

- `Turn your exported chats into ordinary email in your own mailbox.` (65)
- `File your chat exports as email you own, on your own mail server.` (65)

## Long description — 2,552 chars

> Chat Mail Sync files your exported WhatsApp conversations into your own mailbox as
> ordinary email, so they live where the rest of your records already live: searchable,
> backed up by your mail provider, and readable in fifty years by anything that can open
> an email.
>
> It sends. It never reads, never replies, never deletes. The connection is one way by
> design.
>
> HOW IT WORKS
>
> 1. In WhatsApp, open a chat, then More > Export chat. Share it to Chat Mail Sync, or
>    save it to a folder the app watches.
> 2. Chat Mail Sync turns the export into one email per chat, threaded, in date order,
>    with the attachments still attached.
> 3. It remembers exactly what it has already sent. Export the same chat again next month
>    and only the new messages go — no duplicates, no second copy of the history.
>
> WHAT YOU NEED
>
> An email account that supports IMAP, and an app password for it. Gmail, Outlook, Yahoo,
> iCloud, Fastmail and any standard IMAP server all work.
>
> Be warned: setting up an app password is the least pleasant part of this app. It means a
> few minutes in your mail provider's security settings, and on Gmail it also means having
> two-step verification switched on first. The app walks you through it and links to the
> right page, but there is no way around it — an app password is what lets a program put
> mail into your mailbox without ever holding your real password.
>
> WHAT IT DOES NOT DO
>
> There is no Chat Mail Sync account, because there is no Chat Mail Sync server. Nothing is
> uploaded to us; there is no "us" in the data path at all. Your chats go from your phone
> to your mail provider and nowhere else. No analytics, no advertising, no tracking, no
> third-party SDKs.
>
> It does not read your WhatsApp messages, and it cannot: it only sees the export file you
> hand it yourself. It never sends anything back into WhatsApp.
>
> WORTH KNOWING BEFORE YOU INSTALL
>
> - The export step is manual and always will be. WhatsApp does not let an app read your
>   chats directly, which is exactly as it should be.
> - Your app password is stored encrypted in the Android Keystore, on this device only.
> - Attachments make exports large. A long chat with photos can be tens of megabytes, and
>   your mail provider has its own per-message size limit.
> - This build runs on 64-bit ARM devices, which is every Samsung phone and tablet from the
>   last several years.
>
> Chat Mail Sync is not affiliated with, endorsed by, or connected to WhatsApp LLC or Meta
> Platforms, Inc. WhatsApp is a trademark of WhatsApp LLC.
>
> Privacy policy: https://chatmailsync.ambani.tech/privacy.html

---

## Notes for whoever fills the portal in

- The **onboarding cliff paragraph is not optional**. A store visitor did not build this
  app; they will meet an IMAP app password on the second screen. Saying so in the listing
  costs an install and saves a one-star review that says "doesn't work".
- The trademark disclaimer is there because the listing names another company's app. Keep
  it whatever else changes.
- **Data safety / permissions answers are now uncomplicated**, because v2.0.0 removed the
  Google sign-in: the app collects nothing, shares nothing, and has no account. Answer
  every "does it collect" question with a plain no.
- Content rating: the app has no content of its own. The questionnaire should come back at
  the lowest rating available.
