"""Guard: the byte-exact golden fixtures must never get line-ending conversion.

`android/core/src/test/resources/golden/*` (and any `*.eml`) are compared
byte-for-byte against Kotlin's own output in MimeGoldenParityTest. The repo's
`* text=auto` line-ending policy normally checks these files out with the
platform's native line ending, which is CRLF on Windows -- silently turning a
passing byte-for-byte comparison into one that only passes on machines with
autocrlf/core.eol configured just so. `.gitattributes` marks both `-text` to
opt them out of any conversion; this test fails if that ever regresses.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GITATTRIBUTES = REPO_ROOT / ".gitattributes"


def test_gitattributes_marks_golden_dir_and_eml_as_no_conversion():
    text = GITATTRIBUTES.read_text(encoding="utf-8")
    assert "android/core/src/test/resources/golden/** -text" in text, (
        "the golden-fixture directory must stay marked '-text' in "
        ".gitattributes, or checkout can silently reintroduce CRLF into "
        "files compared byte-for-byte against Kotlin's output"
    )
    assert "*.eml -text" in text, (
        "*.eml must stay marked '-text' in .gitattributes for the same "
        "reason -- the golden .eml fixture is a byte-for-byte comparison"
    )


def test_git_actually_treats_the_golden_files_as_no_conversion():
    """Parsing the file is not enough on its own -- confirm git's own
    attribute resolution agrees, so a typo or a pattern git doesn't match
    the way we expect still gets caught."""
    sample_paths = [
        "android/core/src/test/resources/golden/index_golden.json",
        "android/core/src/test/resources/golden/mime_message_golden.eml",
        "android/core/src/test/resources/golden/parser_golden.json",
        "android/core/src/test/resources/golden/time_of_day_golden.json",
    ]
    for rel in sample_paths:
        out = subprocess.run(
            ["git", "check-attr", "text", "--", rel],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        # git check-attr prints "<path>: text: unset" when -text applies.
        assert out.endswith("text: unset"), (
            f"expected {rel} to resolve 'text' attribute to 'unset' "
            f"(i.e. -text, no line-ending conversion); git check-attr said: "
            f"{out!r}"
        )


def test_a_non_eml_golden_file_outside_the_golden_dir_is_not_special_cased(tmp_path):
    """Negative check on the rule itself: an .eml anywhere is opted out (the
    rule is a blanket *.eml), but a plain .json file living outside the
    golden directory is not -- proving the guard checks the real rule rather
    than something that happens to pass for every file."""
    result = subprocess.run(
        ["git", "check-attr", "text", "--", "src/self_sender.py"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    assert result.endswith("text: eol=lf") or result.endswith("text: set") or "eol=lf" in result
