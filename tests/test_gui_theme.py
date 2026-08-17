"""The two front-ends must be the same colour.

gui_theme.py is a hand-copy of ChatMailTheme.kt, and a hand-copy rots the
moment somebody adjusts one file and not the other -- which is precisely the
failure PLATFORM-PARITY.md exists to stop, and precisely the sort of drift no
reviewer catches by eye across two languages. So this reads the Kotlin source
and compares it, role by role, to the Python.

It does not import customtkinter: the palette has to stay assertable on the
Linux CI runner, which has no Tk.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

import gui_theme

KOTLIN_THEME = (
    Path(__file__).resolve().parents[1]
    / "android/app/src/main/java/com/chatmailsync/app/ChatMailTheme.kt"
)


# --------------------------------------------------------------------------
# Reading the Kotlin
# --------------------------------------------------------------------------

_NAMED = {"Color.White": "#FFFFFF", "Color.Black": "#000000"}


def _to_hex(literal: str) -> str:
    """`Color(0xFF2E4374)` / `Color.White` / a brand val name -> `#2E4374`."""
    literal = literal.strip()
    if literal in _NAMED:
        return _NAMED[literal]
    m = re.fullmatch(r"Color\(0x(FF)?([0-9A-Fa-f]{6})\)", literal)
    if m:
        return "#" + m.group(2).upper()
    return _BRAND[literal]


def _brand_vals(source: str) -> "dict[str, str]":
    """The `private val PostmarkBlue = Color(0xFF2E4374)` declarations."""
    out = {}
    for name, literal in re.findall(
        r"^private val (\w+) = (Color\([^)]*\)|Color\.\w+)", source, re.MULTILINE
    ):
        out[name] = _to_hex(literal)
    return out


def _scheme(source: str, func: str) -> "dict[str, str]":
    """The `role = <colour>,` pairs inside one *ColorScheme( ... ) call."""
    # Anchored on the assignment, not on the bare name: the file's own KDoc
    # discusses "lightColorScheme()/darkColorScheme()" in prose, and matching
    # that empty pair parses a scheme with no roles in it -- which then makes
    # every comparison below fail with a KeyError instead of a diff.
    start = source.index("= " + func + "(") + 2
    depth, i = 0, start + len(func)
    for i in range(start + len(func), len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                break
    body = source[start + len(func) + 1 : i]
    # Strip comments first: the file is heavily annotated and a `//` line can
    # otherwise contribute a bogus `x = y` match.
    body = re.sub(r"//[^\n]*", "", body)
    return {
        role: _to_hex(literal)
        for role, literal in re.findall(
            r"(\w+)\s*=\s*(Color\([^)]*\)|Color\.\w+|\w+)\s*,", body
        )
    }


_SOURCE = KOTLIN_THEME.read_text(encoding="utf-8")
_BRAND = _brand_vals(_SOURCE)
_LIGHT = _scheme(_SOURCE, "lightColorScheme")
_DARK = _scheme(_SOURCE, "darkColorScheme")


# Kotlin role name -> the gui_theme attribute that mirrors it. Roles with no
# desktop counterpart (surfaceTint, scrim, inversePrimary, the surfaceDim /
# surfaceBright ramp ends) are deliberately absent: CustomTkinter has no
# elevation tint and no scrim, so mirroring them would be inventing a promise
# neither side keeps.
ROLE_MAP = {
    "primary": "PRIMARY",
    "onPrimary": "ON_PRIMARY",
    "primaryContainer": "PRIMARY_CONTAINER",
    "onPrimaryContainer": "ON_PRIMARY_CONTAINER",
    "secondary": "SECONDARY",
    "onSecondary": "ON_SECONDARY",
    "secondaryContainer": "SECONDARY_CONTAINER",
    "onSecondaryContainer": "ON_SECONDARY_CONTAINER",
    "tertiary": "TERTIARY",
    "onTertiary": "ON_TERTIARY",
    "tertiaryContainer": "TERTIARY_CONTAINER",
    "onTertiaryContainer": "ON_TERTIARY_CONTAINER",
    "error": "ERROR",
    "onError": "ON_ERROR",
    "errorContainer": "ERROR_CONTAINER",
    "onErrorContainer": "ON_ERROR_CONTAINER",
    "background": "BACKGROUND",
    "onBackground": "ON_BACKGROUND",
    "surface": "SURFACE",
    "onSurface": "ON_SURFACE",
    "surfaceContainerLowest": "SURFACE_CONTAINER_LOWEST",
    "surfaceContainerLow": "SURFACE_CONTAINER_LOW",
    "surfaceContainer": "SURFACE_CONTAINER",
    "surfaceContainerHigh": "SURFACE_CONTAINER_HIGH",
    "surfaceContainerHighest": "SURFACE_CONTAINER_HIGHEST",
    "surfaceVariant": "SURFACE_VARIANT",
    "onSurfaceVariant": "ON_SURFACE_VARIANT",
    "outline": "OUTLINE",
    "outlineVariant": "OUTLINE_VARIANT",
    "inverseSurface": "INVERSE_SURFACE",
    "inverseOnSurface": "INVERSE_ON_SURFACE",
}


def test_the_kotlin_actually_parsed():
    # Every assertion below is vacuously true if the regexes silently matched
    # nothing, so pin the shape of what was read before trusting it.
    assert _BRAND["PostmarkBlue"] == "#2E4374"
    assert len(_LIGHT) > 30 and len(_DARK) > 30


@pytest.mark.parametrize("role, token", sorted(ROLE_MAP.items()))
def test_windows_palette_matches_android(role, token):
    light, dark = getattr(gui_theme, token)
    assert (light.upper(), dark.upper()) == (_LIGHT[role], _DARK[role]), (
        f"{token} has drifted from ChatMailTheme.kt's {role}. Change the "
        f"Kotlin and the Python in the same commit."
    )


def test_brand_constants_match():
    assert gui_theme.POSTMARK_BLUE.upper() == _BRAND["PostmarkBlue"]
    assert gui_theme.POSTMARK_BLUE_LIGHT.upper() == _BRAND["PostmarkBlueLight"]
    assert gui_theme.GRAPHITE.upper() == _BRAND["Graphite"]
    assert gui_theme.OXBLOOD.upper() == _BRAND["Oxblood"]
    assert gui_theme.ARCHIVE_GREEN.upper() == _BRAND["ArchiveGreen"]


# --------------------------------------------------------------------------
# The palette's own invariants
# --------------------------------------------------------------------------


def _tokens():
    for name in dir(gui_theme):
        if name.isupper() and not name.startswith("_"):
            yield name, getattr(gui_theme, name)


def test_every_colour_token_is_a_light_dark_pair_of_hex():
    # A bare "#2E4374" instead of a pair renders identically in both modes,
    # which is exactly the old gui.py bug: the status dots did not react to
    # the theme switch at all.
    skip = {
        "STATUS_COLOR", "STATUS_COLOR_ON_BAND", "SERIF_FAMILY", "SERIF_FALLBACKS",
    }
    checked = 0
    for name, value in _tokens():
        if name in skip or isinstance(value, str):
            continue
        assert isinstance(value, tuple) and len(value) == 2, name
        for hexcode in value:
            assert re.fullmatch(r"#[0-9A-Fa-f]{6}", hexcode), f"{name}: {hexcode}"
        checked += 1
    assert checked > 25, "token sweep found almost nothing -- did they get renamed?"


def test_status_colours_come_from_the_palette_not_from_nowhere():
    # The four codes this replaced (#2ecc71, #e74c3c, #f39c12, #7f8c8d) were
    # a different palette from the rest of the window.
    assert gui_theme.STATUS_COLOR["complete"] == gui_theme.TERTIARY
    assert gui_theme.STATUS_COLOR["failed"] == gui_theme.ERROR
    assert gui_theme.STATUS_COLOR["pending"] == gui_theme.AMBER
    assert gui_theme.STATUS_COLOR[None] == gui_theme.OUTLINE


def test_band_status_colours_are_the_flipped_pair():
    # The masthead is `primary`: dark in light mode, light in dark mode -- the
    # one surface whose lightness runs opposite the page. A dot painted with
    # the page's own status colour lands dark-on-dark and vanishes.
    for state, pair in gui_theme.STATUS_COLOR.items():
        assert gui_theme.STATUS_COLOR_ON_BAND[state] == (pair[1], pair[0])


def test_nothing_on_the_band_is_painted_in_primary():
    # The trap this guards: CustomTkinter's default button fill is now
    # gui_theme.PRIMARY, so any band control left at its default is a
    # primary rectangle on a primary strip -- present, clickable, invisible.
    for token in ("BAND_BUTTON_FG", "BAND_BUTTON_HOVER", "BAND_GHOST_HOVER"):
        assert getattr(gui_theme, token) != gui_theme.PRIMARY, token
    assert gui_theme.BAND_BUTTON_TEXT != gui_theme.BAND_BUTTON_FG


def test_apply_theme_overwrites_the_builtin_and_keeps_it_json_shaped():
    # CustomTkinter indexes colours as theme[widget][option][0 or 1], so a
    # tuple that never got listified would still work by luck here and then
    # break save_theme(); and a missing widget class means that widget keeps
    # stock CustomTkinter blue.
    loaded = {}

    class _FakeThemeManager:
        theme = loaded

    class _FakeCtk:
        ThemeManager = _FakeThemeManager

        @staticmethod
        def set_default_color_theme(name):
            loaded.clear()
            loaded.update({"CTkButton": {"fg_color": ["#3B8ED0", "#1F6AA5"]}})

    gui_theme.apply_theme(_FakeCtk)

    assert loaded["CTkButton"]["fg_color"] == list(gui_theme.PRIMARY)
    assert loaded["CTk"]["fg_color"] == list(gui_theme.BACKGROUND)
    for widget, options in loaded.items():
        for option, value in options.items():
            assert not isinstance(value, tuple), f"{widget}.{option} is a tuple"


def test_apply_theme_loads_a_builtin_first():
    # Without the base load, any option CustomTkinter adds in a future version
    # would be missing rather than merely stale.
    seen = []

    class _FakeThemeManager:
        theme = {}

    class _FakeCtk:
        ThemeManager = _FakeThemeManager

        @staticmethod
        def set_default_color_theme(name):
            seen.append(name)

    gui_theme.apply_theme(_FakeCtk)
    assert seen == ["blue"]
