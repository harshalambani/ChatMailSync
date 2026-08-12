"""Tests for src/app_version.py -- the version shown in the desktop UI.

The thing worth protecting here is the failure mode, not the happy path. The
Android settings screen hardcoded "(dev build)" and kept saying it on a
release-signed 1.0.1; the desktop equivalent must never invent a number it
cannot read. So most of these assert that a missing, unreadable or malformed
appinfo.ini produces "development build" rather than something plausible.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from src import app_version  # noqa: E402


APPINFO = """[Format]
Type=PortableApps.comFormat
Version=3.9

[Details]
Name=Chat Mail Sync Portable
AppID=ChatMailSyncPortable

[Version]
PackageVersion=1.0.1.0
DisplayVersion=1.0.1
"""


def _write_ini(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "appinfo.ini"
    path.write_text(text, encoding="utf-8")
    return path


def test_reads_display_version(tmp_path):
    assert app_version.read_display_version(_write_ini(tmp_path, APPINFO)) == "1.0.1"


def test_prefers_display_version_over_package_version(tmp_path):
    """PackageVersion is the 4-part installer form (1.0.1.0) and appears first
    in the file. DisplayVersion is what build_portable.ps1 treats as the source
    of truth and what a user recognises from the release page."""
    assert app_version.read_display_version(_write_ini(tmp_path, APPINFO)) == "1.0.1"


def test_tolerates_whitespace(tmp_path):
    ini = _write_ini(tmp_path, "[Version]\n  DisplayVersion =  2.3.4  \n")
    assert app_version.read_display_version(ini) == "2.3.4"


def test_ignores_a_mention_that_is_not_a_key(tmp_path):
    """Anchored to the start of a line, so prose or a commented-out entry
    elsewhere in the file cannot be mistaken for the real key."""
    ini = _write_ini(tmp_path, "[Details]\nDescription=Set DisplayVersion=9.9.9 here\n")
    assert app_version.read_display_version(ini) is None


def test_missing_file_returns_none(tmp_path):
    assert app_version.read_display_version(tmp_path / "nope.ini") is None


def test_directory_instead_of_file_returns_none(tmp_path):
    """OSError, not just FileNotFoundError -- reading a directory raises
    IsADirectoryError on Linux and PermissionError on Windows."""
    assert app_version.read_display_version(tmp_path) is None


def test_file_without_display_version_returns_none(tmp_path):
    assert app_version.read_display_version(_write_ini(tmp_path, "[Details]\nName=x\n")) is None


def test_source_checkout_reports_development_build(monkeypatch):
    """Running from source must NOT report the version in the repo's
    appinfo.ini. That file says what the next build will be called, not what is
    running -- the working tree can sit any number of commits past it."""
    monkeypatch.delattr(sys, "frozen", raising=False)
    assert app_version._candidate_ini_paths() == []
    assert app_version.app_version() == app_version.FALLBACK
    assert app_version.version_label() == "Chat Mail Sync - development build"


def test_frozen_looks_beside_the_exe(tmp_path, monkeypatch):
    """The built layout is fixed by the PortableApps.com Format: the exe in
    App\\ChatMailSync\\, the metadata in App\\AppInfo\\."""
    app_dir = tmp_path / "App"
    exe_dir = app_dir / "ChatMailSync"
    exe_dir.mkdir(parents=True)
    info_dir = app_dir / "AppInfo"
    info_dir.mkdir()
    (info_dir / "appinfo.ini").write_text(APPINFO, encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(exe_dir / "ChatMailSync.exe"))

    assert app_version.app_version() == "1.0.1"
    assert app_version.version_label() == "Chat Mail Sync 1.0.1"


def test_frozen_without_appinfo_falls_back(tmp_path, monkeypatch):
    """A bundle assembled by hand, without AppInfo\\. Say so rather than guess."""
    exe_dir = tmp_path / "App" / "ChatMailSync"
    exe_dir.mkdir(parents=True)
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(exe_dir / "ChatMailSync.exe"))

    assert app_version.app_version() == app_version.FALLBACK


@pytest.mark.parametrize("layout", ["portableapps", "standalone"])
def test_both_frozen_layouts(tmp_path, monkeypatch, layout):
    """Also support a onedir bundle run outside the PortableApps wrapper, where
    AppInfo\\ sits next to the exe instead of one level up."""
    exe_dir = tmp_path / "App" / "ChatMailSync"
    exe_dir.mkdir(parents=True)
    parent = exe_dir.parent / "AppInfo" if layout == "portableapps" else exe_dir / "AppInfo"
    parent.mkdir()
    (parent / "appinfo.ini").write_text(APPINFO, encoding="utf-8")

    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(exe_dir / "ChatMailSync.exe"))

    assert app_version.app_version() == "1.0.1"


def test_shipped_appinfo_is_parseable():
    """Guards the real file, not a fixture. If someone edits appinfo.ini into a
    shape this cannot read, the app would silently say "development build" on a
    release build -- exactly the confidently-wrong display this replaced."""
    shipped = Path(__file__).parent.parent / "portable" / "App" / "AppInfo" / "appinfo.ini"
    assert shipped.exists(), "portable/App/AppInfo/appinfo.ini is missing"
    assert app_version.read_display_version(shipped) is not None
