package dev.packupdater;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoaderIntegrationTest {
    @Test
    void applicableStartupServiceCanBeDiscoveredAndInstantiated() throws Exception {
        Class<?> service;
        String implementation;
        try {
            service = Class.forName("net.neoforged.neoforgespi.locating.IModFileCandidateLocator");
            implementation = "dev.packupdater.modern.ModernUpdateLocator";
        } catch (ClassNotFoundException originalNamespace) {
            service = Class.forName("cpw.mods.modlauncher.api.ITransformationService");
            implementation = "dev.packupdater.UpdatePlugin";
        }
        String expected = implementation;
        var provider = ServiceLoader.load(service).stream()
                .filter(p -> p.type().getName().equals(expected))
                .findFirst().orElseThrow();
        Object instance = provider.get();
        if (expected.endsWith("ModernUpdateLocator")) {
            assertEquals(Integer.MAX_VALUE, instance.getClass().getMethod("getPriority").invoke(instance));
        } else {
            assertEquals("packupdater", instance.getClass().getMethod("name").invoke(instance));
        }
    }

    @Test
    void loaderExposesOneOfTheSupportedStartupContracts() throws Exception {
        Class<?> loader = LoaderAccess.loaderClass();
        try {
            var current = loader.getMethod("getCurrent");
            assertTrue(Modifier.isStatic(current.getModifiers()));
            assertEquals(loader, current.getReturnType());
            assertEquals(Path.class, loader.getMethod("getGameDir").getReturnType());
            assertFalse(Modifier.isStatic(loader.getMethod("getDist").getModifiers()));
        } catch (NoSuchMethodException legacyLoader) {
            assertEquals(Path.class, loader.getMethod("getGamePath").getReturnType());
            assertTrue(Modifier.isStatic(loader.getMethod("getDist").getModifiers()));
        }
    }
}
