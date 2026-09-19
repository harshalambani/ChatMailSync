"""Guard: the repo owner's real surname must never appear in sample data.

Older tests used the owner's real name (first + last) as stand-in chat/sender
data -- realistic-looking, but it is the maintainer's actual legal surname,
not a fictional one, and it does not belong in test fixtures. This test walks
every tracked, non-excluded file and fails if that surname shows up again.

The surname itself is deliberately not written out as a literal in this file
(so grep-for-the-name style scrubs of the source tree don't also have to
touch this guard, and so nobody can restore the name here by mistake) --
it is assembled from two pieces at runtime instead.

A handful of files are excluded, and are excluded deliberately, not by
oversight:

* Dated planning/history docs (root ``YYYY-MM-DD-*.md``, everything under
  ``Completed/``) and fastlane changelogs are a historical record; rewriting
  them would falsify what actually happened. Same exclusion the scrub PR
  itself used.
* ``README.md`` / ``CHANGELOG.md`` are being edited by a different PR.
* A short list of files that use the surname as part of two *real*, still-live
  identifiers rather than as sample personal data: the owner's actual GitHub
  username (as it appears in repo URLs), and the owner's actual domain name.
  Renaming those would break real links, not scrub test data.
  ``docs/RELEASING.md`` also names the actual legal entity registered with
  the app-store account, which is factual release-process documentation, not
  sample data. These are listed explicitly below so the exclusion is visible
  and reviewable, rather than a silent blanket skip.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent

# Built from parts on purpose -- see module docstring.
_SURNAME = "Amb" + "ani"

# Historical-record files: never rewritten, per the scrub PR's own scope.
_DATED_DOC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}-.*\.md$")
_HISTORY_DIR_PREFIXES = ("Completed/",)
_FASTLANE_CHANGELOG_PREFIX = "fastlane/metadata/"
_FASTLANE_CHANGELOG_SEGMENT = "/changelogs/"

# Being edited by a separate PR.
_UNTOUCHED_BY_THIS_PR = {"README.md", "CHANGELOG.md"}

# Files where the surname is part of a real, live identifier (GitHub
# username in a repo URL, or the owner's real domain) or of real
# app-store-account documentation -- not sample/test data.
_REAL_IDENTIFIER_FILES = {
    "docs/CNAME",
    "docs/RELEASING.md",
    "docs/index.html",
    "docs/privacy.html",
    "docs/user-guide.md",
    "fastlane/metadata/android/en-US/full_description.txt",
    "store/galaxy/listing-copy.md",
    "android/app/src/main/java/com/chatmailsync/app/HelpScreen.kt",
    "android/app/src/main/java/com/chatmailsync/app/PrivacyScreen.kt",
    "android/app/src/main/java/com/chatmailsync/app/SettingsScreen.kt",
    "android/app/src/test/java/com/chatmailsync/app/HelpLinkTest.kt",
}

_SKIP_DIR_PREFIXES = (
    ".git/",
    "auth/",
    "data/",
    "Issues/",
    "device-work/",
    "Claude outputs/",
)


def _is_excluded(rel_path: str) -> bool:
    if rel_path in _UNTOUCHED_BY_THIS_PR:
        return True
    if rel_path in _REAL_IDENTIFIER_FILES:
        return True
    if rel_path.startswith(_HISTORY_DIR_PREFIXES):
        return True
    if "/" not in rel_path and _DATED_DOC_RE.match(rel_path):
        return True
    if rel_path.startswith(_FASTLANE_CHANGELOG_PREFIX) and _FASTLANE_CHANGELOG_SEGMENT in rel_path:
        return True
    for prefix in _SKIP_DIR_PREFIXES:
        if rel_path.startswith(prefix):
            return True
    return False


def _tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return [line for line in out.stdout.splitlines() if line]


def _scan(paths: list[Path], base: Path) -> list[str]:
    """Return "relpath:lineno-or-<binary>" for every hit, case-insensitive."""
    hits = []
    needle = _SURNAME.lower()
    for path in paths:
        rel = str(path.relative_to(base)).replace("\\", "/")
        try:
            data = path.read_bytes()
        except OSError:
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            if needle.encode("utf-8") in data.lower():
                hits.append(f"{rel}:<binary>")
            continue
        for lineno, line in enumerate(text.splitlines(), start=1):
            if needle in line.lower():
                hits.append(f"{rel}:{lineno}")
    return hits


def test_owner_surname_is_absent_from_tracked_files():
    tracked = _tracked_files()
    candidates = [
        REPO_ROOT / rel for rel in tracked if not _is_excluded(rel)
    ]
    hits = _scan(candidates, REPO_ROOT)
    assert not hits, (
        "the owner's real surname was found in tracked files that are not "
        "on the historical/real-identifier exclusion list "
        "(file:line only, name withheld): " + ", ".join(hits)
    )


def test_guard_actually_detects_the_surname(tmp_path):
    """Prove the scanner isn't vacuously passing: plant the name and see it caught."""
    planted = tmp_path / "planted.txt"
    planted.write_text(f"WhatsApp Chat with Some {_SURNAME}\n", encoding="utf-8")
    hits = _scan([planted], tmp_path)
    assert hits == ["planted.txt:1"]


def test_guard_passes_on_clean_content(tmp_path):
    clean = tmp_path / "clean.txt"
    clean.write_text("WhatsApp Chat with Rohan Mehta\n", encoding="utf-8")
    hits = _scan([clean], tmp_path)
    assert hits == []


@pytest.mark.parametrize("rel", sorted(_REAL_IDENTIFIER_FILES | _UNTOUCHED_BY_THIS_PR))
def test_exclusion_list_entries_still_exist(rel):
    """Catch drift: if one of these paths gets renamed/removed, the exclusion
    list is stale and should be revisited rather than silently doing nothing."""
    assert (REPO_ROOT / rel).exists(), (
        f"{rel} is on the exclusion list but no longer exists in the repo"
    )
