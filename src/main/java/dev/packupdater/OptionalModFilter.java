package dev.packupdater;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a copy of a pack with opt-in optional mods removed.
 *
 * <p>The PackWiz installer has no non-interactive way to choose optional mods. Its CLI handler
 * force-enables every one of them:
 *
 * <pre>{@code iOptionDetails.setOptionValue(true);}</pre>
 *
 * so a headless run installs the union of all optional mods. That is only correct for packs
 * whose optional mods are purely additive, so when this fallback runs we rewrite the index to
 * omit optional mods that are explicitly {@code default = false}. Optional mods that are enabled
 * by default are kept, and since the installer then accepts all remaining optional mods, the
 * installed set matches what a player would have chosen in the window.
 *
 * <p>The index is filtered textually rather than parsed and re-serialised. Every retained block
 * is copied through byte for byte, so comments, key order and formatting survive and there is
 * no way for a round trip to alter a pack we do not fully model.
 */
public final class OptionalModFilter {

    private OptionalModFilter() {}

    /** Outcome of building the filtered pack. */
    public record Result(Path packFile, int dropped, int retainedOptional) {}

    public static Result build(Path tempDir, String packUrl, HttpClient client) throws IOException {
        String packToml = fetch(client, packUrl);
        String indexFile = readIndexFile(packToml, "index.toml");

        URI base = URI.create(packUrl);
        String indexUrl = base.resolve(indexFile).toString();
        String indexToml = fetch(client, indexUrl);
        URI indexBase = URI.create(indexUrl);

        List<Block> blocks = parseIndex(indexToml);
        int retained = 0;
        StringBuilder filtered = new StringBuilder();
        Path indexOut = tempDir.resolve("index.toml");
        filtered.append(indexToml, 0, blocks.isEmpty() ? indexToml.length() : blocks.get(0).start());

        for (Block block : blocks) {
            if (block.isMetafile() && isOptedOut(client, indexBase, block)) {
                continue;
            }
            if (block.isMetafile()) {
                retained++;
            }
            // Every retained entry has to exist locally: the installer resolves an index entry
            // relative to the pack file, and ours is a file: URL in a temp dir, so it will not
            // fetch the originals over the network.
            mirror(client, indexBase, tempDir, block.file());
            filtered.append(indexToml, block.start(), block.end());
        }

        int dropped = countMetafiles(blocks) - retained;

        Files.writeString(indexOut, filtered.toString(), StandardCharsets.UTF_8);

        // name, author, version and pack-format are all required by the installer even though
        // it never reads them. They are carried over from the source pack where possible so
        // the synthesised pack is recognisable in logs.
        String packOut = "name = " + quote(stringOr(packToml, "name", "Pack (filtered)")) + "\n"
                + "author = " + quote(stringOr(packToml, "author", "unknown")) + "\n"
                + "version = " + quote(stringOr(packToml, "version", "0.0.0")) + "\n"
                + "pack-format = \"" + packFormatOf(packToml) + "\"\n"
                + "\n"
                + "[index]\n"
                + "file = \"index.toml\"\n"
                + "hash-format = \"sha256\"\n"
                + "hash = \"" + sha256Hex(Files.readAllBytes(indexOut)) + "\"\n";
        Path packFile = tempDir.resolve("pack.toml");
        Files.writeString(packFile, packOut, StandardCharsets.UTF_8);

        return new Result(packFile, dropped, retained);
    }

    /**
     * Copies a retained index entry into the filtered pack at the same relative path, so the
     * installer resolves it locally instead of over the network. Metadata files are written as
     * text, everything else verbatim.
     */
    private static void mirror(HttpClient client, URI indexBase, Path packDir, String file) {
        if (file == null || file.isBlank()) {
            return;
        }
        Path relative;
        try {
            relative = Path.of(file);
        } catch (RuntimeException e) {
            return;
        }
        if (relative.isAbsolute() || file.contains("..")) {
            return;
        }
        try {
            Path out = packDir.resolve(relative).normalize();
            if (!out.startsWith(packDir)) {
                return;
            }
            Files.createDirectories(out.getParent());
            byte[] body = fetchBytes(client, indexBase.resolve(file).toString());
            Files.write(out, body);
        } catch (IOException | RuntimeException e) {
            // The installer reports a clear error if something is genuinely missing, and a
            // single unreachable asset must not abort the whole fallback.
            PackUpdater.LOGGER.debug("[PackUpdater] Could not mirror {}: {}", file, e.toString());
        }
    }

