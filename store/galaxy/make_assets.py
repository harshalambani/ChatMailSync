"""Galaxy Store visual assets, derived from the shipping app icon.

Nothing here is drawn freehand: the icon is the 1024 source downscaled once
with LANCZOS (an upscale would be dishonest at 512), and the feature graphic
is that same mark on the icon's own navy, so the listing and the launcher
agree. Re-run this script rather than editing the PNGs by hand.
"""
import os
from PIL import Image, ImageDraw, ImageFont

SRC = 'portable/App/AppInfo/appicon_1024.png'
OUT = 'store/galaxy'
NAVY = (20, 52, 92)          # sampled from the icon's own field
WHITE = (255, 255, 255)
MUTED = (168, 186, 210)

os.makedirs(OUT, exist_ok=True)
icon = Image.open(SRC).convert('RGBA')

# --- 512x512 store icon -----------------------------------------------------
# One LANCZOS step from the 1024 source. Alpha is kept: the corners are
# transparent by design and every store masks the icon itself.
icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon_512.png'))

# Some portals reject alpha outright. Same downscale, composited on the icon's
# own navy so the rounded corners fill with the field colour rather than white.
flat = Image.new('RGB', (512, 512), NAVY)
r = icon.resize((512, 512), Image.LANCZOS)
flat.paste(r, (0, 0), r)
flat.save(os.path.join(OUT, 'icon_512_opaque.png'))

# --- 1024x500 feature graphic ----------------------------------------------
# Deliberately quiet: mark, name, one line of what it does. A banner is seen at
# thumbnail size in a shelf, so anything smaller than the strapline is wasted.
W, H = 1024, 500
fg = Image.new('RGB', (W, H), NAVY)
d = ImageDraw.Draw(fg)

# A slightly lighter band behind the mark, so the banner is not a flat slab.
d.rectangle([0, 0, 344, H], fill=(26, 63, 108))

mark = icon.resize((216, 216), Image.LANCZOS)
fg.paste(mark, (64, (H - 216) // 2), mark)

title = ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf', 60)
sub = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 26)
small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 22)

x = 404
RIGHT = W - 40
lines = [
    (168, 'Chat Mail Sync', title, WHITE),
    (250, 'Your WhatsApp chats, filed in your own mailbox.', sub, (214, 226, 242)),
    (300, 'One way. No account with us. Nothing leaves the', small, MUTED),
    (332, 'phone except the mail you send to yourself.', small, MUTED),
]
for y, s, font, fill in lines:
    w = d.textlength(s, font=font)
    assert x + w <= RIGHT, ('OVERFLOW', s, x + w, RIGHT)
    d.text((x, y), s, font=font, fill=fill)

fg.save(os.path.join(OUT, 'feature_graphic_1024x500.png'))

for f in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, f)
    if f.endswith('.png'):
        print(f, Image.open(p).size, os.path.getsize(p), 'bytes')
