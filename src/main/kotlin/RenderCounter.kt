import java.util.concurrent.atomic.AtomicLong

/**
 * Counts scene renders (draw-block executions) and prints renders/sec once
 * per second, so baseline vs paced render rates are directly comparable.
 *
 * When paced, also counts FramePacing ticks. The tick rate is what the clock
 * actually delivers, which is not necessarily the refresh period the API
 * advertises for that display: on Windows every clock ticks at the primary
 * display's cadence regardless of which display it was created for. Printing
 * both makes that mismatch visible without any power instrumentation.
 */
object RenderCounter {
    private val renders = AtomicLong()
    private val ticks = AtomicLong()

    init {
        Thread {
            var lastRenders = 0L
            var lastTicks = 0L
            while (true) {
                Thread.sleep(1000)
                val nowRenders = renders.get()
                val nowTicks = ticks.get()
                val tickRate = nowTicks - lastTicks
                val paced = if (nowTicks == 0L) "" else " ticks/sec: $tickRate"
                println("renders/sec: ${nowRenders - lastRenders}$paced")
                lastRenders = nowRenders
                lastTicks = nowTicks
            }
        }.apply {
            isDaemon = true
            name = "render-counter"
            start()
        }
    }

    fun tick() {
        renders.incrementAndGet()
    }

    fun pacingTick() {
        ticks.incrementAndGet()
    }
}
