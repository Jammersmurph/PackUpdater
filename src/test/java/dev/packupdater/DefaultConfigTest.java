package dev.packupdater;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default config is a shipped artifact: it is attached to every release and is also what the
 * mod writes on first run. If those two ever diverged, a pack author following the release page
 * would get a file the mod itself does not produce.
 */
class DefaultConfigTest {

    @Test
    void defaultConfigIsPackagedAndReadable() throws Exception {
        try (var in = UpdaterConfig.class.getResourceAsStream("/default-packupdater.properties")) {
            assertTrue(in != null, "the default config must be packaged in the jar");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(text.isBlank(), "the packaged default config must not be empty");
        }
    }

    @Test
    void whatIsWrittenOnFirstRunIsTheFileThatIsShipped() throws Exception {
        // Asserted against defaultTemplate() rather than load(), because load() logs on the
        // write path and PackUpdater.LOGGER needs com.mojang.logging, which only exists on the
        // game runtime and not on the unit test classpath. This is the invariant that matters:
        // the bytes written into a fresh instance are the bytes attached to a release.
        byte[] shipped;
        try (var in = UpdaterConfig.class.getResourceAsStream("/default-packupdater.properties")) {
            shipped = in.readAllBytes();
        }
        assertEquals(new String(shipped, StandardCharsets.UTF_8), UpdaterConfig.defaultTemplate(),
                "the file written on first run must match the file shipped with a release");
    }

    @Test
    void everyKeyIsPresentInTheDefaultConfig() throws Exception {
        String text;
        try (var in = UpdaterConfig.class.getResourceAsStream("/default-packupdater.properties")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Every key the code understands must appear, so the file is a complete reference rather
        // than a subset that silently omits something configurable.
        for (String key : new String[] {
            UpdaterConfig.KEY_URL,
            UpdaterConfig.KEY_DEV_URL,
            UpdaterConfig.KEY_DEV,
            UpdaterConfig.KEY_SKIP,
            UpdaterConfig.KEY_INSTALLER_URL,
            UpdaterConfig.KEY_INSTALLER_ASSET,
            UpdaterConfig.KEY_INSTALLER_TOKEN,
            UpdaterConfig.KEY_GUI,
            UpdaterConfig.KEY_COMPAT,
            UpdaterConfig.KEY_FALLBACK,
            UpdaterConfig.KEY_ENGINE,
            UpdaterConfig.KEY_SERVER_SIDE,
        }) {
            assertTrue(text.contains(key), "the default config must mention " + key);
        }
    }
}