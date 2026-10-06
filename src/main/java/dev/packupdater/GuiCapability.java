package dev.packupdater;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Decides whether a forked JVM could plausibly open an AWT window.
 *
 * <p>PackUpdater runs the PackWiz installer in a child process, and the installer's window is
 * what exposes optional mods. That child cannot see the parent JVM's system properties or its
 * {@code -Xbootclasspath}. Some platforms, notably Android launchers, supply AWT from an
 * external jar rather than from the JDK, so the parent has a working window but a child would
 * not.
 *
 * <p>The question this class deliberately does not ask is "what platform is this". It asks
 * "can the child inherit the AWT the parent is using", which needs no list of platforms. A stock
 * JDK serves its own toolkit from the {@code java.desktop} module, which reports a {@code null}
 * code source, or a {@code jrt:} URL. A toolkit supplied from a jar reports that jar instead,
 * and a child process will not have it.
 */
public final class GuiCapability {

    private GuiCapability() {}

    /**
     * @return {@code null} when a forked JVM could reasonably be expected to open a window,
     *         otherwise a short human-readable reason why it should not be attempted.
     */
    public static String forkedGuiUnusable(BooleanSupplier headless, Supplier<String> toolkitCodeSource) {
        if (headless.getAsBoolean()) {
            return "the session is headless";
        }

        String source = toolkitCodeSource.get();
        if (source == null || source.isBlank()) {
            // A stock JDK reports null here. Not knowing must never cost a working setup its
            // window, so an unknown source is treated as usable and a real failure is handled
            // by the caller retrying.
            return null;
        }
        if (!source.startsWith("jrt:")) {
            return "AWT is supplied externally (" + source + "), which a forked JVM cannot inherit";
        }
        return null;
    }

    /** Probes this JVM. */
    public static String forkedGuiUnusable() {
        return forkedGuiUnusable(
                () -> java.awt.GraphicsEnvironment.isHeadless(),
                GuiCapability::defaultToolkitCodeSource);
    }

    private static String defaultToolkitCodeSource() {
        try {
            var codeSource = java.awt.Toolkit.getDefaultToolkit()
                    .getClass()
                    .getProtectionDomain()
                    .getCodeSource();
            return codeSource == null ? null : String.valueOf(codeSource.getLocation());
        } catch (Throwable t) {
            // HeadlessException, a missing toolkit, or anything else odd. Unknown, so do not
            // block the GUI on a guess.
            return null;
        }
    }

    /**
     * Whether output from a failed installer run looks like a windowing failure rather than a
     * genuine problem with the pack. Used so a 404 or a hash mismatch is never mistaken for a
     * reason to retry without a GUI.
     */
    public static boolean looksLikeGuiFailure(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {
            "java.awt.headlessexception",
            "no x11 display variable",
            "can't connect to x11",
            "awterror",
            "unsatisfiedlinkerror",
            "libawt",
            "libawt_xawt",
            "cacio",
            "awt.toolkit",
            "no display environment",
        };
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}