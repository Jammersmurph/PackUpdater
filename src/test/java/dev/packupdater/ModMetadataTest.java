package dev.packupdater;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModMetadataTest {
    private static Config metadata(String name) throws Exception {
        try (var in = ModMetadataTest.class.getResourceAsStream("/META-INF/" + name)) {
            assertNotNull(in, name);
            return new TomlParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Config dependency(Config metadata, String id) {
        List<Config> dependencies = metadata.get("dependencies.packupdater");
        return dependencies.stream().filter(c -> id.equals(c.get("modId"))).findFirst().orElseThrow();
    }

    private static boolean accepts(String range, String version) throws Exception {
        return VersionRange.createFromVersionSpec(range).containsVersion(new DefaultArtifactVersion(version));
    }

    @Test
    void bothDescriptorsAllowEndpointsAndIntermediateMinecraftReleases() throws Exception {
        for (String name : List.of("mods.toml", "neoforge.mods.toml")) {
            String range = dependency(metadata(name), "minecraft").get("versionRange");
            for (String version : List.of("1.20.1", "1.20.2", "1.20.4", "1.20.6",
                    "1.21", "1.21.1", "1.21.5", "1.21.11", "26.1", "26.1.2", "26.2", "26.3")) {
                assertTrue(accepts(range, version), name + ": " + version);
            }
            assertFalse(accepts(range, "1.20"));
            assertFalse(accepts(range, "26.4"));
        }
    }

    @Test
    void languageRangesCoverBothGenerationsWithoutAllowingFutureMajors() throws Exception {
        String earlier = metadata("mods.toml").get("loaderVersion");
        for (String version : List.of("1.0.0", "2.0.21", "3.0.45", "47.1.4", "47.2.2")) {
            assertTrue(accepts(earlier, version), version);
        }
        String newer = metadata("neoforge.mods.toml").get("loaderVersion");
        for (String version : List.of("3.0.45", "4.0.23", "5.0.9", "9.0.19", "10.0.37", "12.0.16")) {
            assertTrue(accepts(newer, version), version);
        }
        assertFalse(accepts(newer, "13.0.0"));
        assertFalse(accepts(earlier, "48.0.0"));
    }

    @Test
    void earlierMetadataValidatesWhicheverLoaderIdIsPresent() throws Exception {
        Config old = metadata("mods.toml");
        Config forge = dependency(old, "forge");
        Config neo = dependency(old, "neoforge");
        assertEquals(false, forge.get("mandatory"));
        assertEquals(false, neo.get("mandatory"));
        assertTrue(accepts(forge.get("versionRange"), "47.1.4"));
        for (String version : List.of("20.2.3-beta", "20.4.251", "20.6.144", "21.0.167", "21.11.48", "26.3.0.115")) {
            assertTrue(accepts(neo.get("versionRange"), version), version);
            assertTrue(accepts(dependency(metadata("neoforge.mods.toml"), "neoforge").get("versionRange"), version));
        }
    }
}
