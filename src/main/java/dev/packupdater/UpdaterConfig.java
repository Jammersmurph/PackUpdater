package dev.packupdater;

import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration for the auto-updater.
 *
 * <p>Values are resolved in this order, first hit wins:
 * <ol>
 *   <li>JVM system property ({@code -Dpackupdater.<key>=...})</li>
 *   <li>{@code <gameDir>/config/packupdater.properties}</li>
 *   <li>Built-in default</li>
 * </ol>
 *
 * <p>This runs from a ModLauncher transformation service, long before NeoForge's own
 * config layer is available, so it deliberately uses plain {@link Properties} rather
 * than a NeoForge config type.
 */
public final class UpdaterConfig {

    public static final String KEY_SKIP = "packupdater.skip";
    public static final String KEY_URL = "packupdater.url";
    public static final String KEY_DEV = "packupdater.dev";
    public static final String KEY_DEV_URL = "packupdater.dev-url";
    public static final String KEY_INSTALLER_URL = "packupdater.installer-url";
    public static final String KEY_INSTALLER_ASSET = "packupdater.installer-asset";
    public static final String KEY_INSTALLER_TOKEN = "packupdater.installer-token";
    public static final String KEY_GUI = "packupdater.gui";

    public static final String DEFAULT_INSTALLER_URL =
            "https://api.github.com/repos/packwiz/packwiz-installer/releases/latest";
    public static final String DEFAULT_INSTALLER_ASSET = "packwiz-installer.jar";

    private static final String CONFIG_RELATIVE_PATH = "config/packupdater.properties";

    private static final String DEFAULT_TEMPLATE = """
            # PackUpdater configuration.
            # Every key here can also be set as a JVM argument, e.g. -Dpackupdater.url=<url>.
            # JVM arguments take precedence over this file.

            # URL of the pack.toml to sync against. Required - the updater does nothing
            # without it. Example:
            #   packupdater.url=https://example.com/pack/pack.toml
            packupdater.url=

            # Optional fallback used only when packupdater.url is blank and
            # packupdater.dev is true.
            # packupdater.dev-url=

            # Use packupdater.dev-url instead of packupdater.url.
            packupdater.dev=false

            # GitHub "latest release" API URL for the PackWiz installer that PackUpdater
            # bootstraps and self-updates. Defaults to the upstream PackWiz installer, so you
            # normally do not need to touch this. Point it elsewhere only if you maintain
            # your own build of the installer. Leave blank to disable the self-update.
            packupdater.installer-url=https://api.github.com/repos/packwiz/packwiz-installer/releases/latest

            # Release asset name to download from that release.
            packupdater.installer-asset=packwiz-installer.jar

            # Optional GitHub token, only needed for private repositories.
            # packupdater.installer-token=

            # Show the PackWiz installer's window on launch. This is what exposes the
            # installer's optional mods list, and it blocks startup until you close it.
            # Set to false for a silent, unattended update; it is forced off automatically
            # when there is no display, e.g. on a dedicated server.
            packupdater.gui=true

            # Skip the updater entirely.
            packupdater.skip=false
            """;

    private final Properties file = new Properties();

    public boolean skip;
    public boolean gui;
    public String packUrl = "";
    public String devUrl = "";
    public String installerUrl = "";
    public String installerAsset = DEFAULT_INSTALLER_ASSET;
    public String installerToken = "";

    private UpdaterConfig() {}

    /** Resolves the game directory, falling back to the JVM working directory. */
    public static Path gameDirectory() {
        try {
            Path dir = FMLLoader.getGamePath();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
            // Transformation services initialise before FML has finished wiring up its paths.
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    public static UpdaterConfig load(Path gameDir) {
        UpdaterConfig config = new UpdaterConfig();
        Path path = gameDir.resolve(CONFIG_RELATIVE_PATH);

        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config.file.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                PackUpdater.LOGGER.warn("[PackUpdater] Could not read {}: {}", path, e.toString());
            }
        } else {
            config.writeDefault(path);
        }

        config.skip = config.getBoolean(KEY_SKIP, false);
        config.gui = config.getBoolean(KEY_GUI, true);
        config.packUrl = config.getString(KEY_URL);
        config.devUrl = config.getString(KEY_DEV_URL);
        config.installerUrl = config.getString(KEY_INSTALLER_URL, DEFAULT_INSTALLER_URL);
        config.installerAsset = config.getString(KEY_INSTALLER_ASSET, DEFAULT_INSTALLER_ASSET);
        config.installerToken = config.getString(KEY_INSTALLER_TOKEN);
        return config;
    }

    /**
     * Effective pack.toml URL. An explicit {@code packupdater.url} always wins; the dev
     * branch is only consulted when it is unset.
     */
    public String effectivePackUrl() {
        if (!packUrl.isEmpty()) {
            return packUrl;
        }
        return getBoolean(KEY_DEV, false) ? devUrl : "";
    }

    private void writeDefault(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(DEFAULT_TEMPLATE);
            }
            PackUpdater.LOGGER.info("[PackUpdater] Wrote default config to {}", path);
        } catch (IOException e) {
            PackUpdater.LOGGER.warn("[PackUpdater] Could not write default config to {}: {}", path, e.toString());
        }
    }

    private String getString(String key) {
        return getString(key, "");
    }

    private String getString(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null) {
            value = file.getProperty(key);
        }
        if (value == null) {
            return fallback;
        }
        value = value.trim();
        return value.isEmpty() ? fallback : value;
    }

    private boolean getBoolean(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null) {
            value = file.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}