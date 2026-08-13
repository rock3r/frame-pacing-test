@file:OptIn(ExperimentalSkikoApi::class)

import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Dimension
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
            size = Dimension(800, 600)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
