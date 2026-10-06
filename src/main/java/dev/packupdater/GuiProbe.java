package dev.packupdater;

/**
 * Probes whether this JVM can open a real top-level window.
 *
 * <p>Run as its own short-lived child process. The result is only meaningful in the child,
 * because that is the JVM which will host the installer. The game JVM's own answer is
 * actively misleading: some launchers, notably the Android ones, give the game a working AWT
 * by putting an implementation on the boot classpath, so the parent can open windows while a
 * forked child cannot. Nothing about the parent's AWT predicts the child's.
 *
 * <p>Predicates such as {@code GraphicsEnvironment.isHeadless()} do not help. They report only
 * whether {@code java.awt.headless} was set, which a launcher frequently sets to
 * {@code false} while pointing {@code DISPLAY} at a display that does not exist. Building a
 * window is the only operation that actually exercises the connection.
 *
 * <p>Prints exactly one line: {@code PACKUPDATER_GUI_OK}, or
 * {@code PACKUPDATER_GUI_FAIL <exception> <message>}. The window is created off-screen and at
 * the smallest legal size so a successful probe is not visible.
 */
public final class GuiProbe {

    public static final String OK = "PACKUPDATER_GUI_OK";
    public static final String FAIL_PREFIX = "PACKUPDATER_GUI_FAIL";

    private GuiProbe() {}

    public static void main(String[] args) {
        System.out.println(probe());
        // AWT may leave non-daemon threads running, and the parent enforces a timeout anyway,
        // but exiting promptly keeps the probe cheap. Split out from probe() so tests can call
        // it without killing the test JVM.
        System.exit(0);
    }

    /**
     * Builds a real off-screen window and reports whether that worked.
     *
     * @return {@link #OK}, or {@link #FAIL_PREFIX} followed by the exception and its message.
     *         Always a single line, so the parent can parse stdout reliably.
     */
    public static String probe() {
        javax.swing.JFrame frame = null;
        try {
            frame = new javax.swing.JFrame("packupdater-probe");
            frame.setSize(1, 1);
            frame.setLocation(-32000, -32000);
            frame.setVisible(true);
            return OK;
        } catch (Throwable t) {
            String message = t.getMessage();
            if (message == null || message.isBlank()) {
                message = t.getClass().getName();
            }
            // Newlines would break the single-line protocol the parent parses.
            message = message.replace('\n', ' ').replace('\r', ' ');
            return FAIL_PREFIX + " " + t.getClass().getName() + " " + message;
        } finally {
            if (frame != null) {
                try {
                    frame.dispose();
                } catch (Throwable ignored) {
                    // Nothing useful to do; the probe result is already printed.
                }
            }
        }
    }
}
