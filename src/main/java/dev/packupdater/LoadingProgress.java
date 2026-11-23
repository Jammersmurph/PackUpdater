package dev.packupdater;

import java.lang.reflect.Method;

/** Owns its progress bar rather than changing another loader's private fields. */
final class LoadingProgress {
    private static Object meter;
    private static int steps = -1;
    private static Method prepend;
    private static Method setAbsolute;
    private static Method complete;
    private static Method updateProgress;

    private LoadingProgress() {}

    private static void initialize() throws ReflectiveOperationException {
        if (prepend != null) {
            return;
        }
        String base = LoaderAccess.loadingPackage();
        Class<?> manager = Class.forName(base + ".progress.StartupNotificationManager");
        Class<?> progress = Class.forName(base + ".progress.ProgressMeter");
        // Reflection is limited to public APIs, not private fields. This keeps
        // the Java 17 core independent of both loader package namespaces.
        setAbsolute = progress.getMethod("setAbsolute", int.class);
        complete = progress.getMethod("complete");
        updateProgress = Class.forName(base + ".ImmediateWindowHandler").getMethod("updateProgress", String.class);
        prepend = manager.getMethod("prependProgressBar", String.class, int.class);
    }

    static void message(String message) {
        try {
            // Some launch configurations have no early window provider.
            initialize();
            updateProgress.invoke(null, message);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // Loading-screen integration is cosmetic; never interrupt the installer.
            PackUpdater.LOGGER.debug("[PackUpdater] Loading-screen message unavailable: {}", e.toString());
        }
    }

    static void progress(int current, int total) {
        if (total < 0) {
            return;
        }
        try {
            initialize();
            if (meter == null || steps != total) {
                close();
                meter = prepend.invoke(null, "PackUpdater", total);
                steps = total;
            }
            setAbsolute.invoke(meter, total == 0 ? 0 : Math.max(0, Math.min(current, total)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Loading-screen progress unavailable: {}", e.toString());
        }
    }

    static void close() {
        if (meter != null) {
            try {
                complete.invoke(meter);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                PackUpdater.LOGGER.debug("[PackUpdater] Could not close progress bar: {}", e.toString());
            } finally {
                meter = null;
                steps = -1;
            }
        }
    }
}
