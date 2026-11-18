"""Regenerate the made-up sample exports in docs/sample/ (STO-10).

The F-Droid reviewer has no WhatsApp history to import, so docs/sample/ holds
small WhatsApp-style export zips whose people and messages are invented. This
script writes the two zips that sit beside the older "Meera Iyer" sample:

    WhatsApp Chat with Garden Club.zip    a group chat, three members
    WhatsApp Chat with Rohan Mehta.zip    a one-to-one chat with two pictures

Everything is made up. The only names are Meera Iyer, Rohan Mehta and Tara
Menon (plus the group title "Garden Club"). There are no phone numbers, email
addresses or places, and the two pictures are plain shapes on a flat colour,
written as minimal PNGs with no EXIF, text or time chunks.

The output is deterministic: the same bytes on every run, on every machine.
Zip entries are stored (not deflated) with a fixed timestamp and fixed
attributes, and the PNG data uses stored deflate blocks, so no compressor
version can change a byte.

The line shape is the one the older sample uses, "[dd/mm/yy, HH:MM:SS] - Name:
text" (the `bracketed_24h_seconds` pattern in the Kotlin core's
TIMESTAMP_PATTERNS), and an attachment is the Android wording
"<file> (file attached)" with an optional caption on the next line.

Usage:
    python tools/make_sample_exports.py [output_dir]

Default output_dir is docs/sample next to this script's repository root.
Standard library only.
"""

import struct
import sys
import zipfile
import zlib
from pathlib import Path

HEADER = (
    "Messages and calls are end-to-end encrypted. No one outside of this chat, "
    "not even WhatsApp, can read or listen to them."
)

# Fixed, so the zips are byte-for-byte reproducible.
ZIP_DATE = (2026, 1, 1, 0, 0, 0)

MEERA = "Meera Iyer"
ROHAN = "Rohan Mehta"
TARA = "Tara Menon"

# (timestamp "dd/mm/yy, HH:MM:SS", sender, text). A text may hold a newline: the
# parser treats the second line as part of the same message.
GROUP_NAME = "Garden Club"
GROUP_MESSAGES = [
    ("14/03/26, 10:00:05", MEERA, "Welcome to the garden club chat, both of you!"),
    ("14/03/26, 10:02:41", ROHAN, "Thanks for setting this up."),
    ("14/03/26, 10:04:12", TARA, "Happy to be here. What do we plant first?"),
    ("14/03/26, 10:06:30", MEERA, "Tomatoes and basil, I think."),
    ("14/03/26, 10:07:02", ROHAN, "I have spare pots we can use."),
    ("15/03/26, 08:20:15", TARA, "Good morning. The soil looks ready."),
    ("15/03/26, 08:25:48", MEERA, "Lovely. I will bring the seeds at noon."),
    ("15/03/26, 08:26:10", ROHAN, "I will bring a watering can."),
    ("15/03/26, 12:40:33", MEERA, "Seeds are in. Row one is tomatoes.\nRow two is basil."),
    ("15/03/26, 12:44:09", TARA, "Perfect. Same time next week?"),
    ("21/03/26, 09:05:00", ROHAN, "Tiny green shoots in row one!"),
    ("21/03/26, 09:07:21", MEERA, "That was quick."),
    ("21/03/26, 09:09:44", TARA, "Let us not water them too much."),
    ("21/03/26, 18:30:12", MEERA, "Thanks everyone. See you next Saturday."),
]
# Two system lines (no "Name: text" shape), written the way a group export does.
GROUP_SYSTEM = [
    ("14/03/26, 09:58:00", 'Meera Iyer created group "Garden Club"'),
    ("14/03/26, 09:58:30", "Meera Iyer added Rohan Mehta"),
]

