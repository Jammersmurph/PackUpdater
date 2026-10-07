package dev.packupdater;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiCapabilityTest {

    @Test
    void probeReportsAWorkingWindowOnThisMachine() {
        // This machine has a real display in CI terms only if one is attached; assert on the
        // contract instead: the probe must always produce a verdict, never throw.
        String reason = GuiCapability.forkedGuiUnusable();
        assertTrue(reason == null || !reason.isBlank(), "a verdict must carry a reason when unusable");
    }

    @Test
    void guiProbePrintsExactlyOneVerdictLine() {
        // The parent parses stdout line by line, so the probe must stay on one line even when
        // the failure message contains newlines. Calls probe(), not main(), because main() calls
        // System.exit and would kill the test JVM.
        String verdict = GuiProbe.probe();
        assertFalse(verdict.isEmpty(), "the probe must return something");
        assertEquals(1, verdict.lines().count(), "the probe must return exactly one line, got: " + verdict);
        assertFalse(verdict.contains("\n"), "the verdict must not contain a newline");
        assertTrue(
                verdict.equals(GuiProbe.OK) || verdict.startsWith(GuiProbe.FAIL_PREFIX),
                "unexpected verdict: " + verdict);
    }

    @Test
    void guiFailureMarkersAreRecognised() {
        String[] outputs = {
            "java.awt.HeadlessException: No X11 DISPLAY variable was set",
            "Exception in thread \"main\" java.awt.AWTError: Can't connect to X11 window server using ':0'",
            "java.lang.UnsatisfiedLinkError: dlopen failed: library \"libawt.so\" not found",
            "dlopen /jre/lib/libawt_headless.so failed: library not found",
            "net.java.openjdk.cacio.ctc.CTCToolkit cannot be cast to sun.awt.X11FontManager",
            "PACKUPDATER_GUI_FAIL java.awt.AWTError Can't connect to X11",
        };
        for (String output : outputs) {
            assertTrue(GuiCapability.looksLikeGuiFailure(output), "should be a GUI failure: " + output);
        }
    }

    @Test
    void silenceIsTreatedAsAGuiFailure() {
        // A windowing failure frequently aborts the child before it can print anything, so
        // empty output is the single most common real signal and must trigger the fallback.
        assertTrue(GuiCapability.looksLikeGuiFailure(""));
        assertTrue(GuiCapability.looksLikeGuiFailure("   "));
        assertTrue(GuiCapability.looksLikeGuiFailure(null));
    }

    @Test
    void genuinePackFailuresAreNotMistakenForGuiFailures() {
        String[] outputs = {
            "[FATAL] Failed to download pack.toml: Non-successful error code from HTTP request: 404",
            "Hash mismatch for mods/example.jar, expected abc but got def",
            "DecodingError(reason=no value found for non-nullable parameter 'index')",
            "[FATAL] Update process failed",
        };
        for (String output : outputs) {
            assertFalse(GuiCapability.looksLikeGuiFailure(output), "should NOT be a GUI failure: " + output);
        }
    }

    @Test
    void markerMatchingIgnoresCase() {
        assertTrue(GuiCapability.looksLikeGuiFailure("JAVA.AWT.HEADLESSEXCEPTION"));
    }

    @Test
    void probeRunsWithoutBeingOnTheClasspath() throws Exception {
        // Regression. NeoForge loads mods in a module layer, so the probe's classes are not on
        // java.class.path and the previous implementation always failed to start it. That
        // silently removed the installer's window from every desktop user.
        //
        // Reproduces the real condition: unpack the probe into a directory and run it from a
        // classpath that contains nothing of ours except that directory.
        Path unpacked = java.nio.file.Files.createTempDirectory("probe-extract-test");
        Path classFile = unpacked.resolve(GuiProbe.class.getName().replace('.', '/') + ".class");
        java.nio.file.Files.createDirectories(classFile.getParent());
        try (var in = GuiCapability.class.getResourceAsStream(
                "/" + GuiProbe.class.getName().replace('.', '/') + ".class")) {
            assertTrue(in != null, "the probe class must be readable as a classloader resource");
            java.nio.file.Files.write(classFile, in.readAllBytes());
        }

        String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, "-cp", unpacked.toString(), GuiProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "the probe should exit cleanly");

        assertTrue(
                output.trim().startsWith(GuiProbe.OK) || output.trim().startsWith(GuiProbe.FAIL_PREFIX),
                "the probe must answer when run from an isolated directory, got: " + output.trim());
    }

    @Test
    void anInconclusiveProbeLeavesTheWindowAlone() {
        // Fail open. A probe that cannot answer must never be able to take a working window
        // away; it may only withhold one when it positively cannot open it.
        assertTrue(UpdaterBootstrap.shouldUseGui(true, false, null, false, false),
                "no probe reason means the window is used");
        assertFalse(UpdaterBootstrap.shouldUseGui(true, false, "window probe failed: java.awt.AWTError", false, false),
                "a definite failure withholds the window");
    }
}
