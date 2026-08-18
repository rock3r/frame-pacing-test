# VRR testbench runbook

Answers, per platform: **what does the FramePacing clock deliver on a
variable-refresh display, and does the API's fixed-period model survive?**

The core question is always the same three-scenario matrix, measured with the
platform's vblank probe while a load runs:

| Scenario | Load | VRR-active expectation | Fixed-rate expectation |
|---|---|---|---|
| A: idle desktop | none | vblank at panel's idle floor (min rate) | vblank at mode's fixed rate |
| B: max-rate load | fullscreen presenter at max refresh | vblank ≈ max rate | same |
| C: sub-max load | fullscreen presenter paced inside VRR range (e.g. 48 fps on a 48–120 panel) | **vblank tracks ~48** | vblank stays at fixed rate |

If scenario C tracks content rate, the FramePacing clock on that platform
ticks at a content-driven cadence under VRR and `refreshPeriodNanos()` (a
fixed number) no longer describes the display — that's the API finding to
document, not a bug in the clock. The sleeping-display guard in the native
clocks (reject intervals `< nominal/2`) cannot misfire under VRR: VRR
intervals are always at least the max-rate period.

## Hardware truth table (what we own)

| Box | GPU | VRR capable? |
|---|---|---|
| jbr-bench | UHD 630 (Coffee Lake) | **No.** Intel DP Adaptive-Sync needs Gen11/12+; no HDMI VRR on this gen. Linux VRR must run on other hardware. |
| mattone | RTX 3080 Ti | Yes — G-Sync Compatible over DP, HDMI 2.1 VRR. Needs a VRR display attached. |
| jbr-portable (SSD in mattone) | RTX 3080 Ti | Same hardware under Linux; nvidia driver VRR support varies by compositor (see below). |
| MacBook Pro | Apple silicon | **Yes — the internal ProMotion panel IS a VRR display** (adaptive 24–120 Hz). External Adaptive-Sync displays also supported (macOS 12+, Apple silicon). |
| ARZOPA portable panel | — | Almost certainly not (HDMI input, no FreeSync branding). Verify from EDID when attached: look for a FreeSync range block (`edid-decode < /sys/class/drm/card*-*/edid` on Linux). |
| LG TV (living room) | — | HDMI 2.1 VRR — the designated Windows VRR display if a monitor doesn't materialize (requires physically moving mattone, per the 2026-08-13 decision only if VRR demand is real). |

## Windows (mattone)

1. **Enable**: NVIDIA Control Panel → Set up G-SYNC → enable for the VRR
   display, "windowed and full screen" mode. Verify the display's own OSD/menu
   has FreeSync/VRR on.
2. **Detect**: `vrrpresent 48 5` prints whether
   `DXGI_FEATURE_PRESENT_ALLOW_TEARING` is supported (prerequisite).
3. **Measure**: two terminals (GUI session, not ssh — DXGI enumerates nothing
   in session 0; use `schtasks /it` or run at the console):
   - `vbprobe` (existing) → per-output WaitForVBlank cadence.
   - `vrrpresent 48 30` → borderless fullscreen presenter paced at 48 fps with
     tearing allowed.
   Run the matrix: vbprobe alone (A), `vrrpresent 120` (B), `vrrpresent 48` (C).
4. **FramePacing end-to-end**: run `JitterProbe.java` (this repo, tools/) with
   the JBR build while `vrrpresent 48` runs — does the DXGI clock tick at 48 or
   120?

Build vrrpresent in a VS dev prompt:
`cl /O2 /EHsc vrrpresent.cpp /link d3d11.lib dxgi.lib user32.lib`

## Linux (VRR-capable box only — NOT jbr-bench)

1. **Enable**: GNOME/Mutter gates VRR behind
   `gsettings set org.gnome.mutter experimental-features "['variable-refresh-rate']"`
   then set the refresh mode in Display settings. KDE/kwin enables Adaptive
   Sync per-display in System Settings with no experimental flag — prefer KDE
   or sway for the testbench to keep Mutter's gating out of the variable set.
   nvidia proprietary driver: VRR on Wayland requires recent drivers
   (545+); X11 needs "Allow G-SYNC" in nvidia-settings.
