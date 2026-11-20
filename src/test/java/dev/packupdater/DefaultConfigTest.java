package dev.packupdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.io.StringReader;

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
    void omittedInstallerUrlUsesNewDefault(@TempDir Path gameDir) throws Exception {
        Path config = gameDir.resolve("config/packupdater.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "packupdater.url=https://example.com/pack.toml\n");
        assertEquals(UpdaterConfig.DEFAULT_INSTALLER_URL, UpdaterConfig.load(gameDir).installerUrl);
    }

    @Test
    void explicitExistingInstallerUrlIsPreserved(@TempDir Path gameDir) throws Exception {
        Path config = gameDir.resolve("config/packupdater.properties");
        Files.createDirectories(config.getParent());
        String upstream = "https://api.github.com/repos/packwiz/packwiz-installer/releases/latest";
        Files.writeString(config, "packupdater.installer-url=" + upstream + "\n");
        assertEquals(upstream, UpdaterConfig.load(gameDir).installerUrl);
    }

    @Test
    void blankInstallerUrlFallsBackToDefault(@TempDir Path gameDir) throws Exception {
        Path config = gameDir.resolve("config/packupdater.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "packupdater.installer-url=   \n");
        assertEquals(UpdaterConfig.DEFAULT_INSTALLER_URL, UpdaterConfig.load(gameDir).installerUrl);
    }

    @Test
    void shippedAndBuiltInDefaultsUseTheThemedInstallerFork() throws Exception {
        String expected = "https://api.github.com/repos/Jammersmurph/packwiz-installer/releases/latest";
        assertEquals(expected, UpdaterConfig.DEFAULT_INSTALLER_URL);
        Properties template = new Properties();
        template.load(new StringReader(UpdaterConfig.defaultTemplate()));
        assertEquals(expected, template.getProperty(UpdaterConfig.KEY_INSTALLER_URL));
        assertEquals("packwiz-installer.jar", template.getProperty(UpdaterConfig.KEY_INSTALLER_ASSET));
    }

    @Test
    void optionalGroupsExampleIsPackagedForReleaseAuthors() throws Exception {
        try (var in = UpdaterConfig.class.getResourceAsStream("/examples/optional-groups.toml")) {
            assertTrue(in != null, "the release example must also be available in the built jar");
            String example = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(example.contains("version = 1"));
            assertTrue(example.contains("[[groups]]"));
            assertTrue(example.contains("name = \"VR Support\""));
            assertTrue(example.contains("mods/vivecraft.pw.toml"));
        }
    }

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
