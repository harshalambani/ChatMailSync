# Splash screens, start time and shutdown in a PortableApps package

Learnings carried over from **platform-agnostic-skills-portable** (PASk), where all
of this was measured on a real build across releases 3.3.0 - 3.4.1. WA Mail Sync
ships the same PortableApps.com launcher, so most of it applies directly; the
parts that do not are called out.

Nothing here is theory. Every number is from a stopwatch on the frozen exe.

---

## 0. The one rule that generated all the others

**Measure the frozen build, never the source run.**

Source mode and the PyInstaller bundle are not the same program in the ways that
matter to startup. PASk measured ~9s warm from source and **14.6s warm frozen** -
a 60% miss. The splash was sized against the source number, which is precisely
how 3.4.0 shipped a splash that cleared while the screen was still empty, leaving
~8.6s of nothing: the exact gap the splash existed to close.

The same trap is waiting in WA Mail Sync. `python gui.py` starts a Tk window
almost immediately; `App\WAMailSync\WAMailSync.exe` has to unpack a onedir
bundle, and CustomTkinter loads its theme JSON and fonts on import. Time the exe.

Corollary: **re-measure after any change to the startup path**, and say so in a
comment next to whatever constant you derived from the measurement. A tuned
constant with no record of what it was tuned against rots silently.

---

## 1. The splash

### What it is

The PortableApps.com Launcher shows `App\AppInfo\Launcher\splash.jpg` for a
duration you set in `App\AppInfo\Launcher\<AppID>.ini`:

```ini
[Launch]
SplashTime=13000
```

