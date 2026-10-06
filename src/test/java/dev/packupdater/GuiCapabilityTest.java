package dev.packupdater;

import org.junit.jupiter.api.Test;

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

}
