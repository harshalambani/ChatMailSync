"""Resolve the desktop app's version for display in the UI.

Deliberately a *resolver*, not a constant. The version already lives in three
places -- `versionName` in android/app/build.gradle.kts, `DisplayVersion` in
portable/App/AppInfo/appinfo.ini, and the git tag -- and adding a fourth that
nothing checks is how a UI ends up confidently showing a stale number. That is
not hypothetical here: the Android settings screen hardcoded "(dev build)" and
went on saying it on a release-signed 1.0.1.

So this reads appinfo.ini, which build_portable.ps1 already calls "the
single source of truth for what this build is" -- it is what names the package
and what the installer stamps. Nothing to keep in sync, because there is
nothing new to be out of sync with.

Android needs none of this: BuildConfig.VERSION_NAME is generated from the
gradle file at build time, so it cannot drift.

When the version cannot be established -- running from a source checkout, or a
bundle assembled by hand without AppInfo\\ -- this returns "development build"
rather than a guess. An honest absence beats a plausible wrong number, which is
the whole point of not caching one.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# DisplayVersion=1.0.1 -- tolerant of surrounding whitespace, but anchored to
# the start of a line so a commented-out or quoted mention elsewhere in the
# file cannot match.
_DISPLAY_VERSION_RE = re.compile(r"^\s*DisplayVersion\s*=\s*(\S+)\s*$", re.MULTILINE)

FALLBACK = "development build"


def read_display_version(ini_path: Path) -> str | None:
    """Return DisplayVersion from a PortableApps appinfo.ini, or None.

    None covers every failure the same way -- missing file, unreadable file,
    no DisplayVersion key -- because the caller's response to all of them is
    identical: say "development build" instead of inventing a number.
    """
    try:
        text = ini_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = _DISPLAY_VERSION_RE.search(text)
    return match.group(1) if match else None


def _candidate_ini_paths() -> list[Path]:
    """Where appinfo.ini can be, most authoritative first.

    In a built package the layout is fixed by the PortableApps.com Format and
    by build_portable.ps1:58-60 -- the frozen exe sits in App\\ChatMailSync\\ and
    the metadata in App\\AppInfo\\ -- so it is one hop up and across from
    sys.executable.

    A source checkout is deliberately NOT searched, even though
    portable/App/AppInfo/appinfo.ini exists there. That file says what the next
    build will be called, not what is running: a working tree can sit any number
    of commits past it. "development build" is the true answer when running from
    source, and it is the answer that cannot quietly go stale.
    """
    if not getattr(sys, "frozen", False):
        return []
    exe_dir = Path(sys.executable).parent              # App\ChatMailSync\
    return [
        exe_dir.parent / "AppInfo" / "appinfo.ini",
        # A onedir bundle run outside the PortableApps wrapper.
        exe_dir / "AppInfo" / "appinfo.ini",
    ]


def app_version() -> str:
    """The version to show a user, e.g. "1.0.1", or "development build"."""
    for path in _candidate_ini_paths():
        version = read_display_version(path)
        if version:
            return version
    return FALLBACK


def version_label() -> str:
    """One line for an About section, on either side of the frozen/source split."""
    version = app_version()
    if version == FALLBACK:
        return "Chat Mail Sync - development build"
    return f"Chat Mail Sync {version}"
