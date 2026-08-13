# FramePacing spike test harness

Validates the JBR FramePacing API spike (JetBrainsRuntime branch
`frame-pacing-spike`, jbr-api branch `frame-pacing-spike`) with the scenario
from the plan: a large ComposePanel (lightweight Swing / SwingGraphics mode)
whose only animation is a draw-phase infinite rotation — a 48 dp spinner, no
recomposition, invalidation is pure redraw. This mirrors the Android Studio
agent-timeline shimmer pathology: low CPU from Compose, GPU saturated by the
unthrottled redraw loop.

## Modes

- `-Dpacing=off` (default) — unpaced baseline. Compose invalidation calls
  `repaint()` on the SkiaSwingLayer and the EDT paints as fast as it can.
- `-Dpacing=on` — installs `PacedRepaintManager`, which holds the Compose
  layer's dirty regions and releases them once per `JBR.getFramePacing()`
  tick (the plan's Phase-2 "coalesce one repaint per tick" client, done at
  the app level via RepaintManager so no CMP fork is needed).

## Running

Requires the spike JBR build as `JAVA_HOME`:

```bash
./gradlew installDist
JAVA_HOME=~/src/JetBrainsRuntime/build/macosx-aarch64-server-release/images/jdk \
FRAME_PACING_TEST_OPTS='-Dpacing=on' \
./build/install/frame-pacing-test/bin/frame-pacing-test
```

The app prints `renders/sec` (scene draw executions) once per second, and the
pacer prints the resolved quality/display/refresh period at startup.

### Skiko-direct mode

`SkikoDirectMain.kt` drives a `SkiaSwingLayer` directly (no Compose) to validate the
pacing that lives *inside* skiko (the `~/src/skiko-frame-pacing` worktree — see its
REVIEW.md), gated by `skiko.swing.frame.pacing`:

```bash
# once, in ~/src/skiko-frame-pacing: ./gradlew :skiko:publishToMavenLocal
./gradlew runSkikoDirect -Ppacing=false   # unpaced baseline, renders/sec ≫ refresh
./gradlew runSkikoDirect -Ppacing=true    # locks to the display refresh
```

The mavenLocal skiko `0.0.0-SNAPSHOT` jars are prepended on the runtime classpath
(the `runLocalCmp` pattern); `needRender()` is called reflectively because the module
compiles against the released skiko that CMP pins.

Measured 2026-08-06 (same machine/display as below): unpaced ~500–570 renders/sec,
GPU ~1.4–1.5 W; paced locks to 119–121 renders/sec, GPU ~1.05–1.27 W. The arc scene
is far cheaper than the ComposePanel one, so the GPU stays at min clock even
unpaced — the cadence lock is the result to look at here.

## Measured results (2026-08-05, MacBookPro18,2, 120 Hz ProMotion)

`sudo powermetrics --samplers gpu_power`, Claude/other GUI noise minimized:

| | Idle | Baseline (unpaced) | Paced (FramePacing) |
|---|---|---|---|
| renders/sec | — | ~1229 | ~121 (locked to 120 Hz) |
| Process CPU | — | ~70% | ~33% |
| GPU residency | ~18% @ 389 MHz | 100% @ 1296 MHz (max clock) | ~90% @ 389 MHz (min clock) |
| GPU power | ~0.11 W | **~14.7 W** | **~1.16 W** |

GPU cost attributable to the app: baseline ~14.6 W over idle vs paced
~1.05 W over idle — a ~93% reduction; the paced app uses ~8% of the GPU
power budget the unpaced app burns. Note that on Apple Silicon "active
residency" alone is misleading across frequency states: the paced run shows
90% residency but at the minimum clock — power is the comparable metric.
Per-process GPU attribution (Activity Monitor / powermetrics `--show-process-gpu`)
reports 0 for all processes on this OS build (25F84); the compositing cost
lands in WindowServer.

The remaining ~1 W paced cost is the SwingGraphics GPU→CPU→Swing copy per
frame — the zero-copy problem, out of scope for FramePacing.

## Measured results — Windows (2026-08-05, RTX 3080 Ti, 59 Hz display)

Per-process `GPU Engine` counters via `Get-Counter`, app in the interactive
session:

| | Baseline (unpaced) | Paced (FramePacing) |
|---|---|---|
| renders/sec | ~100–140 | ~48–58 (tick period 16.95 ms) |
| Process CPU (one core) | ~89% | ~63% |
| Process GPU | ~8–19% | ~8–17% |

The D3D present path already half-bounds the unpaced loop (~2× refresh vs
~10× on macOS), and an idle 3080 Ti doesn't stress on this scene — Windows
shows the cadence lock and CPU win rather than a GPU collapse.

## Measured results — Linux (2026-08-05, Ubuntu 26.04 VM, Xvfb)

Functional validation only (software rendering; GPU numbers meaningless in a
VM): service registers, `refreshPeriodNanos` correctly unknown under Xvfb,
60 Hz fallback engages, coalescing degrades gracefully when render is slower
than the tick (at most one repaint per tick).

| | Baseline (unpaced) | Paced (FramePacing) |
|---|---|---|
| renders/sec | ~46–81 | ~29–33 |
| Process CPU (one core) | ~89% | ~74% |
