package dev.packupdater;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 *
 * <p>The probe is advisory. It can withhold a window when it positively cannot open one, but any
 * inconclusive result is treated as "a window is available", because a probe that misfires must
 * not cost a working setup its optional mods.
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

    /**
     * Runs the probe in a child JVM.
     *
     * <p>Every inconclusive outcome resolves to "usable". The probe exists to catch platforms
     * that genuinely cannot open a window, so it must never be able to remove one that works. An
     * earlier version treated silence as a failure and, because NeoForge loads mods in a module
     * layer rather than on the classpath, the probe could never start and so silently took the
     * window away from every desktop user.
     */
    private static Result probe() {
        String javaBin = Path.of(System.getProperty("java.home", ""), "bin", "java").toString();
        if (!new File(javaBin).canExecute()) {
            PackUpdater.LOGGER.debug("[PackUpdater] No usable java binary for the window probe, assuming a window is available");
            return new Result(true, null);
        }

        Path probeDir;
        try {
            probeDir = extractProbe();
        } catch (IOException e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Could not unpack the window probe, assuming a window is available: {}", e.toString());
            return new Result(true, null);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(probeDir.toString());
        command.add(GuiProbe.class.getName());

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (Exception e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Could not start the window probe, assuming a window is available: {}", e.toString());
            return new Result(true, null);
        }

        String verdict = null;
        List<String> diagnostics = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (verdict == null && (line.startsWith(GuiProbe.OK) || line.startsWith(GuiProbe.FAIL_PREFIX))) {
                    verdict = line;
                } else {
                    // Keep the tail so a probe that dies early says why, instead of leaving a
                    // bare "no answer" with nothing to act on.
                    diagnostics.add(line);
                    if (diagnostics.size() > 6) {
                        diagnostics.remove(0);
                    }
                }
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                PackUpdater.LOGGER.debug("[PackUpdater] Window probe did not finish in 30s, assuming a window is available");
                return new Result(true, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(true, null);
        } catch (Exception e) {
            return new Result(true, null);
        } finally {
            deleteQuietly(probeDir);
        }

        if (verdict == null) {
            PackUpdater.LOGGER.debug(
                    "[PackUpdater] Window probe gave no verdict, assuming a window is available. Output: {}", diagnostics);
            return new Result(true, null);
        }
        if (verdict.startsWith(GuiProbe.OK)) {
            return new Result(true, null);
        }
        return new Result(false, verdict.replace(GuiProbe.FAIL_PREFIX, "window probe failed:").trim());
    }

    /**
     * Writes {@link GuiProbe} into a temporary directory so it can be run on a plain classpath.
     *
     * <p>Its bytes are read as a classloader resource rather than located on disk, because a
     * NeoForge mod lives in a module layer and is not on {@code java.class.path} at all. Reading
     * the resource works regardless of how the mod was loaded.
     */
    private static Path extractProbe() throws IOException {
        String resource = "/" + GuiProbe.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (var in = GuiCapability.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("window probe class not found on the classpath: " + resource);
            }
            bytes = in.readAllBytes();
        }
        Path dir = Files.createTempDirectory("packupdater-probe");
        Path classFile = dir.resolve(GuiProbe.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return dir;
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing an update over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
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
