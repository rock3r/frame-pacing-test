# Measurement tooling

Scripts used to produce the per-platform numbers in the PRD
([specs.sebastiano.dev/frame-pacing](https://specs.sebastiano.dev/frame-pacing/), §6).

| Script | Platform | What it does |
|---|---|---|
| `jbr-measure` | Linux | Samples GPU + CPU power/clock over an interval. NVIDIA via `nvidia-smi`, Intel via RAPL sysfs + i915 rc6. Same output shape either way. |
| `jbr-experiment` | Linux | Runs the harness unpaced then paced at a given window size and reports both. |
| `fpt-measure.ps1` | Windows | Same, via a scheduled task, sampling `nvidia-smi` for real GPU watts. |

Install the two Linux scripts into `/usr/local/bin` (they call each other by name).

```bash
jbr-experiment 20 700x450       # seconds, window
powershell -File fpt-measure.ps1 -mode off -window 700x450 -seconds 20
```

## Method

**Vary window size, hold the display fixed.** `-Dwindow=WxH` (see `Main.kt`) is the
sweep variable. Sweeping *display resolution* instead confounds three things at once —
render area, compositor load, and desktop scaling — and an early Linux sweep did exactly
that. Pin fractional scaling to 1 as well; GNOME at 2560×1440 was silently rendering a
3840×2160 framebuffer.

**Read results as an over-render ratio** — unpaced fps ÷ refresh. Below 1.0 pacing is a
no-op; there is nothing to coalesce. The saving grows from there and saturates near
−65%, because paced power floors at the cost of actually producing frames at refresh.

**Mixed-refresh setups need `-Ddisplay=N`, not a mouse.** The harness prints every screen
with its mode at startup and `-Ddisplay=N` launches the window on screen `N`. Dragging the
window between displays proves nothing: `PacedRepaintManager` resolves its display once
from the frame's `GraphicsConfiguration` and holds that subscription for the process
lifetime, so the clock never re-resolves. Each display must be a separate launch. The
number to compare is the `refreshPeriod` printed at startup (what the API *advertises* for
that display) against `ticks/sec` (what the clock actually *delivers*) — no power
instrumentation needed to show a disagreement.

## Traps

Every one of these silently produced plausible-but-wrong numbers before it was found.

**Background load.** CPU contention inflates package/core power while GT power still
looks sane, so a bad run reads as almost-right. Both scripts now check idle power
**before and after** each run — a start-only check misses a daemon waking mid-run, which
is exactly what happened when another agent's Gradle build started halfway through a
sweep. A quiet i5-9500T idles at 0.5–0.9 W package.

**The first sample in a fresh ssh session reads ~4 W** regardless of load: it captures
session startup, bash init, and the first `sudo` doing PAM and a sudoers parse. The idle
guard was measuring *itself* and aborting on an idle machine. `jbr-experiment` discards a
warm-up sample first.

**Leaked harness instances.** Cleanup must match the java process (`pkill -f MainKt`),
not the launcher script name (`frame-pacing-test`) — otherwise instances accumulate and
steal CPU from later runs. On Windows, kill *every* matching process, not just the first
PID found.

**Phantom displays.** Windows retains the last mode in `Win32_VideoController` with no
monitor attached, so the harness will happily render into nothing with no real vblank —
a full sweep was taken this way before anyone noticed. Confirm a physical display, and
check for extra adapters: a panel powered from a Thunderbolt port also appeared as a
second display on the iGPU, and cross-GPU compositing cut frame rate by 2.6×.

**Windows GUI apps cannot run from an ssh session** (session 0, no desktop). They must be
launched via `schtasks` with `/it`. Scheduled tasks do not inherit environment set over
ssh, which is why `fpt-measure.ps1` rewrites the launcher `.bat` on each run.
