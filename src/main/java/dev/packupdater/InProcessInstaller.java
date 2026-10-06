package dev.packupdater;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Installs a PackWiz pack without starting a second JVM.
 *
 * <p>Exists because some launchers run Minecraft inside their own process and the platform
 * forbids launching another. Android launchers such as PojavLauncher, Pojav Glow·Worm, Fold Craft
 * and Zalith do this: the game's {@code java.home} points at a runtime the app may execute
 * in-process but may not {@code exec}, so spawning {@code bin/java} fails with
 *
 * <pre>Exec failed, error: 13 (Permission denied)</pre>
 *
 * <p>The PackWiz installer's own entry point cannot be called directly for the same reason it
 * cannot be forked: it calls {@link System#exit(int)} on essentially every path, including
 * success ({@code GUIHandler} exits 0 when the player declines an update). In this JVM that
 * would take Minecraft down with it, and Java 21 refuses to allow {@code System.exit} to be
 * trapped because {@code System.setSecurityManager} throws {@link UnsupportedOperationException}.
 * So the download-and-verify loop is implemented here instead.
 *
 * <p>This is deliberately a smaller tool than the real installer. It supports the parts of the
 * format CreateVC packs actually use, and it honours the same rules for optional mods so the
 * installed set matches what the windowed path would have produced.
 */
public final class InProcessInstaller {

    private InProcessInstaller() {}

    /** What the caller should be told, so it can drive the loading screen. */
    public interface Progress {
        void applying(String message, int done, int total);
    }

    public record Result(int installed, int validated, int removed, int skipped) {}

    /**
     * Syncs {@code packUrl} into {@code gameDir}.
     *
     * @param side {@code client} or {@code server}, recorded in the manifest so a later run of
     *             the real installer does not try to reconcile the wrong side.
     */
    public static Result sync(Path gameDir, String packUrl, String side, HttpClient client, Progress progress)
            throws IOException {
        String packToml = OptionalModFilter.fetch(client, packUrl);
        String indexFile = OptionalModFilter.readIndexFile(packToml, "index.toml");
        URI packUri = URI.create(packUrl);
        String indexUrl = packUri.resolve(indexFile).toString();
        String indexToml = OptionalModFilter.fetch(client, indexUrl);
        URI indexUri = URI.create(indexUrl);

        List<OptionalModFilter.Block> blocks = OptionalModFilter.parseIndex(indexToml);
        String indexHash = sha256Hex(indexToml.getBytes(StandardCharsets.UTF_8));

        Map<String, String> manifestFiles = new LinkedHashMap<>();
        int installed = 0;
        int validated = 0;
        int skipped = 0;
        int total = blocks.size();
        int done = 0;

        for (OptionalModFilter.Block block : blocks) {
            String indexedPath = block.file();
            if (indexedPath == null || indexedPath.isBlank()) {
                continue;
            }
            done++;
            if (progress != null) {
                progress.applying(indexedPath, done, total);
            }

            Entry entry = resolve(client, indexUri, gameDir, block);
            if (entry == null) {
                skipped++;
                continue;
            }
            if (entry.excluded) {
                // Not in the pack as far as the installer will ever see it, so nothing is
                // recorded and a later run will not try to restore it.
                skipped++;
                continue;
            }

            Path target = entry.target;
            if (hashMatches(target, entry.expectedHash, entry.expectedFormat)) {
                validated++;
            } else {
                byte[] body = download(client, entry.downloadUrl);
                verify(body, entry.expectedHash, entry.expectedFormat, entry.downloadUrl);
                writeAtomically(target, body);
                installed++;
            }
            manifestFiles.put(
                    target.toString().substring(gameDir.toString().length() + 1).replace('\\', '/'),
                    entry.expectedHash);
        }

        int removed = removeStale(gameDir, manifestFiles);
        writeManifest(gameDir, packUrl, indexHash, manifestFiles, side);
        return new Result(installed, validated, removed, skipped);
    }

    private record Entry(
            Path target, String downloadUrl, String expectedHash, String expectedFormat, boolean excluded) {}

    /**
     * Turns one index entry into a concrete download plus destination.
     *
     * <p>A {@code metafile = true} entry points at a {@code .pw.toml} rather than the artifact.
     * The artifact's real name comes from that file's {@code filename}, which is relative to the
     * metadata file's own directory.
     */
    private static Entry resolve(HttpClient client, URI indexUri, Path gameDir, OptionalModFilter.Block block)
            throws IOException {
        String indexedPath = block.file();
        Path indexedRelative = safe(gameDir, indexedPath);
        if (indexedRelative == null) {
            return null;
        }

        if (!block.isMetafile()) {
            String expected = OptionalModFilter.value(block.body(), "hash");
            String format = OptionalModFilter.value(block.body(), "hash-format");
            return new Entry(
                    indexedRelative,
                    indexUri.resolve(indexedPath).toString(),
                    expected,
                    format == null ? "sha256" : format,
                    false);
        }

        String meta;
        try {
            meta = OptionalModFilter.fetch(client, indexUri.resolve(indexedPath).toString());
        } catch (IOException e) {
            PackUpdater.LOGGER.warn("[PackUpdater] Skipping unreadable metadata {}: {}", indexedPath, e.toString());
            return null;
        }
        if (OptionalModFilter.isOptedOut(meta)) {
            return new Entry(indexedRelative, null, null, null, true);
        }

        String filename = OptionalModFilter.value(meta, "filename");
        if (filename == null || filename.isBlank()) {
            return null;
        }
        // filename is relative to the metadata file's own directory, per the PackWiz spec.
        Path metaDir = indexedRelative.getParent();
        Path target = safe(gameDir, (metaDir == null ? "" : gameDir.relativize(metaDir) + "/") + filename);
        if (target == null) {
            return null;
        }

        String url = OptionalModFilter.value(OptionalModFilter.section(meta, "download"), "url");
        if (url == null || url.isBlank()) {
            // Metadata without a download URL, such as a local file shipped in the pack, is
            // already present by definition.
            return new Entry(target, null, null, null, true);
        }
        String hash = OptionalModFilter.value(OptionalModFilter.section(meta, "download"), "hash");
        String format = OptionalModFilter.value(OptionalModFilter.section(meta, "download"), "hash-format");
        return new Entry(target, url, hash, format == null ? "sha256" : format, false);
    }

    /** Resolves a pack-relative path, refusing anything that escapes the game directory. */
    private static Path safe(Path gameDir, String relative) {
        try {
            Path resolved = gameDir.resolve(relative).normalize();
            return resolved.startsWith(gameDir) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] download(HttpClient client, String url) throws IOException {
        return OptionalModFilter.fetchBytes(client, url);
    }

    private static void verify(byte[] body, String expected, String format, String url) throws IOException {
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = hex(digestOf(format, body));
        if (!actual.equalsIgnoreCase(expected.replaceAll("[^0-9a-fA-F]", ""))) {
            throw new IOException("Hash mismatch for " + url + ": expected " + expected + " but got " + actual);
        }
    }

    private static boolean hashMatches(Path target, String expected, String format) {
        if (expected == null || expected.isBlank() || !Files.isRegularFile(target)) {
            return false;
        }
        try {
            byte[] body = Files.readAllBytes(target);
            return hex(digestOf(format, body)).equalsIgnoreCase(expected.replaceAll("[^0-9a-fA-F]", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] digestOf(String format, byte[] body) throws IOException {
        String algorithm = switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "sha1" -> "SHA-1";
            case "md5" -> "MD5";
            case "sha512" -> "SHA-512";
            default -> "SHA-256";
        };
        try {
            return MessageDigest.getInstance(algorithm).digest(body);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm unavailable: " + algorithm, e);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        return hex(digestOf("sha256", data));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    /**
     * Writes via a sibling temp file then moves it into place, so an interrupted download can
     * never leave a truncated jar where the game expects a complete one.
     */
    private static void writeAtomically(Path target, byte[] body) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".packupdater-part");
        Files.write(temp, body);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Removes files this pack previously installed that the current index no longer lists.
     *
     * <p>Only paths recorded in our own manifest are ever considered, so a mod the player added
     * by hand, or a config file belonging to another mod, is never touched.
     */
    private static int removeStale(Path gameDir, Map<String, String> current) {
        Path manifest = gameDir.resolve("packupdater-manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return 0;
        }
        int removed = 0;
        for (String previously : readManifestPaths(manifest)) {
            if (current.containsKey(previously)) {
                continue;
            }
            Path stale = safe(gameDir, previously);
            if (stale == null || !Files.exists(stale)) {
                continue;
            }
            try {
                Files.delete(stale);
                removed++;
            } catch (IOException e) {
                PackUpdater.LOGGER.warn("[PackUpdater] Could not remove stale file {}: {}", previously, e.toString());
            }
        }
        return removed;
    }

    private static List<String> readManifestPaths(Path manifest) {
        List<String> paths = new ArrayList<>();
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            int i = text.indexOf("\"files\"");
            if (i < 0) {
                return paths;
            }
            int open = text.indexOf('[', i);
            int close = text.indexOf(']', open);
            if (open < 0 || close < 0) {
                return paths;
            }
            String inner = text.substring(open + 1, close);
            int cursor = 0;
            while (true) {
                int quote = inner.indexOf('"', cursor);
                if (quote < 0) {
                    break;
                }
                int end = inner.indexOf('"', quote + 1);
                if (end < 0) {
                    break;
                }
                paths.add(inner.substring(quote + 1, end));
                cursor = end + 1;
            }
        } catch (Exception e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Could not read previous manifest: {}", e.toString());
        }
        return paths;
    }

    /**
     * Records what was installed.
     *
     * <p>This is deliberately a separate file from the real installer's {@code packwiz.json}.
     * Writing that one from here would make the two tools disagree about what is installed, and
     * the installer would then try to undo our work on its next run.
     */
    private static void writeManifest(
            Path gameDir, String packUrl, String indexHash, Map<String, String> files, String side) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"packUrl\": \"").append(escape(packUrl)).append("\",\n");
        json.append("  \"side\": \"").append(escape(side)).append("\",\n");
        json.append("  \"indexHash\": \"").append(escape(indexHash)).append("\",\n");
        json.append("  \"files\": [");
        int n = 0;
        for (String file : files.keySet()) {
            json.append(n++ == 0 ? "\n    " : ",\n    ");
            json.append("\"").append(escape(file)).append("\"");
        }
        json.append(n == 0 ? "" : "\n  ");
        json.append("]\n}\n");

        try {
            Files.writeString(gameDir.resolve("packupdater-manifest.json"), json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PackUpdater.LOGGER.warn("[PackUpdater] Could not write manifest: {}", e.toString());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}