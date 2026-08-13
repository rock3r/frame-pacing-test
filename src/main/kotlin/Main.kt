import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * FramePacing spike validation app.
 *
 * A large ComposePanel (lightweight Swing / SwingGraphics mode) whose only
 * animation is a draw-phase infinite rotation: the animated value feeds a
 * rotate() inside a Canvas draw block, so invalidation is pure redraw with
 * no recomposition-driven work.
 *
 * Run with -Dpacing=on to install [PacedRepaintManager], which coalesces the
 * Compose layer's repaints onto JBR FramePacing ticks. Run with -Dpacing=off
 * (default) for the unpaced baseline.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val paced = System.getProperty("pacing", "off") == "on"
    System.setProperty("apple.awt.application.appearance", "system")
    SwingUtilities.invokeLater {
        val frame = JFrame("FramePacing test — pacing=${if (paced) "on" else "off"}")
        val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        panel.setContent { SpinnerScene() }
        frame.contentPane.add(panel)
        // Render area decides whether the unpaced loop can outrun the display, so make it
        // the sweep variable: -Dwindow=WxH (default 1600x1000, the original size).
        val winSpec = System.getProperty("window", "1600x1000").split("x")
        frame.setSize(winSpec[0].trim().toInt(), winSpec[1].trim().toInt())
        placeOnDisplay(frame)
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.isVisible = true
        if (paced) {
            PacedRepaintManager.install(frame)
        }
    }
}

/**
 * Puts the window on the screen selected by -Ddisplay=N (index into the
 * enumeration printed below; default is whichever screen the toolkit picks).
 *
 * The pacer resolves its display once, from the frame's GraphicsConfiguration
 * at install time, and never re-resolves — so a mixed-refresh setup has to be
 * tested by *launching* on each display in turn, not by dragging the window
 * between them.
 */
private fun placeOnDisplay(frame: JFrame) {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val screens = env.screenDevices
    val primary = env.defaultScreenDevice
    for ((i, device) in screens.withIndex()) {
        val bounds = device.defaultConfiguration.bounds
        val mode = device.displayMode
        println(
            "display[$i]: ${device.iDstring} ${mode.width}x${mode.height}@${mode.refreshRate}Hz " +
                "at (${bounds.x},${bounds.y})${if (device == primary) " PRIMARY" else ""}"
        )
    }

    val index = System.getProperty("display")?.trim()?.toIntOrNull() ?: return
    require(index in screens.indices) { "-Ddisplay=$index but only ${screens.size} screen(s) found" }
    val bounds = screens[index].defaultConfiguration.bounds
    frame.setLocation(
        bounds.x + (bounds.width - frame.width) / 2,
        bounds.y + (bounds.height - frame.height) / 2,
    )
}

@Composable
private fun SpinnerScene() {
    val transition = rememberInfiniteTransition("CircularProgressIndicator")
    val rotation by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(easing = LinearEasing, durationMillis = 1000),
                    repeatMode = RepeatMode.Restart,
                ),
        )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(48.dp)) {
            RenderCounter.tick()
            rotate(degrees = rotation, pivot = center) {
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(center.x - 2f, center.y - 10f),
                    size = Size(4f, 20f),
                )
            }
        }
    }
}
