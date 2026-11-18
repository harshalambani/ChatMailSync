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

1. Install the app and sign in with the mailbox you were given (Yahoo address
   plus its app password).
2. Copy the three zips to the phone (any folder that Files can see).
3. In the app, open Home, then Choose exports to import, and pick the zips from
   Files. Selecting all three at once works.
4. Tap Sync now.
5. Each chat arrives in the mailbox as an email under the WhatsApp/ folder, with
   the pictures attached or shown inline.

## Regenerating

The two newer zips are produced by `tools/make_sample_exports.py` (standard
library only, same bytes on every run). A test fails if the committed zips
differ from what the script writes.
