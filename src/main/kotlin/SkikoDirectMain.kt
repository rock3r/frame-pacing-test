@file:OptIn(ExperimentalSkikoApi::class)

import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.lang.reflect.Method
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Skiko-direct validation mode: the same draw-phase spinner scenario as the ComposePanel app in
 * Main.kt, but driving a [SkiaSwingLayer] directly (no Compose), against the skiko that owns the
 * pacing — built from
 * the ~/src/skiko-frame-pacing worktree and published to mavenLocal as 0.0.0-SNAPSHOT
 * (`./gradlew :skiko:publishToMavenLocal` there). Run with `./gradlew runSkikoDirect
 * -Ppacing=true|false`, which prepends that skiko on the runtime classpath (the runLocalCmp
 * pattern) and maps `-Ppacing` onto the `skiko.swing.frame.pacing` system property.
 *
 * Invalidation-driven animation: every rendered frame requests the next one through
 * `SkiaSwingLayer.needRender()`. This module compiles against the released skiko that CMP pins
 * (which predates `needRender`), so the call is made reflectively; if it is absent at runtime,
 * the scene falls back to plain `repaint()` — i.e. always-unpaced behavior.
 */
fun main() {
    println("java.home=${System.getProperty("java.home")}")
    println("java.runtime.version=${System.getProperty("java.runtime.version")}")
    println("skiko.swing.frame.pacing=${System.getProperty("skiko.swing.frame.pacing")}")

    val needRender: Method? = try {
        SkiaSwingLayer::class.java.getMethod("needRender")
    } catch (_: NoSuchMethodException) {
        null
    }
    println(
        if (needRender != null) "SkiaSwingLayer.needRender(): available (pacing-capable skiko)"
        else "SkiaSwingLayer.needRender(): ABSENT in this skiko — falling back to repaint()"
    )

    SwingUtilities.invokeLater {
        lateinit var layer: SkiaSwingLayer

        val renderDelegate = SkikoRenderDelegate { canvas, width, height, nanoTime ->
            RenderCounter.tick()

            canvas.clear(Color.makeRGB(24, 24, 24))
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 4f
            val angle = ((nanoTime / 1_000_000) % 1_000L) * 360f / 1_000f
            val paint = Paint().apply {
                color = Color.makeRGB(90, 200, 250)
                mode = PaintMode.STROKE
                strokeWidth = 24f
            }
            canvas.drawArc(
                cx - radius, cy - radius, cx + radius, cy + radius,
                angle, 270f, false, paint
            )
            paint.close()

            // Invalidation-driven animation: each frame requests the next.
            if (needRender != null) needRender.invoke(layer) else layer.repaint()
        }

        layer = SkiaSwingLayer(renderDelegate)

        JFrame("skiko-direct frame pacing validation").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            contentPane.add(layer)
            // Same sweep variable as Main.kt: render area, with the display mode held fixed.
            val winSpec = System.getProperty("window", "1600x1000").split("x")
            size = Dimension(winSpec[0].trim().toInt(), winSpec[1].trim().toInt())
            placeOnDisplay(this)
            isVisible = true
            // -Dfullscreen=true asks the compositor for a real fullscreen surface.
            // Whether VRR engages at all may depend on the surface being scanned
            // out directly, which a windowed client never is -- so this is the
            // control that separates "windowed apps cannot get VRR" from "VRR is
            // not working on this machine".
            if (System.getProperty("fullscreen") == "true") {
                goFullScreen(this)
            }
        }
    }
}

/**
 * Puts the window on the screen chosen by -Ddisplay=N, defaulting to the
 * primary. Needed because the pacer resolves its display once, from the
 * frame's GraphicsConfiguration, so a mixed-refresh setup can only be measured
 * by launching on each display in turn -- dragging the window afterwards
 * changes nothing.
 */
private fun placeOnDisplay(frame: JFrame) {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val screens = env.screenDevices
    val primary = env.defaultScreenDevice
    for ((i, device) in screens.withIndex()) {
        val b = device.defaultConfiguration.bounds
        val m = device.displayMode
        println(
            "display[$i]: ${device.iDstring} ${m.width}x${m.height}@${m.refreshRate}Hz " +
                "at (${b.x},${b.y})${if (device == primary) " PRIMARY" else ""}"
        )
    }

    val index = System.getProperty("display")?.trim()?.toIntOrNull()
    val target = if (index != null && index in screens.indices) screens[index] else primary
    val b = target.defaultConfiguration.bounds
    frame.setLocation(
        b.x + (b.width - frame.width) / 2,
        b.y + (b.height - frame.height) / 2,
    )
}

/**
 * Requests fullscreen on the window's own screen. setFullScreenWindow is the
 * request that maps to a fullscreen surface; if the platform refuses it (it is
 * advisory, and returns silently), fall back to an undecorated window covering
 * the screen so the run still happens and the log says which path was taken.
 */
private fun goFullScreen(frame: JFrame) {
    val device = frame.graphicsConfiguration?.device
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
    println("fullscreen: requesting on ${device.iDstring} (supported=${device.isFullScreenSupported})")
    try {
        device.fullScreenWindow = frame
    } catch (t: Throwable) {
        println("fullscreen: setFullScreenWindow threw $t")
    }
    if (device.fullScreenWindow !== frame) {
        val b = device.defaultConfiguration.bounds
        println("fullscreen: request not honoured, covering ${b.width}x${b.height} instead")
        frame.isVisible = false
        frame.isUndecorated = true
        frame.bounds = b
        frame.isVisible = true
    } else {
        println("fullscreen: granted")
    }
}
