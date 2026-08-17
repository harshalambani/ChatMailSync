"""Quiet Archive, for the Windows client.

The Android app has had a deliberate identity since 2026-07-05 -- cool paper
neutrals, graphite ink, one postmark-blue accent -- while the Windows window
was still running CustomTkinter's stock "blue" theme with one-off hex codes
sprinkled through gui.py. Two products, one name. This module is the Windows
half of the same palette.

It is a *mirror*, not a second source of truth: every value below that has a
counterpart in android/app/src/main/java/com/chatmailsync/app/ChatMailTheme.kt
is copied from it verbatim, and tests/test_gui_theme.py parses the Kotlin file
and fails if the two ever drift. When a colour changes, it changes there first
and here second, in the same commit -- PLATFORM-PARITY.md, "fix both
front-ends in the same batch".

Colours are ``(light, dark)`` pairs throughout, which is both how Material
splits a colour scheme and, conveniently, exactly the two-element form
CustomTkinter accepts for every colour option. So a token can be handed
straight to a widget::

    ctk.CTkLabel(parent, text="...", text_color=ON_SURFACE_VARIANT)

and it follows the appearance-mode switch for free.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Brand
# ---------------------------------------------------------------------------

# The named brand colours, in the same order ChatMailTheme.kt declares them.
POSTMARK_BLUE = "#2E4374"
POSTMARK_BLUE_LIGHT = "#93A8D6"
GRAPHITE = "#20242B"
OXBLOOD = "#8C2F2A"
ARCHIVE_GREEN = "#3F6B52"

# ---------------------------------------------------------------------------
# Roles  (light, dark)
# ---------------------------------------------------------------------------

PRIMARY = (POSTMARK_BLUE, POSTMARK_BLUE_LIGHT)
ON_PRIMARY = ("#FFFFFF", "#16233F")
PRIMARY_CONTAINER = ("#DDE4F0", POSTMARK_BLUE)
ON_PRIMARY_CONTAINER = ("#16233F", "#DDE4F0")

SECONDARY = ("#515A6B", "#A6AEBB")
ON_SECONDARY = ("#FFFFFF", "#1B2027")
SECONDARY_CONTAINER = ("#DCE1E9", "#3A4250")
ON_SECONDARY_CONTAINER = ("#1B2027", "#DCE1E9")

TERTIARY = (ARCHIVE_GREEN, "#9FCBB2")
ON_TERTIARY = ("#FFFFFF", "#0B2618")
TERTIARY_CONTAINER = ("#D5E6DC", ARCHIVE_GREEN)
ON_TERTIARY_CONTAINER = ("#17281F", "#D5E6DC")

ERROR = (OXBLOOD, "#F2B8B2")
ON_ERROR = ("#FFFFFF", "#561713")
ERROR_CONTAINER = ("#F6DEDA", OXBLOOD)
ON_ERROR_CONTAINER = ("#3F110E", "#F9DEDC")

BACKGROUND = ("#F5F6F4", "#17191D")
ON_BACKGROUND = (GRAPHITE, "#ECEDE9")

SURFACE = ("#FBFBF9", "#17191D")
ON_SURFACE = (GRAPHITE, "#ECEDE9")
SURFACE_CONTAINER_LOWEST = ("#FFFFFF", "#101216")
SURFACE_CONTAINER_LOW = ("#F5F6F4", "#1B1E23")
SURFACE_CONTAINER = ("#EFF0ED", "#202329")
SURFACE_CONTAINER_HIGH = ("#E9EAE7", "#2A2E35")
SURFACE_CONTAINER_HIGHEST = ("#E3E5E1", "#353A42")

SURFACE_VARIANT = ("#E2E4E6", "#3A3F47")
ON_SURFACE_VARIANT = ("#4B5361", "#C2C8D0")

OUTLINE = ("#767D89", "#8A9199")
OUTLINE_VARIANT = ("#D8DBD6", "#3A3D44")

INVERSE_SURFACE = ("#2F3238", "#ECEDE9")
INVERSE_ON_SURFACE = ("#F1F2EE", "#2F3238")

# ---------------------------------------------------------------------------
# Roles Material does not have, and a desktop needs
# ---------------------------------------------------------------------------

# A pointer has hover; a finger does not, so Compose never needed these and
# every CustomTkinter widget does. Each is its own role's colour taken one
# step further from the page -- darker in light mode, lighter in dark -- so a
# hover always reads as "more", never as a different colour.
PRIMARY_HOVER = ("#24345A", "#7C93C6")
ERROR_HOVER = ("#6E2521", "#E5A099")
TERTIARY_HOVER = ("#325642", "#88BC9E")
# The neutral hover for flat/transparent controls (icon buttons, toolbar
# glyphs): a tint of the page rather than a colour of its own.
NEUTRAL_HOVER = ("#E3E5E1", "#2A2E35")

# The fourth status. Android's chat rows carry three states (synced, failed,
# never synced) and its theme therefore has no amber; the Windows sync panel
# has a fourth, "in progress", and needs a colour for it. Muted to sit in the
# same family as the blue and the oxblood rather than borrowing a stock
# warning yellow, which is the sort of thing that made the old palette look
# like four different apps.
AMBER = ("#8A6420", "#E3BE7E")

# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

# Replaces gui.py's old flat _STATUS_COLOR, whose four codes (#2ecc71 emerald,
# #e74c3c alizarin, #f39c12 orange, #7f8c8d concrete) were a completely
# different palette from anything else on screen and did not react to the
# light/dark switch at all -- the "synced" green in particular glowed on the
# paper background. Same four states, drawn from the theme.
STATUS_COLOR = {
    "complete": TERTIARY,
    "failed": ERROR,
    "pending": AMBER,
    None: OUTLINE,
}


def _on_band(pair: "tuple[str, str]") -> "tuple[str, str]":
    """The same status colour, for something drawn on the masthead band.

    The band is `primary`, which is dark in light mode and light in dark mode
    -- the one surface in the app whose lightness runs opposite to the page.
    So a status dot on it needs the *other* mode's colour: light-mode Oxblood
    on light-mode Postmark Blue is two dark colours on top of each other and
    the dot simply disappears.
    """
    return (pair[1], pair[0])


STATUS_COLOR_ON_BAND = {state: _on_band(pair) for state, pair in STATUS_COLOR.items()}

# Controls drawn on the masthead band have the same problem as the dot: a
# stock CTkButton is `primary`-filled, and primary on primary is an invisible
# button. The filled one takes the container pair (light-on-dark in light
# mode, dark-on-light in dark mode, both by construction); the ghost ones
# outline in ON_PRIMARY and hover to a band tint rather than to a page tint,
# which would flash a pale rectangle in the middle of the blue.
BAND_BUTTON_FG = PRIMARY_CONTAINER
BAND_BUTTON_TEXT = ON_PRIMARY_CONTAINER
BAND_BUTTON_HOVER = ("#C9D4E6", "#3A5288")
BAND_GHOST_HOVER = ("#3F5488", "#7C93C6")

# ---------------------------------------------------------------------------
# Type
# ---------------------------------------------------------------------------

# The masthead wordmark and panel headings, matching the Serif that
# ChatMailTheme.kt puts on Material's headline roles. Named families rather
# than Tk's "serif" alias so the two platforms land somewhere close: Georgia
# ships with Windows, and the fallbacks cover a machine where it does not.
SERIF_FAMILY = "Georgia"
SERIF_FALLBACKS = ("Cambria", "Times New Roman")

# ---------------------------------------------------------------------------
# Applying it
# ---------------------------------------------------------------------------

# CustomTkinter reads these defaults once, at widget construction, out of
# ThemeManager.theme -- so this has to run before the first widget exists.
#
# Patching the loaded dict rather than shipping a theme .json and pointing
# set_default_color_theme() at it: the JSON route needs a file path that
# resolves both from a source checkout and from inside the PyInstaller bundle
# (where __file__ is a temp extraction dir), which is exactly the class of
# path bug that only ever shows up in the packaged build, on the user's
# machine, after release. A dict has no path. The built-in theme is still
# loaded first so any key CustomTkinter adds in a future version keeps a
# sane value instead of vanishing.
_WIDGET_DEFAULTS: "dict[str, dict[str, object]]" = {
    "CTk": {"fg_color": BACKGROUND},
    "CTkToplevel": {"fg_color": BACKGROUND},
    "CTkFrame": {
        "corner_radius": 8,
        "border_width": 0,
        "fg_color": SURFACE_CONTAINER_LOW,
        "top_fg_color": SURFACE_CONTAINER,
        "border_color": OUTLINE_VARIANT,
    },
    "CTkButton": {
        "corner_radius": 8,
        "border_width": 0,
        "fg_color": PRIMARY,
        "hover_color": PRIMARY_HOVER,
        "border_color": OUTLINE,
        "text_color": ON_PRIMARY,
        "text_color_disabled": OUTLINE,
    },
    "CTkLabel": {
        "corner_radius": 0,
        "border_width": 0,
        "fg_color": "transparent",
        "border_color": OUTLINE,
        "text_color": ON_SURFACE,
    },
    "CTkEntry": {
        "corner_radius": 8,
        "border_width": 1,
        "fg_color": SURFACE_CONTAINER_LOWEST,
        "border_color": OUTLINE,
        "text_color": ON_SURFACE,
        "placeholder_text_color": ON_SURFACE_VARIANT,
    },
    "CTkCheckBox": {
        "corner_radius": 4,
        "border_width": 2,
        "fg_color": PRIMARY,
        "border_color": OUTLINE,
        "hover_color": PRIMARY_HOVER,
        "checkmark_color": ON_PRIMARY,
        "text_color": ON_SURFACE,
        "text_color_disabled": OUTLINE,
    },
    "CTkSwitch": {
        "corner_radius": 1000,
        "border_width": 3,
        "button_length": 0,
        "fg_color": SURFACE_VARIANT,
        "progress_color": PRIMARY,
        "button_color": OUTLINE,
        "button_hover_color": ON_SURFACE_VARIANT,
        "text_color": ON_SURFACE,
        "text_color_disabled": OUTLINE,
    },
    "CTkRadioButton": {
        "corner_radius": 1000,
        "border_width_checked": 6,
        "border_width_unchecked": 3,
        "fg_color": PRIMARY,
        "border_color": OUTLINE,
        "hover_color": PRIMARY_HOVER,
        "text_color": ON_SURFACE,
        "text_color_disabled": OUTLINE,
    },
    "CTkProgressBar": {
        "corner_radius": 1000,
        "border_width": 0,
        "fg_color": SURFACE_VARIANT,
        "progress_color": PRIMARY,
        "border_color": OUTLINE_VARIANT,
    },
    "CTkSlider": {
        "corner_radius": 1000,
        "button_corner_radius": 1000,
        "border_width": 6,
        "button_length": 0,
        "fg_color": SURFACE_VARIANT,
        "progress_color": SECONDARY,
        "button_color": PRIMARY,
        "button_hover_color": PRIMARY_HOVER,
    },
    "CTkOptionMenu": {
        "corner_radius": 8,
        "fg_color": PRIMARY,
        "button_color": PRIMARY_HOVER,
        "button_hover_color": PRIMARY_HOVER,
        "text_color": ON_PRIMARY,
        "text_color_disabled": OUTLINE,
    },
    "CTkComboBox": {
        "corner_radius": 8,
        "border_width": 1,
        "fg_color": SURFACE_CONTAINER_LOWEST,
        "border_color": OUTLINE,
        "button_color": OUTLINE,
        "button_hover_color": ON_SURFACE_VARIANT,
        "text_color": ON_SURFACE,
        "text_color_disabled": OUTLINE,
    },
    "CTkScrollbar": {
        "corner_radius": 1000,
        "border_spacing": 4,
        "fg_color": "transparent",
        "button_color": OUTLINE_VARIANT,
        "button_hover_color": OUTLINE,
    },
    "CTkSegmentedButton": {
        "corner_radius": 8,
        "border_width": 2,
        "fg_color": SURFACE_VARIANT,
        "selected_color": PRIMARY,
        "selected_hover_color": PRIMARY_HOVER,
        "unselected_color": SURFACE_VARIANT,
        "unselected_hover_color": NEUTRAL_HOVER,
        "text_color": ON_PRIMARY,
        "text_color_disabled": OUTLINE,
    },
    "CTkTextbox": {
        "corner_radius": 8,
        "border_width": 0,
        "fg_color": SURFACE_CONTAINER_LOWEST,
        "border_color": OUTLINE,
        "text_color": ON_SURFACE,
        "scrollbar_button_color": OUTLINE_VARIANT,
        "scrollbar_button_hover_color": OUTLINE,
    },
    "CTkScrollableFrame": {"label_fg_color": SURFACE_CONTAINER_HIGH},
    "DropdownMenu": {
        "fg_color": SURFACE_CONTAINER_HIGH,
        "hover_color": NEUTRAL_HOVER,
        "text_color": ON_SURFACE,
    },
}


def apply_theme(ctk_module) -> None:
    """Load the built-in theme, then overwrite it with Quiet Archive.

    Takes the customtkinter module as an argument rather than importing it, so
    the palette above stays importable (and testable) on a machine with no Tk
    -- which is what CI runs on for the Linux job.
    """
    ctk_module.set_default_color_theme("blue")
    theme = ctk_module.ThemeManager.theme
    for widget, options in _WIDGET_DEFAULTS.items():
        # setdefault the widget key: a CustomTkinter version that drops one of
        # these classes should not have it reappear as a half-populated dict.
        theme.setdefault(widget, {})
        for option, value in options.items():
            theme[widget][option] = list(value) if isinstance(value, tuple) else value
