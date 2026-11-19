"""F-07 guard: every `uses:` in .github/workflows is pinned to a full commit SHA.

A tag can be moved; a 40-hex commit cannot. Local actions (./...) are exempt.
"""
import re
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parent.parent / ".github" / "workflows"
USES = re.compile(r"^\s*-?\s*uses:\s*(\S+)", re.M)
SHA = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")


def _uses():
    out = []
    for f in sorted(WORKFLOWS.glob("*.y*ml")):
        for ref in USES.findall(f.read_text(encoding="utf-8")):
            out.append((f.name, ref))
    return out


def test_workflows_exist_and_use_actions():
    assert _uses(), "no `uses:` lines found; the guard would pass vacuously"


def test_every_action_is_pinned_to_a_full_sha():
    bad = [(f, r) for f, r in _uses() if not r.startswith("./") and not SHA.match(r)]
    assert not bad, f"actions not pinned to a 40-hex commit SHA: {bad}"


def test_pin_regex_rejects_tags_and_short_shas():
    # Negative: the checker itself must not accept the unpinned forms.
    for ref in ("actions/checkout@v4", "actions/checkout@main", "a/b@" + "a" * 39,
                "a/b@" + "g" * 40):
        assert not SHA.match(ref), ref
    assert SHA.match("a/b/c@" + "0" * 40)


def test_every_pin_carries_a_version_comment():
    text = "\n".join(p.read_text(encoding="utf-8") for p in WORKFLOWS.glob("*.y*ml"))
    for line in text.splitlines():
        if "uses:" in line and "./" not in line:
            assert re.search(r"#\s*v\d", line), f"missing version comment: {line.strip()}"
