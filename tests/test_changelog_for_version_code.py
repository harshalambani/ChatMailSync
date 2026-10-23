"""Guard: every versionCode has a store changelog.

F-Droid and the Play-style stores read fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
for the release notes. Bumping versionCode in android/app/build.gradle.kts without adding
that file ships a release with no notes, and nothing else notices.

This fails loudly (never skips) if versionCode cannot be parsed, if the file is missing,
or if it is empty.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = REPO_ROOT / "android" / "app" / "build.gradle.kts"
CHANGELOG_DIR = REPO_ROOT / "fastlane" / "metadata" / "android" / "en-US" / "changelogs"

_VERSION_CODE_RE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)


def _version_code() -> int:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    matches = _VERSION_CODE_RE.findall(text)
    assert len(matches) == 1, (
        f"expected exactly one 'versionCode = <number>' line in {GRADLE_FILE}, "
        f"found {len(matches)}; update this test if the gradle file was restructured"
    )
    return int(matches[0])


def test_changelog_exists_for_current_version_code() -> None:
    code = _version_code()
    path = CHANGELOG_DIR / f"{code}.txt"
    assert path.is_file(), f"missing store changelog for versionCode {code}: {path}"
    assert path.read_text(encoding="utf-8").strip(), (
        f"store changelog for versionCode {code} is empty: {path}"
    )