2. **Detect**: `vrrprobe` (this repo) prints per-connector `vrr_capable` and
   per-CRTC `VRR_ENABLED`, then measures vblank cadence.
3. **Measure the matrix**: `vrrprobe` alone (A); fullscreen
   `vkcube --present_mode 2` (B, FIFO at refresh); scenario C needs a paced
   fullscreen client — `vkcube --present_mode 0` (IMMEDIATE) where supported,
   or port vrrpresent's pacing loop to a tiny Vulkan/GL presenter (TODO if C
   is ever needed on Linux before the backend work starts).
4. **FramePacing end-to-end**: JitterProbe with the spike JBR while the load
   runs — the DRM clock delivers whatever the CRTC does, so C shows directly
   whether ticks follow content.

## macOS (this Mac, tomorrow's session)

macOS supports VRR twofold: **ProMotion** internal panels (adaptive 24–120 Hz,
MacBook Pro 2021+) and external **Adaptive-Sync** displays (macOS 12+, Apple
silicon). The internal panel means every measurement so far on this Mac was
already taken on a VRR display — locked at 120 by the fullscreen load, most
likely, but scenario A/C behavior of CVDisplayLink is unmeasured. Note
CVDisplayLink is deprecated (macOS 15) in favor of CADisplayLink with
`preferredFrameRateRange` — a v3-era question for the Mac backend.

With the ARZOPA attached (fixed 120 external + ProMotion internal):
1. **Per-display binding**: JitterProbe per screen index (`JitterProbe 600 0`
   vs `JitterProbe 600 1`) — each display's clock should tick at its own
   cadence; confirms the macOS analog of the Windows mixed-refresh 2x2.
2. **ProMotion idle floor (A)**: JitterProbe on the internal panel with
   nothing animating — does CVDisplayLink idle down (intervals stretch toward
   1/24 s) or hold 120?
3. **Content-rate tracking (C)**: run the harness paced at a sub-max rate on
   the internal panel and re-measure tick cadence.
4. `refreshPeriodNanos` honesty: what does AWT's DisplayMode report for the
   ProMotion panel (120? current rate?) vs what the clock delivers.

## Reporting

Fill one row per (platform, scenario) with: vblank mean/sd/min/max, clock
quality tier, tick cadence, and whether ticks tracked content. The API
conclusion lands in PRD §4 (VRR semantics): either "fixed-period model
documented as max-rate nominal; ticks may be content-driven under VRR" or a
new API surface if clients need the real per-tick deadline.

## macOS ground-truth findings (2026-08-18, ARZOPA in Variable 60-180 mode)

`vrrpresent-mac.swift` (this repo) is the macOS presenter: borderless Metal
window on a chosen screen, paced by mach_wait_until, per-frame ground truth
from MTLDrawable.addPresentedHandler. Build: `swiftc -O vrrpresent-mac.swift
-o vrrpresent-mac`. Gotcha encoded in the source: with `screen:` supplied,
NSWindow's contentRect is in that screen's own coordinates.

Measured: presenting at a steady 90 fps on the VRR panel delivers 89.9 fps
*average* but a broad presented-interval mixture (p50 10.1ms, 5.6-28.7ms) —
neither clean 90Hz nor pure grid quantization. The WindowServer mediates even
fullscreen borderless Metal, so on macOS raw present cadence does NOT drive
panel VRR; frame-rate-range declarations do. Correspondingly, the v2
CADisplayLink backend (CFramePacing.m) leaves preferredFrameRateRange at its
default, so on a VRR display the tick cadence is system-chosen and can differ
between subscribers (measured: 120 in a presenting process vs ~188 in a
window-less probe, same panel, same moment). The scenario-C experiment on
macOS is therefore about frame-rate-range policy, not invalidation frequency.
