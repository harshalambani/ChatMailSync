"""Render every WA Mail Sync icon asset from one geometry definition.

Run from the repo root:

    cd "C:\\Users\\inabm\\Documents\\Cowork Playground\\WAMailSync"
    python tools\\render_icons.py

Why a script and not a folder of hand-made PNGs
-----------------------------------------------
Before this, every icon in the repo was a PNG and nothing else - there was no
vector master, so no size could be re-cut cleanly and the 1024px file was
itself an upscale. The geometry below is now the single source of truth: it
emits assets/logo.svg for humans to look at AND rasterises every tracked
asset, so the two can never disagree. Change a number here, re-run, commit
what changed.

Deliberately dependency-light: Pillow only, which the project already has.
An SVG rasteriser (cairosvg, Inkscape, ImageMagick) would let the SVG be the
literal input, but adding a native dependency that is present on one machine
and absent on another is exactly how the PA Skills splash shipped the wrong
version number - see the stamping block in build_portable.ps1. Drawing the
shapes twice from shared constants keeps the toolchain to one pure-Python
package.

The mark
--------
A speech bubble descending into an open envelope: chat becomes archived mail,
one way only. The previous logo had a double-headed sync arrow, which promised
two-way sync on a strictly write-only tool, and Gmail red, on a tool that has
not been Gmail-only since the IMAP backend landed. Both are gone.

The bubble's tail sweeps left while the arrow inside it points straight down.
That asymmetry is the point and is not a mistake to be "corrected": a centred
tail made the whole mark mirror-symmetrical and static.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Geometry - all coordinates are in a 512x512 design space
# ---------------------------------------------------------------------------

CANVAS = 512

NAVY = (20, 52, 92)          # #14345C - Oxford navy
WHITE = (255, 255, 255)
CLEAR = (0, 0, 0, 0)

GROUND_RADIUS = 112

BUBBLE = (140, 52, 372, 238)  # x0, y0, x1, y1
BUBBLE_RADIUS = 48

# Straight down, centred on the bubble. The direction cue must not be tilted:
# past roughly 25 degrees a chevron stops reading as "down" and starts reading
# as "back", which on a write-only archiver is the one wrong idea to suggest.
CHEVRON = [(206, 118), (256, 168), (306, 118)]
CHEVRON_WIDTH = 34

# Swept left. Base along the bubble's bottom edge, tip pulled to the left so
# the message reads as falling into the envelope rather than resting above it.
TAIL = [(212, 234), (264, 234), (206, 300)]

ENVELOPE_BODY = (84, 296, 428, 428)
ENVELOPE_RADIUS = 40
ENVELOPE_NOTCH = [(84, 296), (428, 296), (256, 400)]

# Adaptive icons reserve the outer third: 72dp visible out of a 108dp canvas.
# Anything outside this shrinks or gets cropped depending on the launcher's
# mask, so the foreground layer is drawn at this scale about the centre.
ADAPTIVE_SAFE = 72 / 108

SUPERSAMPLE = 8   # draw big, downsample once - Pillow has no shape antialiasing


# ---------------------------------------------------------------------------
# Drawing
# ---------------------------------------------------------------------------


class Canvas:
    """A 512-space drawing surface that rasterises supersampled."""

    def __init__(self, size: int, scale: float = 1.0):
        self.px = size * SUPERSAMPLE
        self.k = self.px / CANVAS * scale
        self.pad = (self.px - CANVAS * self.k) / 2   # centre when scale < 1
        self.img = Image.new("RGBA", (self.px, self.px), CLEAR)
        self.d = ImageDraw.Draw(self.img)
        self.size = size

    def _p(self, x: float, y: float) -> tuple[float, float]:
        return (x * self.k + self.pad, y * self.k + self.pad)

    def _box(self, box) -> list[float]:
        x0, y0, x1, y1 = box
        a, b = self._p(x0, y0)
        c, d = self._p(x1, y1)
        return [a, b, c, d]

    def rounded_rect(self, box, radius, fill):
        self.d.rounded_rectangle(self._box(box), radius=radius * self.k, fill=fill)

    def ellipse(self, box, fill):
        self.d.ellipse(self._box(box), fill=fill)

    def polygon(self, points, fill):
        self.d.polygon([self._p(*pt) for pt in points], fill=fill)

    def polyline(self, points, width, fill):
        """Thick polyline with round caps and joins.

        Pillow's line() rounds joins with joint="curve" but never rounds the
        two ends, which at icon scale reads as a chipped-off stroke. Discs at
        every vertex, ends included, give the same result as SVG's
        stroke-linecap/linejoin="round".
        """
        pts = [self._p(*pt) for pt in points]
        w = width * self.k
        self.d.line(pts, fill=fill, width=int(round(w)), joint="curve")
        r = w / 2
        for x, y in pts:
            self.d.ellipse([x - r, y - r, x + r, y + r], fill=fill)

    def finish(self) -> Image.Image:
        return self.img.resize((self.size, self.size), Image.LANCZOS)


def draw_logo(size: int, ground: str = "rounded", scale: float = 1.0) -> Image.Image:
    """Render the mark.

    ground="rounded"  full icon on a rounded-square navy ground
    ground="circle"   same on a circular ground (Android's round mipmap)
    ground="none"     mark only, transparent behind and *through* it - the
                      chevron and the envelope's notch become holes rather
                      than navy shapes, so an adaptive icon's separate
                      background layer shows through them correctly.
    """
    c = Canvas(size, scale)

    if ground == "rounded":
        c.rounded_rect((0, 0, CANVAS, CANVAS), GROUND_RADIUS, NAVY)
    elif ground == "circle":
        c.ellipse((0, 0, CANVAS, CANVAS), NAVY)

    # Cut-outs are painted in the ground colour when there is a ground, and
    # punched to transparent when there is not. ImageDraw writes pixels rather
    # than blending them, so drawing CLEAR genuinely erases.
    cut = CLEAR if ground == "none" else NAVY

    c.rounded_rect(BUBBLE, BUBBLE_RADIUS, WHITE)
    c.polyline(CHEVRON, CHEVRON_WIDTH, cut)
    c.polygon(TAIL, WHITE)
    c.rounded_rect(ENVELOPE_BODY, ENVELOPE_RADIUS, WHITE)
    c.polygon(ENVELOPE_NOTCH, cut)

    return c.finish()


# ---------------------------------------------------------------------------
# SVG master - same numbers, emitted for humans and for any future toolchain
# ---------------------------------------------------------------------------


def svg_source() -> str:
    def pts(points):
        return " ".join(f"{'M' if i == 0 else 'L'}{x} {y}" for i, (x, y) in enumerate(points))

    navy = "#%02X%02X%02X" % NAVY
    bx0, by0, bx1, by1 = BUBBLE
    ex0, ey0, ex1, ey1 = ENVELOPE_BODY
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!-- WA Mail Sync application mark.
     GENERATED by tools/render_icons.py - edit the geometry constants there,
     not this file, or the raster assets will drift away from it. -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}" width="{CANVAS}" height="{CANVAS}">
  <rect width="{CANVAS}" height="{CANVAS}" rx="{GROUND_RADIUS}" fill="{navy}"/>
  <rect x="{bx0}" y="{by0}" width="{bx1 - bx0}" height="{by1 - by0}" rx="{BUBBLE_RADIUS}" fill="#fff"/>
  <path d="{pts(CHEVRON)}" fill="none" stroke="{navy}" stroke-width="{CHEVRON_WIDTH}"
        stroke-linecap="round" stroke-linejoin="round"/>
  <path d="{pts(TAIL)} Z" fill="#fff"/>
  <path d="M{ex0} {ey0} L{ENVELOPE_NOTCH[2][0]} {ENVELOPE_NOTCH[2][1]} L{ex1} {ey0}
           v{ey1 - ey0 - ENVELOPE_RADIUS}
           a{ENVELOPE_RADIUS} {ENVELOPE_RADIUS} 0 0 1 -{ENVELOPE_RADIUS} {ENVELOPE_RADIUS}
           h-{ex1 - ex0 - 2 * ENVELOPE_RADIUS}
           a{ENVELOPE_RADIUS} {ENVELOPE_RADIUS} 0 0 1 -{ENVELOPE_RADIUS} -{ENVELOPE_RADIUS} z" fill="#fff"/>
</svg>
"""


# ---------------------------------------------------------------------------
# Splash - 400x160, drawn to the same layout the old one used
# ---------------------------------------------------------------------------

SPLASH_SIZE = (400, 160)
SPLASH_BG = (27, 36, 48)          # #1B2430
SPLASH_TITLE = (240, 242, 245)
SPLASH_MUTED = (139, 148, 161)    # matches the version stamp in build_portable.ps1


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    path = Path("C:/Windows/Fonts") / name
    if not path.exists():
        raise SystemExit(f"Missing font {path}. The splash cannot be rendered without it.")
    return ImageFont.truetype(str(path), size)


def draw_splash() -> Image.Image:
    img = Image.new("RGB", SPLASH_SIZE, SPLASH_BG)

    # The mark goes on WITHOUT its navy ground. Oxford navy (#14345C) against
    # the splash's #1B2430 is navy on near-navy - the rounded-square tile has
    # almost no edge against the panel and reads as a smudge, so the icon
    # would look broken on the one screen every user sees on every launch.
    # Groundless, the white shapes sit at full contrast and the cut-outs let
    # the panel show through, which is the same figure/ground relationship the
    # launcher icon has - just inverted.
    icon = draw_logo(100, ground="none")
    img.paste(icon, (22, 30), icon)

    d = ImageDraw.Draw(img)
    d.text((154, 46), "WA Mail Sync", font=font("segoeui.ttf", 34), fill=SPLASH_TITLE)
    d.text((156, 96), "Starting...", font=font("segoeui.ttf", 15), fill=SPLASH_MUTED)

    # Nothing may be drawn in the bottom-right corner: build_portable.ps1
    # stamps the version there at package time and does NOT clear the area
    # first, so anything already there shows through the text. The stamp is
    # Segoe UI 9pt placed at (width - textwidth - 10, height - textheight - 7);
    # this reserves a generous box around that and fails the render rather
    # than shipping a splash where the version is unreadable.
    reserved = (300, 128, SPLASH_SIZE[0], SPLASH_SIZE[1])
    if img.crop(reserved).getcolors(maxcolors=4) is None:
        raise SystemExit(
            "Splash bottom-right is not clear - the version stamp would be "
            "drawn over artwork. Move the content, do not relax this check.")
    return img


# ---------------------------------------------------------------------------
# Outputs
# ---------------------------------------------------------------------------

ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    appinfo = root / "portable" / "App" / "AppInfo"
    android_res = root / "android" / "app" / "src" / "main" / "res"
    assets = root / "assets"
    assets.mkdir(exist_ok=True)

    written: list[Path] = []

    def save(img: Image.Image, path: Path, **kw):
        path.parent.mkdir(parents=True, exist_ok=True)
        img.save(path, **kw)
        written.append(path)

    # Vector master
    (assets / "logo.svg").write_text(svg_source(), encoding="utf-8")
    written.append(assets / "logo.svg")

    # Windows / PortableApps
    for px in (1024, 128, 75, 32, 16):
        save(draw_logo(px), appinfo / f"appicon_{px}.png")

    # A multi-size .ico, not a single 256px image: Windows picks the nearest
    # embedded size per surface, and letting it downscale one large bitmap to
    # 16px is exactly how an icon turns to mush in the taskbar.
    base = draw_logo(256)
    save(base, appinfo / "appicon.ico",
         format="ICO", sizes=[(s, s) for s in ICO_SIZES])

    save(draw_splash(), appinfo / "Launcher" / "splash.jpg", quality=95, subsampling=0)

    # Android launcher icons
    save(draw_logo(512), android_res / "mipmap-anydpi" / "ic_launcher.png")
    save(draw_logo(512, ground="circle"), android_res / "mipmap-anydpi" / "ic_launcher_round.png")

    bg = Image.new("RGBA", (512, 512), NAVY + (255,))
    save(bg, android_res / "drawable-nodpi" / "ic_launcher_background.png")
    save(draw_logo(512, ground="none", scale=ADAPTIVE_SAFE),
         android_res / "drawable-nodpi" / "ic_launcher_foreground.png")

    for p in written:
        print(f"  {p.relative_to(root).as_posix():<62} {p.stat().st_size:>8} bytes")
    print(f"\n{len(written)} files written.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
