package dev.packupdater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window decision is easy to get wrong invisibly: the PackWiz installer's own entry point
 * also falls back to a non-GUI handler when it detects a headless JVM, so forgetting to pass
 * {@code -g} still mostly works. The visible symptom is a HeadlessException on a background
 * thread in the bootstrap, which is easy to miss.
 *
 * <p>That is exactly how a dead check survived a release, so the whole matrix is pinned here.
 */
class GuiDecisionTest {

    private static boolean gui(boolean configured, boolean headless, String probeReason, boolean forced) {
        return UpdaterBootstrap.shouldUseGui(configured, headless, probeReason, forced, false);
    }

    private static boolean gui(
            boolean configured, boolean headless, String probeReason, boolean forced, boolean serverSide) {
        return UpdaterBootstrap.shouldUseGui(configured, headless, probeReason, forced, serverSide);
    }

    @Test
    void ordinaryDesktopGetsTheWindow() {
        // The case that must never regress: a normal machine with a display, nothing forced.
        assertTrue(gui(true, false, null, false), "a desktop player must get the installer's window");
    }

    @Test
    void headlessSessionIsSilent() {
        // Dedicated server or CI. Regression guard: this is the check that went dead in v1.1.0.
        assertFalse(gui(true, true, null, false), "a headless session cannot have a window");
    }

    @Test
    void probeFailureSuppressesTheWindow() {
        assertFalse(
                gui(true, false, "window probe failed: java.awt.AWTError Can't connect to X11", false),
                "a child that cannot open a window must not be asked to");
    }

    @Test
    void configuredOffAlwaysSuppressesTheWindow() {
        assertFalse(gui(false, false, null, false), "packupdater.gui=false must win");
        assertFalse(gui(false, true, null, false));
        assertFalse(gui(false, false, "some reason", false));
    }

    @Test
    void forcedFallbackSuppressesTheWindow() {
        assertFalse(gui(true, false, null, true), "packupdater.fallback=always must skip the window");
    }

    @Test
    void everySuppressedCombinationIsSilent() {
        for (boolean configured : new boolean[] {true, false}) {
            for (boolean headless : new boolean[] {true, false}) {
                for (String reason : new String[] {null, "a reason"}) {
                    for (boolean forced : new boolean[] {true, false}) {
                        boolean decided = gui(configured, headless, reason, forced);
                        boolean expected = configured && !headless && reason == null && !forced;
                        assertTrue(
                                expected == decided,
                                "mismatch for gui=" + configured + " headless=" + headless
                                        + " reason=" + reason + " forced=" + forced);
                    }
                }
            }
        }
    }

    @Test
    void aDedicatedServerNeverGetsAWindow() {
        assertFalse(gui(true, false, null, false, true),
                "packupdater.server-side=true must skip the window even on a machine with a display");
        assertFalse(gui(true, false, null, true, true), "and it must win over a forced fallback too");
        assertTrue(gui(true, false, null, false, false), "but a client instance is unaffected");
    }
}