PICTURE_NAME = ROHAN
PICTURE_MESSAGES = [
    ("12/04/26, 16:10:04", MEERA, "Did the new chairs arrive yet, Rohan?"),
    ("12/04/26, 16:12:30", ROHAN, "Yes, this morning."),
    ("12/04/26, 16:13:02", ROHAN, "IMG-20260412-WA0001.png (file attached)"),
    ("12/04/26, 16:15:44", MEERA, "Nice colour."),
    ("19/04/26, 11:02:18", MEERA, "IMG-20260419-WA0002.png (file attached)\nHere is the shelf I mentioned."),
    ("19/04/26, 11:05:50", ROHAN, "That looks sturdy."),
    ("19/04/26, 11:06:15", MEERA, "It is. Thanks for the advice."),
]
PICTURES = {
    # name -> (background, shape colour, shape kind)
    "IMG-20260412-WA0001.png": ((200, 220, 240), (30, 90, 160), "square"),
    "IMG-20260419-WA0002.png": ((240, 225, 200), (170, 70, 40), "circle"),
}
PIC_W, PIC_H = 80, 60


def chat_text(messages, system=()):
    """Full text of one export: header, any system lines, then the messages."""
    first_ts = min([m[0] for m in messages] + [s[0] for s in system])
    lines = [f"[{first_ts}] - {HEADER}"]
    lines += [f"[{ts}] - {text}" for ts, text in system]
    lines += [f"[{ts}] - {sender}: {text}" for ts, sender, text in messages]
    return "\n".join(lines) + "\n"


def _chunk(kind: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(kind + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)


def _stored_deflate(raw: bytes) -> bytes:
    """A zlib stream of stored (uncompressed) blocks: identical on every machine."""
    out = bytearray(b"\x78\x01")
    pos = 0
    while True:
        block = raw[pos:pos + 65535]
        pos += len(block)
        final = 1 if pos >= len(raw) else 0
        out += struct.pack("<BHH", final, len(block), len(block) ^ 0xFFFF) + block
        if final:
            break
    out += struct.pack(">I", zlib.adler32(raw) & 0xFFFFFFFF)
    return bytes(out)


def make_png(background, colour, kind) -> bytes:
    """A small palette PNG: IHDR, PLTE, IDAT, IEND and nothing else."""
    cx, cy, r = PIC_W // 2, PIC_H // 2, PIC_H // 3
    rows = bytearray()
    for y in range(PIC_H):
        rows.append(0)  # filter type: none
        for x in range(PIC_W):
            if kind == "square":
                inside = abs(x - cx) <= r and abs(y - cy) <= r
            else:
                inside = (x - cx) ** 2 + (y - cy) ** 2 <= r * r
            rows.append(1 if inside else 0)
    ihdr = struct.pack(">IIBBBBB", PIC_W, PIC_H, 8, 3, 0, 0, 0)
    plte = bytes(background) + bytes(colour)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"PLTE", plte)
        + _chunk(b"IDAT", _stored_deflate(bytes(rows)))
        + _chunk(b"IEND", b"")
    )


def write_zip(path: Path, entries) -> None:
    """Write entries [(name, bytes)] in the order given, with fixed metadata."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as zf:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=ZIP_DATE)
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 0
            info.external_attr = 0
            zf.writestr(info, data)


def build(out_dir: Path) -> list:
    out_dir.mkdir(parents=True, exist_ok=True)
    made = []

    name = f"WhatsApp Chat with {GROUP_NAME}"
    path = out_dir / f"{name}.zip"
    write_zip(path, [(f"{name}.txt", chat_text(GROUP_MESSAGES, GROUP_SYSTEM).encode("utf-8"))])
    made.append(path)

    name = f"WhatsApp Chat with {PICTURE_NAME}"
    path = out_dir / f"{name}.zip"
    entries = [(f"{name}.txt", chat_text(PICTURE_MESSAGES).encode("utf-8"))]
    entries += [(pic, make_png(*spec)) for pic, spec in PICTURES.items()]
    write_zip(path, entries)
    made.append(path)
    return made


def main() -> int:
    default = Path(__file__).resolve().parent.parent / "docs" / "sample"
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else default
    for path in build(out):
        print(f"{path}  ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
