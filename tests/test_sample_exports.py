"""STO-10: the committed sample zips are exactly what the generator writes."""

import importlib.util
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / "docs" / "sample"


def _load():
    spec = importlib.util.spec_from_file_location(
        "make_sample_exports", ROOT / "tools" / "make_sample_exports.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_committed_zips_match_the_generator(tmp_path):
    made = _load().build(tmp_path)
    assert len(made) == 2
    for path in made:
        assert (SAMPLE / path.name).read_bytes() == path.read_bytes(), path.name


def test_generator_is_deterministic(tmp_path):
    mod = _load()
    a = [p.read_bytes() for p in mod.build(tmp_path / "a")]
    b = [p.read_bytes() for p in mod.build(tmp_path / "b")]
    assert a == b


def test_samples_hold_no_address_or_metadata():
    for z in SAMPLE.glob("*.zip"):
        with zipfile.ZipFile(z) as zf:
            for name in zf.namelist():
                data = zf.read(name)
                if name.endswith(".txt"):
                    assert b"@" not in data, z.name
                else:
                    for marker in (b"Exif", b"eXIf", b"tEXt", b"tIME", b"GPS"):
                        assert marker not in data, (z.name, name)
