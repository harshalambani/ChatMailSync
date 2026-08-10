"""Tests for early dismissal of the PortableApps.com Launcher splash.

READ THE FIXTURE BELOW BEFORE ADDING A TEST HERE.

`_dismiss_launcher_splash_impl()` opens with a `sys.platform` gate and returns
False on anything that is not Windows. On Linux CI that gate is hit before a
single line of the logic these tests name, which silently turns every
`assert ... is False` into a test of the gate rather than of the behaviour it
claims to cover. This is not hypothetical: the upstream version of this module
shipped with 3 of 5 tests green over code that never executed.

So `_pin_platform` is autouse, and the one test that genuinely cares about the
gate (test_non_windows_is_a_no_op) opts back out explicitly.
"""

import pytest

import _splash


@pytest.fixture(autouse=True)
def _pin_platform(monkeypatch):
    """Force the Windows path so the platform gate cannot shadow assertions."""
    monkeypatch.setattr(_splash.sys, "platform", "win32")


@pytest.fixture(autouse=True)
def _no_real_sleep(monkeypatch):
    """Keep the poll loop's own timing out of the suite's wall clock."""
    monkeypatch.setattr(_splash.time, "sleep", lambda _seconds: None)


def _install_windows(monkeypatch, windows, clicked=None):
    """Wire the Win32 wrappers to a synthetic window list.

    *windows* maps hwnd -> (visible, class_name, pid), and pids are resolved
    to exe names through *procs* below. Nothing here touches a real window.
    """
    procs = {1: "WAMailSyncPortable.exe", 2: "explorer.exe"}

    monkeypatch.setattr(_splash, "_enum_top_level_windows", lambda: list(windows))
    monkeypatch.setattr(_splash, "_window_is_visible", lambda h: windows[h][0])
    monkeypatch.setattr(_splash, "_window_class_name", lambda h: windows[h][1])
    monkeypatch.setattr(_splash, "_window_owner_pid", lambda h: windows[h][2])
    monkeypatch.setattr(_splash, "_process_image_name", lambda p: procs.get(p, ""))
    if clicked is not None:
        monkeypatch.setattr(_splash, "_post_click", clicked.append)


# ---------------------------------------------------------------------------
# The happy path
# ---------------------------------------------------------------------------

def test_a_matching_splash_is_clicked(monkeypatch):
    clicked = []
    _install_windows(monkeypatch, {10: (True, "_sp", 1)}, clicked)

    assert _splash.dismiss_launcher_splash() is True
    assert clicked == [10]


def test_every_matching_window_is_clicked(monkeypatch):
    clicked = []
    _install_windows(monkeypatch, {10: (True, "_sp", 1), 11: (True, "_sp", 1)}, clicked)

    assert _splash.dismiss_launcher_splash() is True
    assert sorted(clicked) == [10, 11]


def test_click_is_lbuttondown_then_lbuttonup_never_wm_close(monkeypatch):
    """WM_CLOSE is ignored by this window (measured); the click is the exit path."""
    posted = []

    class _FakeUser32:
        def PostMessageW(self, hwnd, msg, wparam, lparam):
            posted.append((hwnd, msg, wparam, lparam))
            return 1

    class _FakeWindll:
        user32 = _FakeUser32()

    import ctypes

    monkeypatch.setattr(ctypes, "windll", _FakeWindll(), raising=False)
    _splash._post_click(10)

    assert posted == [
        (10, _splash.WM_LBUTTONDOWN, 1, 0),
        (10, _splash.WM_LBUTTONUP, 0, 0),
    ]
    assert 0x0010 not in [msg for _h, msg, _w, _l in posted]  # WM_CLOSE


# ---------------------------------------------------------------------------
# Windows that must NOT be clicked
# ---------------------------------------------------------------------------

def test_wrong_class_is_ignored(monkeypatch):
    clicked = []
    _install_windows(monkeypatch, {10: (True, "Notepad", 1)}, clicked)

    assert _splash.dismiss_launcher_splash() is False
    assert clicked == []


def test_invisible_window_is_ignored(monkeypatch):
    clicked = []
    _install_windows(monkeypatch, {10: (False, "_sp", 1)}, clicked)

    assert _splash.dismiss_launcher_splash() is False
    assert clicked == []


def test_sp_window_owned_by_another_process_is_ignored(monkeypatch):
    """`_sp` is a generic NSIS class - the owning exe is what makes it ours."""
    clicked = []
    _install_windows(monkeypatch, {10: (True, "_sp", 2)}, clicked)

    assert _splash.dismiss_launcher_splash() is False
    assert clicked == []


def test_only_the_launcher_owned_window_is_clicked(monkeypatch):
    clicked = []
    _install_windows(
        monkeypatch,
        {10: (True, "_sp", 2), 11: (True, "_sp", 1), 12: (True, "Notepad", 1)},
        clicked,
    )

    assert _splash.dismiss_launcher_splash() is True
    assert clicked == [11]


# ---------------------------------------------------------------------------
# The poll loop and its budget
# ---------------------------------------------------------------------------

def test_window_appearing_on_a_later_poll_is_still_found(monkeypatch):
    """Our window can be mapped before the splash has painted."""
    clicked = []
    _install_windows(monkeypatch, {10: (True, "_sp", 1)}, clicked)

    calls = {"n": 0}

    def _late_enum():
        calls["n"] += 1
        return [10] if calls["n"] >= 3 else []

    monkeypatch.setattr(_splash, "_enum_top_level_windows", _late_enum)

    assert _splash.dismiss_launcher_splash() is True
    assert clicked == [10]
    assert calls["n"] == 3


def test_search_gives_up_at_the_budget(monkeypatch):
    """No splash (source mode, no PAL) must return, not spin forever."""
    _install_windows(monkeypatch, {})

    ticks = iter([0.0] + [0.5 * i for i in range(1, 40)])
    monkeypatch.setattr(_splash.time, "monotonic", lambda: next(ticks))

    assert _splash.dismiss_launcher_splash() is False


# ---------------------------------------------------------------------------
# Nothing here may ever affect startup
# ---------------------------------------------------------------------------

def test_an_exception_from_a_wrapper_never_escapes(monkeypatch):
    def _boom():
        raise OSError("EnumWindows failed")

    monkeypatch.setattr(_splash, "_enum_top_level_windows", _boom)

    assert _splash.dismiss_launcher_splash() is False


def test_one_bad_window_does_not_abort_the_scan(monkeypatch):
    """A window can die between enumeration and inspection."""
    clicked = []
    _install_windows(monkeypatch, {10: (True, "_sp", 1)}, clicked)

    def _flaky_visible(hwnd):
        if hwnd == 9:
            raise OSError("window is gone")
        return True

    monkeypatch.setattr(_splash, "_enum_top_level_windows", lambda: [9, 10])
    monkeypatch.setattr(_splash, "_window_is_visible", _flaky_visible)

    assert _splash.dismiss_launcher_splash() is True
    assert clicked == [10]


def test_non_windows_is_a_no_op(monkeypatch):
    """The one test that WANTS the platform gate - see this module's docstring."""
    monkeypatch.setattr(_splash.sys, "platform", "linux")

    def _should_not_run():
        raise AssertionError("enumerated windows on a non-Windows platform")

    monkeypatch.setattr(_splash, "_enum_top_level_windows", _should_not_run)

    assert _splash.dismiss_launcher_splash() is False
