# Galaxy Store submission assets

Everything the Seller Portal asks for, kept in the repo so the listing can be
rebuilt rather than remembered. Nothing here is submitted yet.

| File | What it is |
| --- | --- |
| `make_assets.py` | Regenerates the two PNGs below from the shipping app icon. Run it from the repo root: `python store/galaxy/make_assets.py`. Requires Pillow. |
| `icon_512.png` | 512x512 store icon, one LANCZOS step down from `portable/App/AppInfo/appicon_1024.png`. Alpha kept. |
| `icon_512_opaque.png` | The same, flattened on the icon's own navy, for a portal that rejects alpha. |
| `feature_graphic_1024x500.png` | 1024x500 banner: mark, name, one line of what the app does. |
| `listing-copy.md` | App name, short description, long description, and notes for whoever fills the portal in. |

Edit `make_assets.py` and re-run it rather than touching the PNGs by hand -- the
whole point of the script is that the listing art and the launcher icon cannot
drift apart. The text in the feature graphic is measured against the canvas at
render time and the script fails rather than clipping.

## Still outstanding

- **Screenshots.** At least four, from a real arm64 device (no emulator can run
  this APK), on a clean state that shows no real chat names.
- **Support email.** The listing shows one publicly and forever; the address is
  being created separately and is not in `listing-copy.md` yet.
