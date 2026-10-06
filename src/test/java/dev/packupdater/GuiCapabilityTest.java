package dev.packupdater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiCapabilityTest {

    private static String reason(boolean headless, String codeSource) {
        return GuiCapability.forkedGuiUnusable(() -> headless, () -> codeSource);
    }

    @Test
    void desktopWithStockToolkitKeepsItsWindow() {
        // A stock JDK reports a null code source for its own toolkit. This is the case that
        // must never change, or every working desktop loses the installer's window.
        assertNull(reason(false, null), "a stock JDK toolkit must not block the GUI");
        assertNull(reason(false, "   "), "a blank code source must not block the GUI");
        assertNull(reason(false, "jrt:/java.desktop/sun/awt/X11/XToolkit.class"),
                "a JDK module toolkit must not block the GUI");
    }

    @Test
    void headlessSessionBlocksTheGui() {
        String reason = reason(true, null);
        assertNotNull(reason);
        assertTrue(reason.contains("headless"));
    }

    @Test
    void externallySuppliedToolkitBlocksTheGui() {
        // Android launchers inject AWT from a jar via -Xbootclasspath, which a child JVM does
        // not inherit. No platform is named here on purpose.
        String reason = reason(false,
                "file:/data/user/0/net.kdt.pojavlaunch/runtimes/Internal/lib/cacio-androidnw.jar");
        assertNotNull(reason, "an externally supplied toolkit must block the GUI");
        assertTrue(reason.contains("cannot inherit"), "reason should explain why: " + reason);
    }

    @Test
    void guiFailureMarkersAreRecognised() {
        String[] outputs = {
            "java.awt.HeadlessException: No X11 DISPLAY variable was set",
            "Exception in thread \"main\" java.awt.AWTError: Assistive Technology not found",
            "java.lang.UnsatisfiedLinkError: dlopen failed: library \"libawt.so\" not found",
            "dlopen /jre/lib/libawt_headless.so failed",
            "net.java.openjdk.cacio.ctc.CTCToolkit cannot be cast to sun.awt.X11FontManager",
            "Can't connect to X11 window server using ':0' as the value of the DISPLAY variable",
        };
        for (String output : outputs) {
            assertTrue(GuiCapability.looksLikeGuiFailure(output), "should be treated as a GUI failure: " + output);
        }
    }

    @Test
    void genuinePackFailuresAreNotMistakenForGuiFailures() {
        String[] outputs = {
            "",
            null,
            "[FATAL] Failed to download pack.toml: Non-successful error code from HTTP request: 404",
            "Hash mismatch for mods/example.jar, expected abc but got def",
            "DecodingError(reason=no value found for non-nullable parameter 'index')",
            "[FATAL] Update process failed",
        };
        for (String output : outputs) {
            assertFalse(GuiCapability.looksLikeGuiFailure(output), "should NOT be treated as a GUI failure: " + output);
        }
    }

    @Test
    void markerMatchingIgnoresCase() {
        assertTrue(GuiCapability.looksLikeGuiFailure("JAVA.AWT.HEADLESSEXCEPTION"));
    }
}