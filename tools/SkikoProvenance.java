import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;

/**
 * Records what a measurement run is actually running against, so a result can be
 * audited afterwards instead of trusted.
 *
 * Two jars have silently shadowed the ones under test: Compose's jbr-api 1.9.0
 * sorting ahead of jbr-api-SNAPSHOT on a lib/* classpath, and released skiko
 * sitting beside the 0.0.0-SNAPSHOT build. Both failures leave pacing quietly
 * inert rather than raising anything, so the resolved paths belong in the record
 * next to the numbers.
 */
public class SkikoProvenance {

    public static void main(String[] args) {
        System.out.println("== provenance ==");
        where("org.jetbrains.skiko.swing.SkiaSwingLayer", "skiko");
        System.out.println("skiko_needRender=" + hasMethod("org.jetbrains.skiko.swing.SkiaSwingLayer", "needRender"));
        where("com.jetbrains.JBR", "jbr_api");

        System.out.println("== framepacing ==");
        if (GraphicsEnvironment.isHeadless()) {
            // Not a fault: FramePacing refuses to start headless by design, so a
            // headless probe reports "unsupported" for a reason unrelated to the build.
            System.out.println("framepacing=headless (inconclusive -- run inside the session)");
            return;
        }
        try {
            Class<?> jbr = Class.forName("com.jetbrains.JBR");
            Object supported = jbr.getMethod("isFramePacingSupported").invoke(null);
            System.out.println("framepacing_supported=" + supported);
            if (!Boolean.TRUE.equals(supported)) return;

            Object svc = jbr.getMethod("getFramePacing").invoke(null);

            // The service arrives as a generated JBR API proxy whose class is not
            // accessible, so every call has to go through the declared interface.
            // Reflecting on svc.getClass() throws IllegalAccessException instead.
            Class<?> api = Class.forName("com.jetbrains.FramePacing");
            Method quality = api.getMethod("getQuality");
            System.out.println("framepacing_quality=" + quality.invoke(svc));

            Method displayId = api.getMethod("displayId", GraphicsConfiguration.class);
            Method period = api.getMethod("refreshPeriodNanos", long.class);

            System.out.println("== displays ==");
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            for (int i = 0; i < screens.length; i++) {
                GraphicsDevice d = screens[i];
                GraphicsConfiguration gc = d.getDefaultConfiguration();
                long id = (Long) displayId.invoke(svc, gc);
                long ns = (Long) period.invoke(svc, id);
                System.out.printf(
                    "display[%d] id=%s mode=%dx%d@%dHz bounds=%s advertised=%.2fms%s%n",
                    i, d.getIDstring(),
                    d.getDisplayMode().getWidth(), d.getDisplayMode().getHeight(),
                    d.getDisplayMode().getRefreshRate(),
                    gc.getBounds(),
                    ns > 0 ? ns / 1e6 : Double.NaN,
                    d == primary ? " PRIMARY" : "");
            }
        } catch (Throwable t) {
            // A NoSuchMethodError here is the shadowed-jar fault, so name it rather
            // than letting it surface later as "pacing had no effect".
            System.out.println("framepacing_error=" + t);
            try {
                for (Method m : Class.forName("com.jetbrains.FramePacing").getMethods()) {
                    System.out.println("  api_method=" + m.getName());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void where(String cls, String label) {
        try {
            Class<?> c = Class.forName(cls);
            var src = c.getProtectionDomain().getCodeSource();
            System.out.println(label + "_jar=" + (src == null ? "<runtime image>" : src.getLocation()));
        } catch (Throwable t) {
            System.out.println(label + "_jar=<not on classpath: " + t.getClass().getSimpleName() + ">");
        }
    }

    private static boolean hasMethod(String cls, String name) {
        try {
            for (Method m : Class.forName(cls).getDeclaredMethods()) {
                if (m.getName().equals(name)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
