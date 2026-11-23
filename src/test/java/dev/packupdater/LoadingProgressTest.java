package dev.packupdater;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadingProgressTest {
    private static Class<?> manager() throws Exception {
        return Class.forName(LoaderAccess.loadingPackage() + ".progress.StartupNotificationManager");
    }

    private static List<?> bars() throws Exception {
        return (List<?>) manager().getMethod("getCurrentProgress").invoke(null);
    }

    private static Object value(Object bar, String method) throws Exception {
        return bar.getClass().getMethod(method).invoke(bar);
    }

    private static void absolute(Object bar, int current) throws Exception {
        bar.getClass().getMethod("setAbsolute", int.class).invoke(bar, current);
    }

    private static Object updater() throws Exception {
        // Original-namespace FML prepends in the opposite deque order.
        for (Object bar : bars()) {
            if ("PackUpdater".equals(value(bar, "name"))) {
                return bar;
            }
        }
        throw new AssertionError("Updater bar was not registered");
    }

    @AfterEach
    void cleanup() {
        LoadingProgress.close();
    }

    @Test
    void ownsAndRemovesItsBarWithoutChangingTheLoadersBar() throws Exception {
        Object loader = manager().getMethod("prependProgressBar", String.class, int.class).invoke(null, "Loader", 10);
        absolute(loader, 3);
        try {
            LoadingProgress.progress(2, 5);
            Object updater = updater();
            assertEquals("PackUpdater", value(updater, "name"));
            assertEquals(5, value(updater, "steps"));
            assertEquals(2, value(updater, "current"));
            LoadingProgress.progress(8, 20);
            assertFalse(bars().contains(updater));
            assertEquals(10, value(loader, "steps"));
            assertEquals(3, value(loader, "current"));
            LoadingProgress.close();
            assertEquals(loader, bars().get(0));
        } finally {
            loader.getClass().getMethod("complete").invoke(loader);
        }
    }

    @Test
    void supportsIndeterminateAndClampsOutOfBoundsProgress() throws Exception {
        LoadingProgress.progress(8, 0);
        assertEquals(0, value(updater(), "current"));
        LoadingProgress.progress(20, 10);
        assertEquals(10, value(updater(), "current"));
        LoadingProgress.progress(-1, 10);
        assertEquals(0, value(updater(), "current"));
    }
}
