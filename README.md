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
