package dev.packupdater;

import java.nio.file.Path;

/** Shared entry point for the legacy and modern early-startup integrations. */
public final class UpdaterStartup {
    private static Path gameDirectory;
    private static String side = "client";
    private static boolean started;

    private UpdaterStartup() {}

    public static synchronized boolean hasStarted() {
        return started;
    }

    public static synchronized void run(Path directory, String distribution) {
        if (started) {
            return;
        }
        started = true;
        gameDirectory = directory;
        side = distribution;
        UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.gameDirectory());

        if (config.skip) {
            PackUpdater.LOGGER.info("[PackUpdater] Skipping update check because '{}' is set.", UpdaterConfig.KEY_SKIP);
            return;
        }
        String packUrl = config.effectivePackUrl();
        if (packUrl.isEmpty()) {
            PackUpdater.LOGGER.warn("[PackUpdater] No pack URL configured, skipping update.");
            return;
        }
        PackUpdater.LOGGER.info("[PackUpdater] Early update starting: directory={}, side={}", directory, distribution);
        try {
            UpdaterBootstrap.runUpdate(config, packUrl);
        } catch (Exception e) {
            // Updating must not prevent the game from starting.
            PackUpdater.LOGGER.error("[PackUpdater] Update failed: {}", e.toString(), e);
        } finally {
            LoadingProgress.close();
        }
    }

    static Path gameDirectory() {
        return gameDirectory;
    }

    static String side() {
        return side;
    }
}
