// vrrpresent-mac.swift — macOS VRR load generator + presented-time ground truth.
//
// Borderless window on the chosen display with a CAMetalLayer, presenting
// solid-color frames paced by mach_wait_until at a target rate. Each frame's
// actual on-glass time comes from MTLDrawable.addPresentedHandler — the only
// public macOS signal for what the display really did. On a VRR display with
// the target inside the panel's range, presented intervals should track the
// target period; on a fixed-rate display (or if the compositor declines to
// vary) they quantize to multiples of the refresh grid.
//
// Run tools/JitterProbe.java alongside to see what the FramePacing clock
// delivers while this content plays.
//
// Build: swiftc -O vrrpresent-mac.swift -o vrrpresent-mac
// Usage: vrrpresent-mac [targetFps] [seconds] [screenIndex]
import AppKit
import Metal
import QuartzCore

let args = CommandLine.arguments
let targetFps = args.count > 1 ? Double(args[1]) ?? 90.0 : 90.0
let seconds = args.count > 2 ? Double(args[2]) ?? 15.0 : 15.0
let screenIndex = args.count > 3 ? Int(args[3]) ?? 0 : 0

final class PresentStats {
    private var times: [CFTimeInterval] = []
    private var dropped = 0
    private let lock = NSLock()

    func record(_ t: CFTimeInterval) {
        lock.lock()
        if t > 0 { times.append(t) } else { dropped += 1 } // 0 = never shown
        lock.unlock()
    }

    func report(targetPeriodMs: Double, gridPeriodMs: Double) {
        lock.lock()
        let snapshot = times
        let droppedCount = dropped
        lock.unlock()
        guard snapshot.count > 2 else {
            print("not enough presented frames (\(snapshot.count) shown, \(droppedCount) dropped)")
            return
        }
        print("dropped (presentedTime==0): \(droppedCount)")
        var deltas: [Double] = []
        for i in 1..<snapshot.count {
            deltas.append((snapshot[i] - snapshot[i - 1]) * 1000.0)
        }
        let mean = deltas.reduce(0, +) / Double(deltas.count)
        let sd = (deltas.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Double(deltas.count)).squareRoot()
        let sorted = deltas.sorted()
        let p50 = sorted[sorted.count / 2]
        let nearTarget = deltas.filter { abs($0 - targetPeriodMs) < targetPeriodMs * 0.05 }.count
        let nearGrid1 = deltas.filter { abs($0 - gridPeriodMs) < gridPeriodMs * 0.10 }.count
        let nearGrid2 = deltas.filter { abs($0 - 2 * gridPeriodMs) < gridPeriodMs * 0.10 }.count
        print(String(format: "presented %d frames  mean %.3fms  sd %.3fms  p50 %.3fms  min %.3fms  max %.3fms  -> %.2f fps",
                     deltas.count, mean, sd, p50, sorted.first!, sorted.last!, 1000.0 / mean))
        print(String(format: "  intervals within 5%% of target period (%.2fms): %d/%d", targetPeriodMs, nearTarget, deltas.count))
        print(String(format: "  intervals within 10%% of 1x grid (%.2fms): %d, of 2x grid (%.2fms): %d",
                     gridPeriodMs, nearGrid1, 2 * gridPeriodMs, nearGrid2))
        print("  VERDICT: \(nearTarget > deltas.count / 2 ? "presented cadence TRACKS the target — VRR engaged" : "presented cadence quantizes to the refresh grid — VRR not engaged for this surface")")
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.regular)

let screens = NSScreen.screens
for (i, s) in screens.enumerated() {
    print("screen[\(i)]: \(s.localizedName)  \(Int(s.frame.width))x\(Int(s.frame.height))  max \(s.maximumFramesPerSecond) fps\(s == NSScreen.main ? "  MAIN" : "")")
}
guard screenIndex < screens.count else {
    print("screen index \(screenIndex) out of range")
    exit(1)
}
let screen = screens[screenIndex]
let maxFps = Double(screen.maximumFramesPerSecond)
let gridPeriodMs = 1000.0 / maxFps
let targetPeriodMs = 1000.0 / targetFps
print(String(format: "presenting at %.1f fps (%.2fms) for %.0fs on \"%@\" (max %.0f fps, grid %.2fms)",
             targetFps, targetPeriodMs, seconds, screen.localizedName, maxFps, gridPeriodMs))

// With screen: supplied, contentRect is in THAT screen's coordinate space —
// origin zero is the screen's own corner, not global coordinates.
let window = NSWindow(contentRect: NSRect(origin: .zero, size: screen.frame.size),
                      styleMask: .borderless,
                      backing: .buffered, defer: false, screen: screen)
window.level = .normal
window.isOpaque = true
window.backgroundColor = .black

guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
    print("no Metal device")
    exit(1)
}
let metalLayer = CAMetalLayer()
metalLayer.device = device
metalLayer.pixelFormat = .bgra8Unorm
metalLayer.framebufferOnly = true
metalLayer.displaySyncEnabled = true
metalLayer.drawableSize = CGSize(width: screen.frame.width * screen.backingScaleFactor,
                                 height: screen.frame.height * screen.backingScaleFactor)

// Layer-hosting view: assign the layer BEFORE wantsLayer so the view hosts
// our CAMetalLayer instead of creating a backing layer of its own.
let hostView = NSView(frame: NSRect(origin: .zero, size: screen.frame.size))
hostView.layer = metalLayer
hostView.wantsLayer = true
window.contentView = hostView
metalLayer.frame = hostView.bounds
window.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)

let stats = PresentStats()

var timebase = mach_timebase_info_data_t()
mach_timebase_info(&timebase)
func nanosToAbs(_ ns: UInt64) -> UInt64 { ns * UInt64(timebase.denom) / UInt64(timebase.numer) }

let renderThread = Thread {
    // Let the window settle on its display before measuring.
    Thread.sleep(forTimeInterval: 0.5)
    let periodAbs = nanosToAbs(UInt64(1_000_000_000.0 / targetFps))
    var next = mach_absolute_time() + periodAbs
    let end = mach_absolute_time() + nanosToAbs(UInt64(seconds * 1_000_000_000.0))
    var frame = 0
    while mach_absolute_time() < end {
        mach_wait_until(next)
        next += periodAbs
        if next < mach_absolute_time() { next = mach_absolute_time() + periodAbs }
        autoreleasepool {
            guard let drawable = metalLayer.nextDrawable(),
                  let cmd = queue.makeCommandBuffer() else { return }
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = drawable.texture
            pass.colorAttachments[0].loadAction = .clear
            pass.colorAttachments[0].storeAction = .store
            let shade = (frame & 1) == 0 ? 0.18 : 0.22
            pass.colorAttachments[0].clearColor = MTLClearColor(red: shade, green: shade, blue: shade, alpha: 1)
            if let enc = cmd.makeRenderCommandEncoder(descriptor: pass) { enc.endEncoding() }
            drawable.addPresentedHandler { d in stats.record(d.presentedTime) }
            cmd.present(drawable)
            cmd.commit()
            frame += 1
        }
    }
    DispatchQueue.main.async {
        stats.report(targetPeriodMs: targetPeriodMs, gridPeriodMs: gridPeriodMs)
        app.terminate(nil)
    }
}
renderThread.qualityOfService = .userInteractive
renderThread.start()

app.run()
