import sun.awt.FramePacing;

import java.awt.GraphicsEnvironment;
import java.lang.ref.Reference;

/**
 * Subscribes to the platform FramePacing service and reports tick-interval
 * statistics: the end-to-end jitter of the clock as a client observes it.
 *
 * Run with the built JBR:
 *   java --add-exports java.desktop/sun.awt=ALL-UNNAMED \
 *        --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED \
 *        [-Djbr.framePacing.forceEstimated=true] JitterProbe.java [samples] [screenIndex]
 */
public class JitterProbe {
    public static void main(String[] args) throws Exception {
        int samples = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        int screenIndex = args.length > 1 ? Integer.parseInt(args[1]) : -1;

        String os = System.getProperty("os.name").toLowerCase();
        String className = os.contains("mac") ? "sun.awt.FramePacingMac"
                : os.contains("windows") ? "sun.awt.FramePacingWin"
                : "sun.awt.FramePacingUnix";
        FramePacing service = (FramePacing) Class.forName(className)
                .getDeclaredConstructor().newInstance();

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.awt.GraphicsDevice device = screenIndex >= 0
                ? ge.getScreenDevices()[screenIndex]
                : ge.getDefaultScreenDevice();
        long displayId = service.displayId(device.getDefaultConfiguration());
        System.out.printf("device=%s (%d screens)  quality=%d displayId=%d refreshPeriod=%.4fms%n",
                device.getIDstring(), ge.getScreenDevices().length,
                service.getQuality(), displayId,
                service.refreshPeriodNanos(displayId) / 1e6);

        long[] t = new long[samples + 1];
        int[] n = {0};
        Object done = new Object();
        FramePacing.Listener listener = (id, timeNanos) -> {
            synchronized (done) {
                if (n[0] <= samples) t[n[0]++] = timeNanos;
                if (n[0] > samples) done.notifyAll();
            }
        };

        FramePacing.Subscription sub = service.subscribe(displayId, listener);
        if (sub == null) {
            System.out.println("subscribe returned null");
            return;
        }

        synchronized (done) {
            long deadline = System.currentTimeMillis() + 60_000;
            while (n[0] <= samples && System.currentTimeMillis() < deadline) {
                done.wait(1000);
            }
        }
        sub.close();
        Reference.reachabilityFence(listener);

        int count;
        synchronized (done) {
            count = Math.min(n[0], samples + 1) - 1;
        }
        if (count < 2) {
            System.out.println("NO TICKS (" + count + " intervals)");
            return;
        }

        double sum = 0, min = 1e18, max = 0;
        double[] iv = new double[count];
        for (int i = 0; i < count; i++) {
            iv[i] = (t[i + 1] - t[i]) / 1e6;
            sum += iv[i];
            min = Math.min(min, iv[i]);
            max = Math.max(max, iv[i]);
        }
        double mean = sum / count, var = 0;
        for (double v : iv) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / count);
        System.out.printf(
                "samples %d  mean %.3fms  sd %.3fms  min %.3fms  max %.3fms  -> %.2f Hz%n",
                count, mean, sd, min, max, 1000.0 / mean);
    }
}
