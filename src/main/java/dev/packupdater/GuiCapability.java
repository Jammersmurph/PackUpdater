package dev.packupdater;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Decides whether a forked JVM could open the installer's window, by asking one.
 *
 * <p>The answer only exists in the child, because that is the JVM which will host the
 * installer. Inferring it from the parent does not work. Two natural guesses were tried and both
 * are wrong on the platforms this exists for:
 *
 * <ul>
 *   <li>{@code GraphicsEnvironment.isHeadless()} reports only whether
 *       {@code java.awt.headless} was set. Android launchers set it to {@code false} while
 *       pointing {@code DISPLAY} at a display that does not exist, so it returns
 *       {@code false} and a window still cannot be opened.
 *   <li>Asking the parent for its AWT toolkit's code source assumes a supplied implementation
 *       comes from a jar. Launchers supply it through {@code -Xbootclasspath}, and a
 *       bootclasspath-loaded class reports a {@code null} code source, exactly like a stock
 *       JDK toolkit, so the two cannot be told apart.
 * </ul>
 *
 * <p>So a short-lived child is started and asked to open a real window. It answers on stdout
 * and exits. That costs one JVM start, which is far cheaper than a failed update or a crash.
 */
public final class GuiCapability {

    private static Result cached;

    private GuiCapability() {}

    /** @return {@code null} if a window could be opened, otherwise why not. */
    public static synchronized String forkedGuiUnusable() {
        if (cached == null) {
            cached = probe();
        }
        return cached.usable() ? null : cached.reason();
    }

    /** Only for tests. */
    static void reset() {
        cached = null;
    }

    private record Result(boolean usable, String reason) {}

    private static Result probe() {
        String javaBin = Path.of(System.getProperty("java.home", ""), "bin", "java").toString();
        if (!new File(javaBin).canExecute()) {
            return new Result(false, "cannot locate a java binary to probe with");
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // Launch exactly as the installer will be launched: our own code source, no extra
        // -D flags. Anything added here would make the probe more capable than the real run.
        command.add("-cp");
        command.add(ownClasspath());
        command.add(GuiProbe.class.getName());
        command.add("-Djava.awt.headless=false");

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (Exception e) {
            // If we cannot even probe, assume the window works. Being wrong here only means we
            // attempt the GUI and fall back on failure, which is the existing behaviour.
            return new Result(true, null);
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(GuiProbe.OK) || line.startsWith(GuiProbe.FAIL_PREFIX)) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "the GUI probe did not finish within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(true, null);
        } catch (Exception e) {
            return new Result(true, null);
        }

        String verdict = output.toString().trim();
        if (verdict.isEmpty()) {
            // The probe died before answering. On the platforms this targets, AWT failing to
            // initialise often aborts the JVM outright, which can leave nothing on stdout.
            // Treating silence as a failure is what lets the fallback engage at all.
            return new Result(false, "the GUI probe exited without an answer (no window support?)");
        }
        if (verdict.contains(GuiProbe.OK)) {
            return new Result(true, null);
        }
        return new Result(false, verdict.replace(GuiProbe.FAIL_PREFIX, "window probe failed:").trim());
    }

    /** This jar, so the child loads the same classes the game would. */
    private static String ownClasspath() {
        try {
            var source = GuiCapability.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return new File(source.getLocation().toURI()).getPath();
            }
        } catch (Exception ignored) {
            // Fall through to the classpath property.
        }
        String classpath = System.getProperty("java.class.path", "");
        return classpath.isBlank() ? "." : classpath;
    }

    /**
     * Whether output from a failed installer run looks like a windowing failure rather than a
     * genuine problem with the pack, so a 404 or a hash mismatch is never mistaken for a
     * reason to retry without a window.
     */
    public static boolean looksLikeGuiFailure(String output) {
        if (output == null || output.isBlank()) {
            // A windowing failure often kills the child before it can print anything. Without
            // this, the most common real-world failure would never trigger the fallback.
            return true;
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
            "dlopen",
            "xtoolkit",
        };
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