WA Mail Sync has **no splash today** - there is no `splash.jpg` under
`portable\App\AppInfo\Launcher\` and no `SplashTime` in
`WAMailSyncPortable.ini`. Adding one is the single cheapest perceived-speed win
available, because it costs zero actual milliseconds.

### The three traps, in the order they bit

**Trap 1 - it is a TIMED overlay, not a ready signal.**

This is the thing everyone assumes wrongly. `newadvsplash` takes a *duration*.
Nothing tells it the app is ready. It does not clear when your window appears.
So the value is a guess against a measurement, and both failure directions are
real:

- **Undershoot** -> splash clears, user stares at an empty desktop, which is
  worse than no splash at all because it looks like the launch failed.
- **Overshoot** -> the splash is **topmost**, so it parks an unresponsive image
  on top of a window that is already usable.

PASk deliberately sizes **just under the warm start** and leaves cold starts
uncovered. Overshoot is the worse failure: covering the thing the user is waiting
for is more annoying than a beat of empty screen at the end. Warm/cold spread was
14.6s vs 44.0s - stretching to cover cold would have put a topmost splash over a
usable window on *every* warm launch.

**Trap 2 - `LaunchAppAfterSplash` does the opposite of what it sounds like.**

Setting it runs the splash **to completion before starting the app**. That
*adds* the splash duration to your start time instead of hiding the start behind
it. Leave it unset. PASk's ini carries a comment saying so, because it reads like
the obviously-correct setting and is not.

**Trap 3 - a missing splash.jpg disables the splash silently.**

`SplashScreen.nsh` in the launcher generator sets `DisableSplashScreen=true` when
the file is absent. No warning, no build failure - you just get no splash and no
reason. If you add a splash and do not see one, check the file actually landed in
the package before touching `SplashTime`.

### Trap 4 - the build-time render trap (this one shipped a bug)

PASk renders the splash at build time with Pillow, to stamp the version number
onto it. Release 3.4.0 shipped a splash reading **"Version 3.3.0"**.

Cause: `build.py` runs under **whatever interpreter invoked it**, not under the
build venv it creates. Locally that interpreter happens to have Pillow, so it
worked on the dev machine every single time. On CI it is a bare `setup-python`
with no packages, the render raised `ModuleNotFoundError`, and a `try/except`
fallback shipped the stale committed image.

Three separate lessons, all worth carrying:

1. **A build step that silently falls back is a build step that will ship the
   wrong thing.** The fallback now prints a loud warning that its version line
   may be wrong.
2. **"Works on my machine" is structurally guaranteed for build scripts**, since
   the dev machine's interpreter is contaminated with the project's own deps.
   Anything a build step imports must be run through the build venv's python
   explicitly (`subprocess.run([str(py), str(script), ...], check=True)`), not
   assumed importable.
3. **Verify the artifact CI produced, not the one you built locally.** The fix
   was only confirmed by grepping the CI log for `ok rendered splash for 3.4.1`.
   A local build proves nothing about the path that broke.

If WA Mail Sync's splash carries a version number, it inherits this trap through
`build_portable.ps1`. If it is a static image with no version on it, skip the
whole mechanism - commit a plain jpg and avoid the render step entirely. **That
is the better default.** PASk only renders because it wanted the version visible;
that feature cost a shipped bug and a release to fix.

### Sizing recipe

1. Build the exe.
2. Time launcher-start -> visible window, **warm** (run it twice, take the
   second) and **cold** (reboot, or first run after a build).
3. Set `SplashTime` just under the warm figure.
4. Write both numbers, the date, and the fact that it is timed-not-ready into a
   comment directly above the setting.

PASk's actual comment block is a good template - it records warm 14.6s, cold
44.0s, why cold is uncovered, why `LaunchAppAfterSplash` is unset, and that the
previous value (6s) was wrong because it was sized against a source run.

### The drift problem

After later startup work, PASk's warm start dropped to **11.77s** against a
`SplashTime` of 13000 - now overhanging a usable window by ~1s. Tuned constants
drift as the code gets faster, and nothing tells you. The comment instructing a
re-measure is what makes this recoverable by whoever touches it next.

---

## 2. Making it actually start faster

PASk went from **19.4s to 8.9s** (warm, launch to constructed UI). Where it came
from, in order of payoff:

**Take the network off the startup path.** The single biggest win. The app was
probing every configured LLM endpoint during UI construction - a socket connect
with a timeout, on the critical path, before anything could render. Moved to a
deferred load that fires *after* the window is up.

The transferable principle: **anything with a timeout must not be between the
user and their first frame.** WA Mail Sync's equivalent is IMAP/OAuth transport
construction. `gui.py:860` already does the right thing -
`threading.Thread(target=self._silent_build_transport, daemon=True).start()` -
which is the pattern. Audit for anything that *isn't* on a thread: a token
refresh, a mailbox capability probe, a DNS lookup, a "check for updates".

**Import lazily.** Heavy imports at module scope run before your first window can
be drawn. Push them into the function that needs them. In a frozen onedir bundle
this matters more than in source, because each import is also a disk read from a
freshly-extracted tree.

**Do not build UI the user has not asked for yet.** PASk constructs tabs lazily.
The Tk analogue is not building frames for notebook pages until first selected.

**Deferring changes *when* things appear, which creates a new problem** - see
next section. Budget for it. It is not free, it just moves the cost somewhere
more forgiving.

---

## 3. The cost of deferring: things move on screen

Once the endpoint probe moved off the startup path, the results landed
**0.1s - 2.0s after the window appeared**, and two things visibly changed
underneath the user's cursor:

- A status panel that was one italic line grew into a heading plus three bullets
  *per endpoint*, shoving the whole page down just as the eye landed on it.
- A dropdown showed the *configured default* drawn exactly like a confirmed,
  probed value - which then silently swapped for the real one.

### What worked

**Start in the shape you finish in.** The placeholder emits the real block
structure - same line count, same layout - reading from config only, no network.
When the real data lands, only the status dot and one detail line change.
**Nothing moves.**

**A placeholder must not lie.** The dropdown now seeds one `Loading models...`
choice whose *value* is still the configured default. An action fired inside that
window submits exactly what it always did - only the **label** stops claiming to
be settled. Getting this backwards (showing a guess styled as a fact) is what
made the original swap feel like a glitch rather than a load.

**Deliberately NOT spinners.** On a 0.1s fill a spinner announces a wait that is
already over - it draws attention to a delay the user would not otherwise have
noticed. Spinners earn their place above roughly 1s, and only when you cannot
render the final shape.

### Testing it

The anti-reflow property is testable without a GUI harness. PASk asserts the
placeholder and the resolved panel have **the same line count and the same block
shape**, and that drawing the placeholder **opens no socket**. That second
assertion is the one that catches a future edit quietly putting the network back
on the construction path.

For WA Mail Sync this maps onto any Tk widget whose contents arrive from a
worker thread via the `queue` - the account status line, folder lists, anything
that says "connecting". Size the widget for its final content, do not let it
grow.

---

## 4. Shutdown: the bug you will not think to look for

PASk's most user-visible defect was **closing the app blocked relaunching it for
~10-17 seconds**, and it was invisible in every test because nothing tests
"close, then immediately reopen".

### The mechanism

The window vanished on the first click, but the **process lived on**. With
`WaitForProgram=true`, the launcher stays alive for that whole tail. And with
`SinglePortableAppInstance=true`, the launcher holds a mutex
(`PortableApps.comLauncher$AppID-$BaseName`) for as long as it lives.

So relaunching inside that window did **nothing at all** - no window, no error,
no message. `InstanceManagement.nsh` (lines 16-25) simply `Quit`s, silently, by
design. From the user's chair that is indistinguishable from a broken app.

Measured: window gone at t=0, process gone at **10.4s warm / 17.1s cold**.

### The finding worth carrying: what was actually in the tail

The first fix was to exit early, on the window's close event, rather than waiting
for the GUI framework's teardown to return. On its own **it made things worse -
22.8s warm, against a 10.4s baseline.**

Root cause of the regression: the exit path called `atexit._run_exitfuncs()`.
That runs the **entire registered set**, which is not just yours. It includes
`concurrent.futures.thread._python_exit`, which **joins every ThreadPoolExecutor
worker**. With the web server's threads still up, it does not come back. That was
**18.4s** of the tail, all of it with the window already off screen and the mutex
still held.

Two lessons:

1. **Never call `atexit._run_exitfuncs()` yourself.** It is private, it is
   global, and you do not own most of what is in it. Keep your own cleanups in
   your own list and run exactly those:

   ```python
   _EXIT_CLEANUPS = []

   def register_exit_cleanup(fn):
       """Register for BOTH our fast-exit path and normal interpreter shutdown."""
       _EXIT_CLEANUPS.append(fn)
       atexit.register(fn)

   def run_exit_cleanups():
       for fn in reversed(list(_EXIT_CLEANUPS)):
           try:
               fn()
           except BaseException:
               pass   # caller is one line from os._exit
   ```

   Then the fast path is `run_exit_cleanups(); os._exit(0)`, while a normal
   shutdown still runs them the ordinary way. Cleanups must be idempotent, since
   both paths can reach them.

2. **A partial fix can be worse than no fix, and only measurement tells you.**
   Exiting early only helps if what follows is actually short. The two halves
   ship together or not at all - PASk's tests now assert exactly that, because
   a future edit reintroducing `_run_exitfuncs` would silently restore the stall.

Result: **23.43s -> 2.82s** from close to the mutex being released, cold and warm
alike.

### An earlier "finding" that was wrong

An initial probe concluded the registered atexit handlers "total 0.000s". It
wrapped `atexit.register` **before the heavy imports**, so it never saw the
handler that mattered. It was recorded as fact and had to be retracted in the
changelog.

**Instrument the real event, not a proxy for it.** A shutdown probe belongs in
the shutdown path, writing timestamps to a file, exercised by an actual close.

### Does this bite WA Mail Sync?

**Not today, and it is worth understanding why, because it is one setting away.**

- `WAMailSyncPortable.ini` sets `SingleAppInstance=false` and does **not** set
  `SinglePortableAppInstance`. These are different settings: the first guards
  against a locally-installed copy, the second against a second portable copy.
  With neither in play, there is no mutex to be held, so a slow exit delays
  nothing user-visible. **If `SinglePortableAppInstance=true` is ever added -
  and it is a reasonable thing to want - this bug arrives with it**, because
  `WaitForProgram=true` is already set.
- Every worker thread in `gui.py` and `gui_worker.py` is `daemon=True`, so
  interpreter shutdown does not join them. That is the good posture, and it is
  what keeps the tail short. **Any non-daemon thread added later becomes an exit
  stall**: `threading._shutdown()` joins non-daemon threads before the process
  can exit, with no timeout and no diagnostic.

So: no action needed, two invariants to preserve. Worth a comment in
`gui_worker.py` next to the thread creation.

### How to check it in 30 seconds

Close the app and immediately try to reopen it. If nothing happens, this is why.
Or, scripted: post `WM_CLOSE`, then poll for the process to disappear and time
the gap.

---

## 5. Process learnings

**A GUI app has failure modes only a human clicking around will find.** Every bug
in PASk's 3.4.1 came from the user opening the app and using it - the reflow, the
relaunch block, the wrong version on the splash. None were caught by a green test
suite. Budget for hands-on passes on the built artifact, not just source.

**Write the *why* next to tuned constants.** `SplashTime=13000` is meaningless
alone. With the warm/cold measurements, the date, the timed-not-ready mechanic,
and the note that the previous value was wrong because it came from a source run,
the next person can re-derive it instead of cargo-culting it.

**When a measurement contradicts your model, re-derive the model.** The 22.8s
regression was the useful event of the whole exercise: it disproved a recorded
"finding" and exposed the real 18.4s cost. A fix that makes the number worse is
information, not a setback.

---

## Checklist for WA Mail Sync

- [ ] Time the **frozen** exe, launcher-start to visible window, warm and cold.
- [ ] Close-then-immediately-reopen. Confirm it works (it should, given
      `SingleAppInstance=false`) and note the invariant.
- [ ] Add `splash.jpg` + `SplashTime` sized just under the warm figure, with the
      reasoning in a comment. Prefer a **static** image - no version number, no
      build-time render, no Pillow trap.
- [ ] Do **not** set `LaunchAppAfterSplash`.
- [ ] Verify the splash appears in a package built by **CI**, not just locally.
- [ ] Audit for anything with a network timeout on the path to the first window;
      move it to a daemon thread + queue like `_silent_build_transport` already
      does.
- [ ] Ensure any widget filled from a worker thread is sized for its final
      content, so it does not grow when the data lands.
- [ ] Comment the two shutdown invariants: keep threads `daemon=True`, and know
      that adding `SinglePortableAppInstance=true` makes exit latency
      user-visible.
