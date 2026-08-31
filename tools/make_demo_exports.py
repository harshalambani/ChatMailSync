"""Generate a demo set of WhatsApp exports for screenshots and manual testing.

Store listings need screenshots, and screenshots of this app necessarily show a
list of chats -- which on any real install means the names of real people. That
is third-party personal data and it must not be published, so the screenshots
are taken against this invented dataset instead.

Everything here is fictional. The names are ordinary enough to look right in a
screenshot and carry no real contact details: no phone numbers, no addresses,
no email addresses, and nothing that would embarrass anyone if it were real.

The exports use the Android "DD/MM/YY, HH:MM - Sender: body" shape, which is
the `plain_24h` pattern in TIMESTAMP_PATTERNS and the one most Indian and
European phones produce. Dates are spread across several months on purpose, so
the same dataset can demonstrate a cutoff date once that feature lands.

Usage:
    python tools/make_demo_exports.py [output_dir]

Default output_dir is ./demo_exports. The directory is created if missing and
existing files with the same names are overwritten.
"""

import sys
from pathlib import Path

# A 1x1 JPEG, the smallest valid file that an image attachment can point at.
# Written as bytes rather than generated so this script needs no dependencies.
TINY_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300ffffffffffffff"
    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "ffffffffffffffffffffffffffffc2000b080001000101011100ffc400140001"
    "00000000000000000000000000000000ffda0008010100013f10"
)

HEADER = "Messages and calls are end-to-end encrypted. No one outside of this chat, not even WhatsApp, can read or listen to them."

CHATS = {
    "Anita Verma": [
        ("12/01/26, 09:14", "Anita Verma", "Morning! Are we still on for Saturday?"),
        ("12/01/26, 09:31", "You", "Yes, all set. What time works?"),
        ("12/01/26, 09:32", "Anita Verma", "Four-ish? The traffic after five is unbearable."),
        ("12/01/26, 09:35", "You", "Four it is."),
        ("28/02/26, 18:02", "Anita Verma", "That bookshop we talked about has moved to the second floor"),
        ("28/02/26, 18:20", "You", "Good, the old place was impossible to find"),
        ("14/04/26, 11:47", "Anita Verma", "IMG-20260414-WA0002.jpg (file attached)"),
        ("14/04/26, 11:47", "Anita Verma", "Found this in the loft. Any idea what year?"),
        ("14/04/26, 12:15", "You", "No clue, but keep it"),
        ("03/06/26, 20:11", "Anita Verma", "Thanks for today. Genuinely."),
        ("03/06/26, 20:44", "You", "Any time."),
    ],
    "Dad": [
        ("05/02/26, 07:02", "Dad", "Walk was good today. 6000 steps before breakfast."),
        ("05/02/26, 08:19", "You", "That's more than me and I have no excuse"),
        ("05/02/26, 08:21", "Dad", "Start small. Nobody begins at 6000."),
        ("19/03/26, 21:30", "Dad", "The tap in the guest bathroom is dripping again"),
        ("19/03/26, 21:58", "You", "I'll call the plumber tomorrow morning"),
        ("20/03/26, 10:04", "Dad", "He came. Washer had gone. Fixed in ten minutes."),
        ("11/05/26, 16:40", "Dad", "<Media omitted>"),
        ("11/05/26, 16:41", "Dad", "Your mother wanted you to see the garden"),
        ("11/05/26, 17:12", "You", "It looks better than it ever did when I was there"),
        ("02/07/26, 19:25", "Dad", "Call when you get a minute. Nothing urgent."),
    ],
    "Building Society": [
        ("08/01/26, 10:00", "Ramesh (Secretary)", "Reminder: the water tank cleaning is on Sunday. Supply will be off 9am to 1pm."),
        ("08/01/26, 10:14", "Farah", "Thanks for the notice. Can we get this on the board too?"),
        ("08/01/26, 10:16", "Ramesh (Secretary)", "Already up since yesterday."),
        ("08/01/26, 10:52", "Sunil", "Is the lift maintenance the same day?"),
        ("08/01/26, 11:03", "Ramesh (Secretary)", "No, that is the following Saturday."),
        ("22/03/26, 08:30", "Ramesh (Secretary)", "The parking survey closes tonight. Twelve flats have not responded."),
        ("22/03/26, 09:11", "You", "Submitted ours last week"),
        ("22/03/26, 09:40", "Farah", "Same here"),
        ("17/05/26, 13:05", "Sunil", "NOTICE-society-agm.pdf (file attached)"),
        ("17/05/26, 13:06", "Sunil", "AGM minutes, for anyone who missed it"),
        ("17/05/26, 14:22", "Farah", "Much appreciated"),
        ("09/08/26, 07:45", "Ramesh (Secretary)", "Generator test at 7pm today. Expect a short beep, nothing more."),
    ],
    "Trek Crew": [
        ("14/02/26, 22:10", "Kabir", "Right, who is actually coming in April?"),
        ("14/02/26, 22:12", "Meera", "In."),
        ("14/02/26, 22:12", "You", "In, assuming the knee holds"),
        ("14/02/26, 22:19", "Kabir", "That is three. We need five for the permit."),
        ("14/02/26, 22:41", "Nikhil", "Count me in. I will ask Divya."),
        ("06/04/26, 06:02", "Meera", "IMG-20260406-WA0007.jpg (file attached)"),
        ("06/04/26, 06:03", "Meera", "First light from the ridge. Worth every step."),
        ("06/04/26, 06:30", "You", "Unfair. I am at my desk."),
        ("06/04/26, 07:15", "Kabir", "Next year then. Blocking the dates now."),
        ("21/06/26, 12:00", "Nikhil", "Boots finally gave up. Any recommendations?"),
        ("21/06/26, 12:34", "Meera", "Whatever you buy, walk them in for a month first."),
    ],
    "Priya Nair": [
        ("30/01/26, 15:20", "Priya Nair", "Did the invoice go through?"),
        ("30/01/26, 15:44", "You", "Sent this morning. Let me know if it does not show up."),
        ("30/01/26, 16:02", "Priya Nair", "Got it, thanks."),
        ("25/04/26, 09:50", "Priya Nair", "Moving the Thursday call to Friday, same time. Does that work?"),
        ("25/04/26, 10:05", "You", "Friday is fine"),
        ("18/07/26, 17:30", "Priya Nair", "This message was deleted"),
        ("18/07/26, 17:31", "Priya Nair", "Sorry, wrong chat!"),
        ("18/07/26, 17:33", "You", "Happens to all of us"),
    ],
}

# Attachment lines above reference these files; the app looks for them beside
# the .txt, so they are written as real (if minimal) files.
ATTACHMENTS = [
    "IMG-20260414-WA0002.jpg",
    "IMG-20260406-WA0007.jpg",
    "NOTICE-society-agm.pdf",
]


def build_export(messages: list[tuple[str, str, str]]) -> str:
    """Return the full text of one export, header line included."""
    lines = [f"12/01/26, 09:00 - {HEADER}"]
    lines += [f"{ts} - {sender}: {body}" for ts, sender, body in messages]
    return "\n".join(lines) + "\n"


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "demo_exports")
    out.mkdir(parents=True, exist_ok=True)

    for name, messages in CHATS.items():
        path = out / f"WhatsApp Chat with {name}.txt"
        path.write_text(build_export(messages), encoding="utf-8")
        print(f"{path}  ({len(messages)} messages)")

    for filename in ATTACHMENTS:
        (out / filename).write_bytes(TINY_JPEG)
    print(f"{len(ATTACHMENTS)} placeholder attachment(s) written")

    print(f"\nDone. Point the app's watched folder at: {out.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
