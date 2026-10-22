"""LIC-03: requirements-dev.txt must declare what the dev tools import."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _declared(text: str) -> set:
    names = set()
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.add(re.split(r"[<>=!~\[; ]", line, maxsplit=1)[0].lower())
    return names


def test_pillow_is_declared_for_render_icons():
    assert "from PIL import" in (ROOT / "tools" / "render_icons.py").read_text(encoding="utf-8")
    assert "pillow" in _declared((ROOT / "requirements-dev.txt").read_text(encoding="utf-8"))


def test_pillow_has_a_pinned_minimum():
    text = (ROOT / "requirements-dev.txt").read_text(encoding="utf-8")
    assert re.search(r"(?im)^pillow>=\d+\.\d+", text)


def test_dev_requirements_header_is_not_stale():
    text = (ROOT / "requirements-dev.txt").read_text(encoding="utf-8")
    assert "Chaquopy packages only" not in text


def test_the_declaration_check_can_fail():
    # Negative: a file without Pillow must not count as declaring it.
    assert "pillow" not in _declared("# Pillow in a comment\npytest>=8.0.0\n")
