import com.jetbrains.FramePacing
import com.jetbrains.JBR
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

/**
 * App-level prototype of the plan's Phase-2 "coalesce one repaint per tick"
 * client. Compose's lightweight Swing layer invalidates by calling repaint()
 * on its SkiaSwingLayer component; rendering happens inside Swing paint. This
 * RepaintManager holds those dirty regions and releases them once per JBR
 * FramePacing tick, so the panel renders at most once per display refresh
 * instead of as fast as the EDT can paint.
 */
class PacedRepaintManager private constructor(
    private val pacing: FramePacing,
    displayId: Long,
) : RepaintManager() {

    private val pending = HashMap<JComponent, Rectangle>()
    private val flushScheduled = AtomicBoolean(false)
    private val subscription =
        pacing.subscribe(displayId, ::onTick)
            ?: error("Display $displayId cannot be paced")

    override fun addDirtyRegion(c: JComponent, x: Int, y: Int, w: Int, h: Int) {
        if (isComposeLayer(c)) {
            synchronized(pending) {
                val rect = pending[c]
                if (rect == null) {
                    pending[c] = Rectangle(x, y, w, h)
                } else {
                    rect.add(Rectangle(x, y, w, h))
                }
            }
            return
        }
        super.addDirtyRegion(c, x, y, w, h)
    }

    private fun onTick(displayId: Long, timeNanos: Long) {
        // Non-EDT tick thread: return immediately, coalesce onto the EDT.
        RenderCounter.pacingTick()
        if (flushScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(::flush)
        }
    }

    private fun flush() {
        flushScheduled.set(false)
        val toFlush: List<Pair<JComponent, Rectangle>>
        synchronized(pending) {
            if (pending.isEmpty()) return
            toFlush = pending.map { (c, r) -> c to r }
            pending.clear()
        }
        for ((c, r) in toFlush) {
            super.addDirtyRegion(c, r.x, r.y, r.width, r.height)
        }
    }

    private fun isComposeLayer(c: JComponent): Boolean {
        var klass: Class<*>? = c.javaClass
        while (klass != null) {
            if (klass.name == "org.jetbrains.skiko.swing.SkiaSwingLayer") return true
            klass = klass.superclass
        }
        return false
    }

    companion object {
        fun install(frame: JFrame) {
            check(JBR.isFramePacingSupported()) {
                "JBR FramePacing not available — run on the spike JBR build"
            }
            val pacing = JBR.getFramePacing()!!
            val displayId = pacing.displayId(frame.graphicsConfiguration)
            check(displayId != -1L) { "Cannot resolve display id for frame" }
            val quality = pacing.quality
            val period = pacing.refreshPeriodNanos(displayId)
            println(
                "FramePacing: quality=$quality displayId=$displayId " +
                    "refreshPeriod=${if (period > 0) "%.2fms".format(period / 1e6) else "unknown"}"
            )
            RepaintManager.setCurrentManager(PacedRepaintManager(pacing, displayId))
        }
    }
}
