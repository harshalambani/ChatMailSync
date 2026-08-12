"""Dismiss the PortableApps.com Launcher splash the moment our window is up.

Why this exists
---------------
`SplashTime` in portable/App/AppInfo/Launcher/ChatMailSyncPortable.ini is a
TIMER, not a ready signal. The launcher shows the image for exactly that long
and nothing tells it the app has appeared - so any fixed number encodes one
machine's hardware and drifts as startup changes. Worse, the splash is topmost:
overshoot parks an image over a window the user could already be typing into,
and undershoot clears it over a blank desktop. There is no single number that is
right on every machine, which is why the old value (2500 ms) was tuned to sit
just under the fastest warm start and deliberately left the 8.1 s cold start
uncovered.

This module removes the trade-off by telling the splash directly, the moment our
own window maps (see gui.py). `SplashTime` then only has to bound the failure
case where that never happens, so it is set well ABOVE the worst cold start
rather than under the best warm one.

How dismissal works
-------------------
The splash is a separate visible top-level window of class `_sp`, owned by the
LAUNCHER process (ChatMailSyncPortable.exe), not by this app's process. Two things
were measured against the live window before this was written:

  * PostMessage(hwnd, WM_CLOSE, ...) is IGNORED. The window does not close.
  * PostMessage(hwnd, WM_LBUTTONDOWN, ...) then WM_LBUTTONUP DOES dismiss it,
    and the launcher process and app window are unaffected afterwards.

That is not a trick played on the launcher. PAL's SplashScreen.nsh invokes the
newadvsplash NSIS plugin WITHOUT /NOCANCEL, and that plugin's documented default
is "exit on user click". Verified for the generator version this repo actually
pins - PortableApps.com Launcher Generator 2.2.9, see build_portable.ps1 - at

    C:\\PortableApps\\PortableApps.comLauncher\\Other\\Source\\Segments\\SplashScreen.nsh

line 24:

    newadvsplash::show /NOUNLOAD $0 0 0 -1 /L $EXEDIR\\App\\AppInfo\\Launcher\\splash.jpg

No /NOCANCEL, so exit-on-click is live; no /PASSIVE either, which is why the
window is forced to the foreground in the first place. We are synthesizing the
click the plugin was already told to listen for.

`_sp` is a PAL/NSIS implementation detail, not a public contract. If a future
launcher version renames the class, adds /NOCANCEL, or changes this behaviour,
this module simply finds no matching window, no-ops, and the splash falls back
to running out `SplashTime` in full - which is exactly the behaviour that
shipped before this change. That fallback is why `SplashTime` must stay
generous rather than being removed.

Failure handling
----------------
Every path here is best-effort. There is nothing in a splash worth failing
startup over: at worst we lose early dismissal and get the old fixed timer back.
The function is gated to Windows, wrapped so no exception can escape, and caps
its own search in time so it can never hang startup waiting for windows that do
not exist (running from source, splash disabled, or launched without PAL).

Testability
-----------
The Win32 calls are split into small module-level functions doing exactly one
ctypes call each, purely so tests can monkeypatch them individually instead of
poking real OS windows. None are meant to be called from outside this module.
"""

from __future__ import annotations

import sys
import time

# Win32 message constants (winuser.h), defined locally so this module needs
# nothing beyond the standard library's ctypes.
WM_LBUTTONDOWN = 0x0201
WM_LBUTTONUP = 0x0202

_LAUNCHER_EXE_NAME = "ChatMailSyncPortable.exe"
_SPLASH_CLASS_NAME = "_sp"
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

# Upper bound on how long we are willing to spend looking for the splash. This
# must never become a place startup can stall: if the window is not there almost
# immediately it is very likely not there at all. The loop polls rather than
# checking once because our window can be mapped before the splash has painted.
_SEARCH_BUDGET_SECONDS = 2.0
_POLL_INTERVAL_SECONDS = 0.1


def dismiss_launcher_splash() -> bool:
    """Best-effort: click through the PAL splash window if one is showing.

    Returns True if a matching splash window was found and clicked, False
    otherwise (including on any error). The return value is for tests and
    logging only - callers must not branch on it, because False is the
    expected, harmless outcome on non-Windows platforms, when running from
    source, and whenever PAL is not involved at all.
    """
    try:
        return _dismiss_launcher_splash_impl()
    except Exception:  # noqa: BLE001 - must never affect app startup
        return False


def _dismiss_launcher_splash_impl() -> bool:
    if not sys.platform.startswith("win"):
        return False

    deadline = time.monotonic() + _SEARCH_BUDGET_SECONDS
    while True:
        hwnds = _find_splash_hwnds()
        if hwnds:
            for hwnd in hwnds:
                # Synthesize the click newadvsplash's default "exit on user
                # click" behaviour already listens for. WM_CLOSE is ignored by
                # this window (measured), so it is not tried here.
                _post_click(hwnd)
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(_POLL_INTERVAL_SECONDS)


def _find_splash_hwnds() -> list[int]:
    """Return hwnds of visible top-level `_sp` windows owned by the launcher."""
    found: list[int] = []
    for hwnd in _enum_top_level_windows():
        try:
            if not _window_is_visible(hwnd):
                continue
            if _window_class_name(hwnd) != _SPLASH_CLASS_NAME:
                continue
            pid = _window_owner_pid(hwnd)
            if _process_image_name(pid).lower() != _LAUNCHER_EXE_NAME.lower():
                continue
            found.append(hwnd)
        except Exception:  # noqa: BLE001 - one bad window must not abort the scan
            continue
    return found


# ---------------------------------------------------------------------------
# Thin Win32 wrappers. Each does exactly one ctypes call so tests can
# monkeypatch them individually without touching real windows.
# ---------------------------------------------------------------------------

def _enum_top_level_windows() -> list[int]:
    import ctypes  # noqa: PLC0415
    from ctypes import wintypes  # noqa: PLC0415

    hwnds: list[int] = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def _enum_proc(hwnd, _lparam):
        hwnds.append(hwnd)
        return True

    ctypes.windll.user32.EnumWindows(_enum_proc, 0)
    return hwnds


def _window_is_visible(hwnd: int) -> bool:
    import ctypes  # noqa: PLC0415

    return bool(ctypes.windll.user32.IsWindowVisible(hwnd))


def _window_class_name(hwnd: int) -> str:
    import ctypes  # noqa: PLC0415

    buf = ctypes.create_unicode_buffer(256)
    ctypes.windll.user32.GetClassNameW(hwnd, buf, 256)
    return buf.value


def _window_owner_pid(hwnd: int) -> int:
    import ctypes  # noqa: PLC0415
    from ctypes import wintypes  # noqa: PLC0415

    pid = wintypes.DWORD()
    ctypes.windll.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


def _process_image_name(pid: int) -> str:
    """Resolve the exe filename for *pid*, without a psutil dependency."""
    import ctypes  # noqa: PLC0415
    from ctypes import wintypes  # noqa: PLC0415

    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(260)
        size = wintypes.DWORD(len(buf))
        ok = kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size))
        if not ok:
            return ""
        return buf.value.rsplit("\\", 1)[-1]
    finally:
        kernel32.CloseHandle(handle)


def _post_click(hwnd: int) -> None:
    import ctypes  # noqa: PLC0415

    ctypes.windll.user32.PostMessageW(hwnd, WM_LBUTTONDOWN, 1, 0)
    ctypes.windll.user32.PostMessageW(hwnd, WM_LBUTTONUP, 0, 0)
