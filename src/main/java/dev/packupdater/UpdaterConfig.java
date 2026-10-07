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
    public static final String KEY_ENGINE = "packupdater.engine";
    public static final String KEY_SERVER_SIDE = "packupdater.server-side";
    public static final String KEY_FALLBACK = "packupdater.fallback";

    public static final String DEFAULT_INSTALLER_URL =
            "https://api.github.com/repos/packwiz/packwiz-installer/releases/latest";
    public static final String DEFAULT_INSTALLER_ASSET = "packwiz-installer.jar";

    private static final String CONFIG_RELATIVE_PATH = "config/packupdater.properties";


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

    /**
     * How the installer is launched. Forking is preferred because it isolates the installer's
     * Kotlin and OkHttp from the game's, and it is the only path where the window can be shown
     * outside this process.
     */
    public enum Engine {
        /** Fork a child JVM, falling back to running in-process if that is not permitted. */
        AUTO,
        /** Always fork. Fails on platforms that forbid it. */
        FORK,
        /** Never fork. The only workable option on Android launchers. */
        DIRECT
    }

    public boolean skip;
    public boolean gui;
    public boolean serverSide;
    public Engine engine = Engine.AUTO;
    public Compat compat = Compat.AUTO;
    public Fallback fallback = Fallback.AUTO;
    public String packUrl = "";
    public String devUrl = "";
    public String installerUrl = "";
    public String installerAsset = DEFAULT_INSTALLER_ASSET;
    public String installerToken = "";

    /** Packaged so the file attached to a release is byte-identical to the one written on first run. */
    private static final String DEFAULT_TEMPLATE_RESOURCE = "/default-packupdater.properties";

    private UpdaterConfig() {}

    static String defaultTemplate() {
        try (var in = UpdaterConfig.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            PackUpdater.LOGGER.warn("[PackUpdater] Could not read packaged default config: {}", e.toString());
        }
        return "# PackUpdater configuration. See "
                + "https://github.com/Jammersmurph/PackUpdater/wiki/Configuration\n";
    }

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
        config.serverSide = config.getBoolean(KEY_SERVER_SIDE, false);
        config.engine = config.parseEnum(KEY_ENGINE, Engine.class, Engine.AUTO);
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
                writer.write(defaultTemplate());
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