    /** Fetches a resource as bytes, so jars and zips survive the trip unchanged. */
    static byte[] fetchBytes(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "PackUpdater")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private static boolean isOptedOut(HttpClient client, URI indexBase, Block block) {
        String url;
        try {
            url = indexBase.resolve(block.file()).toString();
        } catch (RuntimeException e) {
            // Unparseable path; leave the entry alone rather than silently dropping a mod.
            return false;
        }
        try {
            return isOptedOut(fetch(client, url));
        } catch (IOException | RuntimeException e) {
            // Cannot read the metadata, so we do not know the author's intent. Keep it and let
            // the installer decide; dropping files we did not understand would be worse.
            return false;
        }
    }

    /**
     * True only for an explicit {@code default = false}. An optional mod with no {@code default}
     * key is left in, because the PackWiz spec leaves that case undefined and installing is the
     * less surprising default.
     */
    static boolean isOptedOut(String metafileToml) {
        String optionBlock = section(metafileToml, "option");
        if (optionBlock == null) {
            return false;
        }
        if (!"true".equals(value(optionBlock, "optional"))) {
            return false;
        }
        return "false".equals(value(optionBlock, "default"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Reads a top level string key, ignoring any table sections. */
    private static String stringOr(String toml, String key, String fallback) {
        for (String raw : toml.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                if (line.startsWith("[")) {
                    break;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                String value = unquote(line.substring(eq + 1).trim());
                return value.isEmpty() ? fallback : value;
            }
        }
        return fallback;
    }

    /** Copies the source pack's format, which the installer validates. */
    private static String packFormatOf(String packToml) {
        String format = stringOr(packToml, "pack-format", "");
        return format.isEmpty() ? "packwiz:1.1.0" : format;
    }

    private static String readIndexFile(String packToml, String fallback) {
        String index = section(packToml, "index");
        if (index == null) {
            return fallback;
        }
        String file = value(index, "file");
        return file == null || file.isBlank() ? fallback : file;
    }

    private static String value(String toml, String key) {
        for (String raw : toml.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(key) ) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = line.substring(0, eq).trim();
                if (!name.equals(key)) {
                    continue;
                }
                return unquote(line.substring(eq + 1).trim());
            }
        }
        return null;
    }

    /** Returns the body of a {@code [name]} table, or null when absent. */
    private static String section(String toml, String name) {
        String header = "[" + name + "]";
        StringBuilder body = new StringBuilder();
        boolean in = false;
        for (String raw : toml.split("\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                if (in) {
                    break;
                }
                in = line.equals(header);
                continue;
            }
            if (in) {
                body.append(line).append('\n');
            }
        }
        return in ? body.toString() : null;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            v = v.substring(1, v.length() - 1);
        }
        int hash = v.indexOf(" #");
        if (hash >= 0) {
            v = v.substring(0, hash).trim();
        }
        return v;
    }

    /** A {@code [[files]]} entry and its offsets in the original text. */
    private record Block(int start, int end, String body) {
        String file() {
            return value(body, "file");
        }

        boolean isMetafile() {
            return "true".equals(value(body, "metafile"));
        }
    }

    private static List<Block> parseIndex(String indexToml) {
        List<int[]> spans = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int cursor = 0;
        while (cursor < indexToml.length()) {
            int at = indexToml.indexOf("[[files]]", cursor);
            if (at < 0) {
                break;
            }
            starts.add(at);
            int next = indexToml.indexOf("[[files]]", at + 1);
            if (next < 0) {
                next = indexToml.length();
            }
            spans.add(new int[] {at, next});
            cursor = next;
        }

        List<Block> blocks = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = spans.get(i)[1];
            blocks.add(new Block(start, end, indexToml.substring(start, end)));
        }
        return blocks;
    }

    private static int countMetafiles(List<Block> blocks) {
        int n = 0;
        for (Block block : blocks) {
            if (block.isMetafile()) {
                n++;
            }
        }
        return n;
    }

    static String fetch(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "PackUpdater")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString().toLowerCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}