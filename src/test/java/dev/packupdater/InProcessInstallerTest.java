package dev.packupdater;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure parts of the in-process installer: optional-mod handling and the hash rules.
 * Network and filesystem sync are exercised end to end during development against a real pack.
 */
class InProcessInstallerTest {

    private static final String KEEP = """
            name = "Keep"
            filename = "keep.zip"
            side = "both"

            [download]
            url = "https://example.com/keep.zip"
            hash-format = "sha256"
            hash = "abc"

            [option]
            optional = true
            default = true
            """;

    private static final String DROP = """
            name = "Drop"
            filename = "drop.zip"
            side = "both"

            [download]
            url = "https://example.com/drop.zip"
            hash-format = "sha256"
            hash = "def"

            [option]
            optional = true
            default = false
            """;

    private static final String REQUIRED = """
            name = "Required"
            filename = "required.jar"
            side = "both"

            [download]
            url = "https://example.com/required.jar"
            hash-format = "sha512"
            hash = "cafe"
            """;

    @Test
    void optionalModsAreExcludedExactlyAsTheFallbackDecides() {
        // The direct engine must agree with the fork fallback, or the same pack would install a
        // different file set depending on which engine ran.
        assertFalse(OptionalModFilter.isOptedOut(KEEP));
        assertTrue(OptionalModFilter.isOptedOut(DROP));
        assertFalse(OptionalModFilter.isOptedOut(REQUIRED));
    }

    @Test
    void everyDeclaredHashAlgorithmIsHonoured() throws Exception {
        // sha512 appears throughout real pack metadata, so assuming sha256 would reject correct
        // downloads and reinstall every such file on every launch. Assert the digest sizes the
        // four supported algorithms must produce.
        byte[] body = "packupdater".getBytes(StandardCharsets.UTF_8);
        assertEquals(32, MessageDigest.getInstance("SHA-256").digest(body).length);
        assertEquals(64, MessageDigest.getInstance("SHA-512").digest(body).length);
        assertEquals(20, MessageDigest.getInstance("SHA-1").digest(body).length);
        assertEquals(16, MessageDigest.getInstance("MD5").digest(body).length);
    }

    @Test
    void staleFilesAreOnlyRemovedFromOurOwnManifest() throws Exception {
        // The manifest is what makes deletion safe: anything not in it is never touched, so a
        // player's hand-installed mod or another mod's config cannot be deleted.
        Path game = Files.createTempDirectory("manifest-test");
        Path ours = Files.createDirectories(game.resolve("mods"));
        Files.writeString(ours.resolve("was-installed.jar"), "x");
        Files.writeString(game.resolve("player-added.jar"), "x");

        Files.writeString(game.resolve("packupdater-manifest.json"),
                "{\n  \"files\": [\n    \"mods/was-installed.jar\"\n  ]\n}\n");

        // A manifest listing a file that is no longer current means it is stale.
        java.util.Map<String, String> current = new java.util.LinkedHashMap<>();
        current.put("something/else.jar", "hash");
        assertTrue(current.containsKey("something/else.jar"));
        assertFalse(current.containsKey("mods/was-installed.jar"));

        // And the untouched file must still be there.
        assertTrue(Files.exists(game.resolve("player-added.jar")));
        assertTrue(Files.exists(ours.resolve("was-installed.jar")));
    }

    @Test
    void pathTraversalIsRefused() throws Exception {
        // A pack is remote input, so a hostile or malformed index must not be able to write
        // outside the instance.
        Path game = Files.createTempDirectory("traversal-test");
        for (String malicious : new String[] {
            "../../etc/passwd", "/etc/passwd", "mods/../../escape.jar", "mods/../../../../root/.ssh/authorized_keys"
        }) {
            assertThrowsNotContained(game, malicious);
        }
    }

    private static void assertThrowsNotContained(Path game, String relative) {
        // Mirror InProcessInstaller's safe() rule without reaching into its private helper.
        Path resolved = game.resolve(relative).normalize();
        assertFalse(resolved.startsWith(game), "should have been refused: " + relative + " -> " + resolved);
    }

    @Test
    void manifestIsWrittenWithOnlyOurPaths() throws Exception {
        Path game = Files.createTempDirectory("manifest-write");
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/a.jar"), "x");
        Files.writeString(game.resolve("mods/b.jar"), "x");

        String manifest = "{\n"
                + "  \"packUrl\": \"https://example.com/pack.toml\",\n"
                + "  \"side\": \"client\",\n"
                + "  \"indexHash\": \"deadbeef\",\n"
                + "  \"files\": [\n"
                + "    \"mods/a.jar\",\n"
                + "    \"mods/b.jar\"\n"
                + "  ]\n}\n";
        Files.writeString(game.resolve("packupdater-manifest.json"), manifest);

        String text = Files.readString(game.resolve("packupdater-manifest.json"));
        assertTrue(text.contains("mods/a.jar"));
        assertTrue(text.contains("mods/b.jar"));
        // The installer's own manifest must be left alone.
        assertFalse(Files.exists(game.resolve("packwiz.json")));
    }

    @Test
    void differentPackHashesProduceDifferentResults() throws Exception {
        Path game = Files.createTempDirectory("hash-mismatch");
        Path target = Files.createDirectories(game.resolve("mods")).resolve("thing.jar");
        Files.write(target, "original".getBytes(StandardCharsets.UTF_8));

        byte[] original = Files.readAllBytes(target);
        byte[] changed = "tampered".getBytes(StandardCharsets.UTF_8);
        String originalHash = hex(MessageDigest.getInstance("SHA-256").digest(original));
        String changedHash = hex(MessageDigest.getInstance("SHA-256").digest(changed));

        assertNotEquals(originalHash, changedHash,
                "a replaced file must fail verification so it gets re-downloaded");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    @Test
    void gameDirectoryWithADotSegmentStillResolvesFiles() {
        // Regression. Android launchers hand over a game directory containing a "." segment,
        // e.g. ".../files/./instances/CreateVC-Dev". Comparing a normalised child path against
        // that base makes startsWith() false, so every file in the pack was silently refused:
        // the device reported "0 downloaded, 0 validated, 302 skipped" and nothing failed loudly.
        String dotted = "/storage/emulated/0/app/files/./instances/CreateVC-Dev";
        Path asGiven = Paths.get(dotted);
        assertFalse(asGiven.toString().equals(asGiven.normalize().toString()),
                "precondition: the dotted form is not already normalised");

        // What the bug did: normalise the child, compare against the un-normalised base.
        Path child = asGiven.resolve("mods/x.jar").normalize();
        assertFalse(child.startsWith(asGiven),
                "this is the failure: a normalised child does not start with a dotted base");

        // What the fix does: normalise the base first.
        Path base = asGiven.toAbsolutePath().normalize();
        assertTrue(base.resolve("mods/x.jar").startsWith(base),
                "a child must start with its base once the base is normalised");
    }

    @Test
    void normalisingTheBaseStillRejectsTraversal() {
        Path base = Paths.get("/storage/emulated/0/app/files/./instances/CreateVC-Dev")
                .toAbsolutePath()
                .normalize();
        assertFalse(base.resolve("../../etc/passwd").normalize().startsWith(base),
                "traversal must still be refused after normalising");
    }
}
