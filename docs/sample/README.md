# Sample WhatsApp exports

Three small export zips for trying Chat Mail Sync without any WhatsApp history.
Every name and message in them is made up. The only people are Meera Iyer,
Rohan Mehta and Tara Menon; there are no phone numbers, email addresses or places.

| Zip | What it is |
| --- | --- |
| `WhatsApp Chat with Meera Iyer.zip` | A short one-to-one chat, text only (17 messages). |
| `WhatsApp Chat with Garden Club.zip` | A group chat with three members (14 messages). |
| `WhatsApp Chat with Rohan Mehta.zip` | A one-to-one chat with two small pictures (7 messages, 2 attachments). |

The pictures are plain shapes on a flat colour and carry no metadata.

## Try it (no WhatsApp needed)

1. Install the app and connect the mailbox you were given: its email address
   and app password (Gmail, Yahoo or any IMAP mailbox).
2. Copy the three zips to the phone (any folder that Files can see).
3. In the app, open Home, then Choose exports to import, and pick the zips from
   Files. Selecting all three at once works.
4. Tap Sync now.
5. Each chat gets its own folder (a label, in Gmail) named WhatsApp/<chat name>,
   for example WhatsApp/Rohan Mehta. It holds one email for each day of the
   conversation. Pictures show inside the email; other files are attached.

   The app's Chats screen lists each chat with its sync status and how many
   messages were filed. To read the emails themselves, add the same address and
   app password to any IMAP mail app: an app password does not sign in to the
   Gmail website.

## Regenerating

The two newer zips are produced by `tools/make_sample_exports.py` (standard
library only, same bytes on every run). A test fails if the committed zips
differ from what the script writes.
