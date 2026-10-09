"""PAR-08: the media golden must not depend on the MIME table of the machine generating it."""

from __future__ import annotations

import json
import mimetypes
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"
CHECKER = ROOT / "tools" / "check_golden_fixtures.py"

sys.path.insert(0, str(ROOT / "tools"))
import generate_kotlin_core_golden_fixtures as gen  # noqa: E402


def _regenerate(out: Path) -> Path:
    out.mkdir()
    old = gen.GOLDEN_DIR
    gen.GOLDEN_DIR = out
    try:
        gen.generate_media_extractor_golden()
    finally:
        gen.GOLDEN_DIR = old
    return out / "media_extractor_golden.json"


def _check(fresh: Path, committed: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(fresh), str(committed)],
        capture_output=True, text=True, check=False,
    )


def _dir_with(tmp: Path, name: str, text: str) -> Path:
    d = tmp / name
    d.mkdir()
    (d / "media_extractor_golden.json").write_text(text, encoding="utf-8", newline="\n")
    return d


@pytest.fixture
def polluted_mime_table(monkeypatch):
    """What a Windows registry (or a system mime.types) can do to the module-level table."""
    mimetypes.init()
    db = mimetypes._db
    assert db is not None
    saved = (dict(db.types_map[True]), dict(db.types_map[False]))
    mimetypes.add_type("audio/mp4", ".m4a")
    mimetypes.add_type("application/octet-stream", ".m4a")  # Windows-style entry
    mimetypes.add_type("image/pjpeg", ".jpg")
    mimetypes.add_type("application/x-pdf", ".pdf")
    yield
    db.types_map[True].clear(); db.types_map[True].update(saved[0])
    db.types_map[False].clear(); db.types_map[False].update(saved[1])


def test_regeneration_ignores_a_polluted_machine_mime_table(tmp_path, polluted_mime_table):
    # Prove the pollution is real for the unpatched call, so the test cannot pass vacuously.
    assert mimetypes.guess_type("x.jpg")[0] == "image/pjpeg"
    fresh = _regenerate(tmp_path / "fresh")
    committed = GOLDEN / "media_extractor_golden.json"
    assert fresh.read_text(encoding="utf-8") == committed.read_text(encoding="utf-8")
    result = _check(fresh.parent, _dir_with(tmp_path, "c", committed.read_text(encoding="utf-8")))
    assert result.returncode == 0, result.stdout + result.stderr


def test_m4a_is_pinned_unmapped_and_every_golden_extension_is_covered():
    data = json.loads((GOLDEN / "media_extractor_golden.json").read_text(encoding="utf-8"))
    guess = gen.pinned_media_guess_type()
    assert guess("voice_note.m4a")[0] is None
    for case in data["zip"]["queries"] + data["plainFile"]["queries"]:
        if not case["found"]:
            continue
        expected = case["mimeType"]
        got = guess(case["filename"])[0] or "application/octet-stream"
        assert got == expected, case["filename"]


def test_a_genuinely_stale_golden_still_fails(tmp_path):
    committed_text = (GOLDEN / "media_extractor_golden.json").read_text(encoding="utf-8")
    assert '"mimeType": "video/mp4"' in committed_text
    stale = committed_text.replace('"mimeType": "video/mp4"', '"mimeType": "video/x-wrong"', 1)
    fresh = _regenerate(tmp_path / "fresh")
    result = _check(fresh.parent, _dir_with(tmp_path, "stale", stale))
    assert result.returncode == 1, result.stdout + result.stderr
    assert "media_extractor_golden.json" in result.stdout
