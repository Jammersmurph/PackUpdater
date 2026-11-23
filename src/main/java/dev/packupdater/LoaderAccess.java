package dev.packupdater;

import java.nio.file.Path;

/** Public loader APIs across NeoForge's original and renamed package namespaces. */
final class LoaderAccess {
    private LoaderAccess() {}

    static Class<?> loaderClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.neoforged.fml.loading.FMLLoader");
        } catch (ClassNotFoundException originalNamespace) {
            return Class.forName("net.minecraftforge.fml.loading.FMLLoader");
        }
    }

    static String loadingPackage() throws ClassNotFoundException {
        return loaderClass().getPackageName();
    }

    static void startLegacy() {
        try {
            Class<?> loader = loaderClass();
            Path directory = (Path) loader.getMethod("getGamePath").invoke(null);
            Object dist = loader.getMethod("getDist").invoke(null);
            UpdaterStartup.run(directory, "DEDICATED_SERVER".equals(String.valueOf(dist)) ? "server" : "client");
        } catch (ReflectiveOperationException e) {
            // Only public, explicitly supported methods are used. Never guess a
            // directory and risk updating another instance if the contract changes.
            PackUpdater.LOGGER.error("[PackUpdater] Unsupported legacy loader API; skipping update.", e);
        }
    }
}
