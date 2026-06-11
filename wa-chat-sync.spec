# -*- mode: python ; coding: utf-8 -*-
#
# PyInstaller build spec for WA Chat Sync to Gmail.
#
# Build with:
#   cd "C:\Users\user\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
#   pyinstaller wa-chat-sync.spec
#
# Output: dist\WAGmailSync\  (--onedir bundle)

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

# Google API client ships discovery cache JSON
added_data += collect_data_files("googleapiclient")

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
    # google-auth / oauthlib
    "google.auth.transport.requests",
    "google_auth_oauthlib.flow",
    # google-api-python-client discovery
    "googleapiclient.discovery",
    "googleapiclient.http",
    # httplib2 + google-auth-httplib2 are hard deps of google-api-python-client
    # but PyInstaller's static analyser misses them (dynamic import in build_service).
    "httplib2",
    "google_auth_httplib2",
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
    name="WAGmailSync",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,              # no console window (GUI app)
    icon=None,                  # set to "portable/App/AppInfo/appicon.ico" once available
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="WAGmailSync",         # dist\WAGmailSync\
)
