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
    public static final String KEY_COMPAT = "packupdater.compat";
    public static final String KEY_FALLBACK = "packupdater.fallback";

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

            # URL of the pack.toml to sync against when packupdater.dev is true. When dev
            # is true and this is set, it takes precedence over packupdater.url, so a
            # testing branch only needs to flip the flag.
            # packupdater.dev-url=

            # Sync packupdater.dev-url instead of packupdater.url.
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

            # Compatibility layer. "auto" checks whether a forked JVM could open the
            # installer's window and falls back to a silent update when it could not, which is
            # what makes this usable on Android launchers that supply AWT from a jar. Set to
            # "off" for exactly the previous behaviour.
            packupdater.compat=auto

            # When to use that silent fallback. "auto" only after a confirmed failure,
            # "never" disables it, and "always" uses it unconditionally so you can exercise
            # the path on a desktop.
            packupdater.fallback=auto

            # Skip the updater entirely.
            packupdater.skip=false
            """;

    private final Properties file = new Properties();

    /** Whether the compatibility layer may downgrade the GUI to the filtered path. */
    public enum Compat {
        /** Detect an unusable GUI and fall back. Default. */
        AUTO,
        /** No detection and no fallback. Behaves exactly as before this feature existed. */
        OFF
    }

    /** When the filtered, non-GUI path runs. */
    public enum Fallback {
        /** Only after a confirmed GUI failure, or when detection proves the GUI cannot open. */
        AUTO,
        /** Never fall back. */
        NEVER,
        /** Always skip the GUI and use the filtered path. For testing the fallback anywhere. */
        ALWAYS
    }

    public boolean skip;
    public boolean gui;
    public Compat compat = Compat.AUTO;
    public Fallback fallback = Fallback.AUTO;
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
        config.compat = config.parseEnum(KEY_COMPAT, Compat.class, Compat.AUTO);
        config.fallback = config.parseEnum(KEY_FALLBACK, Fallback.class, Fallback.AUTO);
        config.packUrl = config.getString(KEY_URL);
        config.devUrl = config.getString(KEY_DEV_URL);
        config.installerUrl = config.getString(KEY_INSTALLER_URL, DEFAULT_INSTALLER_URL);
        config.installerAsset = config.getString(KEY_INSTALLER_ASSET, DEFAULT_INSTALLER_ASSET);
        config.installerToken = config.getString(KEY_INSTALLER_TOKEN);
        return config;
    }

    /**
     * Effective pack.toml URL. When {@code packupdater.dev} is true and a dev URL is set,
     * the dev URL wins, so testing a branch is a one-flag change even if the pack ships a
     * production {@code packupdater.url}. Otherwise an explicit {@code packupdater.url} is
     * used, and the dev URL is only a fallback for a file that sets neither.
     */
    public String effectivePackUrl() {
        boolean dev = getBoolean(KEY_DEV, false);
        if (dev && !devUrl.isEmpty()) {
            return devUrl;
        }
        if (!packUrl.isEmpty()) {
            return packUrl;
        }
        return dev ? devUrl : "";
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

    private <E extends Enum<E>> E parseEnum(String key, Class<E> type, E fallback) {
        String value = System.getProperty(key);
        if (value == null) {
            value = file.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            PackUpdater.LOGGER.warn(
                    "[PackUpdater] Ignoring unrecognised value for {}: {} (expected one of {})",
                    key, value.trim(), java.util.Arrays.toString(type.getEnumConstants()));
            return fallback;
        }
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