import java.util.concurrent.atomic.AtomicLong

/**
 * Counts scene renders (draw-block executions) and prints renders/sec once
 * per second, so baseline vs paced render rates are directly comparable.
 */
object RenderCounter {
    private val renders = AtomicLong()

    init {
        Thread {
            var last = 0L
            while (true) {
                Thread.sleep(1000)
                val now = renders.get()
                println("renders/sec: ${now - last}")
                last = now
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
}
