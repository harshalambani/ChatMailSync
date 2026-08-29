# -*- mode: python ; coding: utf-8 -*-
#
# PyInstaller build spec for Chat Mail Sync.
#
# Build with:
#   cd "C:\Users\user\Documents\Cowork Playground\ChatMailSync"
#   pyinstaller chat-mail-sync.spec
#
# Output: dist\ChatMailSync\  (--onedir bundle)

import sys
from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs

# ---------------------------------------------------------------------------
# Locate packages so we can pull their data assets
# ---------------------------------------------------------------------------

def pkg_path(name: str) -> Path:
    import importlib
    spec = importlib.util.find_spec(name)
    if spec is None or not spec.origin:
        raise RuntimeError(f"Cannot locate package: {name}")
    return Path(spec.origin).parent


# ---------------------------------------------------------------------------
# Data files (non-Python assets bundled with the exe)
# ---------------------------------------------------------------------------

added_data = []

# customtkinter ships its own theme JSON and image assets
added_data += collect_data_files("customtkinter")


# Ship the user help page next to the exe so the in-app Help button works
added_data += [("help.html", ".")]

# Ship the icon as DATA as well as embedding it in the exe (see icon= below).
# The embedded copy is what Explorer and the Start menu read; a Tk window
# cannot use it and needs a file on disk to hand to iconbitmap(). Same source
# file, two different consumers - and without this one the title bar and
# taskbar fall back to Tk's generic placeholder.
added_data += [("portable/App/AppInfo/appicon.ico", ".")]

# And the same mark as a PNG, for the masthead the window draws at the top of
# itself. A third consumer, needing a third format: Tk's PhotoImage reads PNG
# natively and cannot read .ico at all, and Pillow - which could convert one -
# is in the excludes list below on purpose. 75px so it can be halved to 38
# rather than scaled up, which Tk's zoom() would do without interpolation.
added_data += [("portable/App/AppInfo/appicon_75.png", ".")]

# ---------------------------------------------------------------------------
# Binary files (native shared libraries / DLLs)
# ---------------------------------------------------------------------------

added_binaries = []

# tkinterdnd2 ships a native DLL per platform
added_binaries += collect_dynamic_libs("tkinterdnd2")

# ---------------------------------------------------------------------------
# Hidden imports that PyInstaller's static analyser misses
# ---------------------------------------------------------------------------

hidden_imports = [
    # The Google client libraries were listed here until v2.0.0 removed the
    # OAuth backend. They are no longer installed, and PyInstaller warns on a
    # hidden import it cannot resolve. See docs/RESTORING-OAUTH.md.
    # tkinterdnd2 internal
    "tkinterdnd2",
]

# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

a = Analysis(
    ["gui.py"],                 # entry point — GUI mode
    pathex=["."],
    binaries=added_binaries,
    datas=added_data,
    hiddenimports=hidden_imports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "matplotlib",
        "numpy",
        "pandas",
        "PIL",
        "scipy",
        "PyQt5",
        "wx",
    ],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,      # --onedir: binaries go in the COLLECT step
    name="ChatMailSync",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,              # no console window (GUI app)
    icon="portable/App/AppInfo/appicon.ico",
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="ChatMailSync",          # dist\ChatMailSync\
)
