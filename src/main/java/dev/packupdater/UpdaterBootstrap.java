package dev.packupdater;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdaterBootstrap {

    private static final String BOOTSTRAP_RESOURCE = "/updater/packupdater-bootstrap.jar";
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("^\\((\\d+)/(\\d+)\\)\\s+(.*)$");

    private static ProgressMeter earlyProgress;
    private static Field stepsField;

    private UpdaterBootstrap() {}

    /**
     * Whether to let the PackWiz installer open its own window.
     *
     * <p>The installer's GUI is what exposes optional mods, so it is on by default. A
     * headless session (a dedicated server, a CI job) cannot show a window and would fail
     * trying, so fall back to the non-GUI path there regardless of configuration.
     */
    private static boolean useGui(UpdaterConfig config) {
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        String reason = headless ? "no display" : preflightReason(config);
        boolean use = shouldUseGui(config.gui, headless, reason, forceFallback(config), config.serverSide);
        if (!use && reason != null) {
            PackUpdater.LOGGER.info("[PackUpdater] Running the installer without a window ({}).", reason);
        }
        return use;
    }

    /**
     * Whether the installer should be allowed to open a window.
     *
     * <p>Split out as a pure function so the whole matrix can be tested, because getting this
     * wrong is invisible in the common case: the installer's own entry point also falls back to
     * a non-GUI handler when it detects a headless JVM, so a missing {@code -g} still mostly
     * works, just noisily, and only shows up as a HeadlessException on a background thread.
     *
     * @param configuredGui  the {@code packupdater.gui} setting
     * @param headless       whether this JVM can open a window at all
     * @param probeReason    why a forked JVM could not open one, or null if it could
     * @param forcedFallback whether the fallback is being forced by configuration
     * @param serverSide     whether this instance is a dedicated server, which has no window
     */
    public static boolean shouldUseGui(
            boolean configuredGui, boolean headless, String probeReason, boolean forcedFallback, boolean serverSide) {
        if (!configuredGui || forcedFallback || serverSide) {
            return false;
        }
        return !headless && probeReason == null;
    }

    public static void runUpdate(UpdaterConfig config, String packUrl) throws Exception {
        Path tempDir = Files.createTempDirectory("packupdater");
        Path bootstrapJar = tempDir.resolve("packupdater-bootstrap.jar");
        Path gameDir = UpdaterConfig.gameDirectory();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // Must come before -jar: the JVM consumes these, so they never reach the installer's
        // own argument parser, which only accepts a fixed set of bootstrap-* options.
        command.add("-D" + UpdaterConfig.KEY_INSTALLER_ASSET + "=" + config.installerAsset);
        command.add("-jar");
        command.add(bootstrapJar.toString());
        command.add(packUrl);

        // Self-update wiring for the bootstrapper. The installer release URL is configurable;
        // omitting it disables the self-update entirely. These are all options the installer
        // also registers, so it tolerates them being forwarded.
        if (!config.installerUrl.isEmpty()) {
            command.add("--bootstrap-update-url");
            command.add(config.installerUrl);
        }
        if (!config.installerToken.isEmpty()) {
            command.add("--bootstrap-update-token");
            command.add(config.installerToken);
        }
        // This is the decision the whole compatibility layer exists to make, so it has to be
        // the one that adds -g. Computing the answer and then passing config.gui instead is how
        // the headless and probe checks became dead code in the first place.
        boolean attemptGui = useGui(config);
        if (!attemptGui) {
            command.add("-g");
        }
        if (config.serverSide) {
            // Instructs the installer to install server-side mods rather than client ones.
            command.add("-s");
            command.add("server");
        }

        try {
            startupProgressMeter();
        } catch (Throwable e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Progress reporting unavailable: {}", e.toString());
        }

        PackUpdater.LOGGER.info("[PackUpdater] Using pack URL: {}", packUrl);

        try (InputStream in = UpdaterBootstrap.class.getResourceAsStream(BOOTSTRAP_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException("Missing internal bootstrap jar: " + BOOTSTRAP_RESOURCE);
            }
            Files.copy(in, bootstrapJar, StandardCopyOption.REPLACE_EXISTING);
        }
        PackUpdater.LOGGER.info("[PackUpdater] Bootstrapper extracted to {}", bootstrapJar);

        if (config.engine == UpdaterConfig.Engine.DIRECT) {
            runDirect(config, packUrl, gameDir);
            return;
        }

        Run first;
        try {
            first = execute(command, gameDir);
        } catch (ForkNotPermitted e) {
            if (config.engine == UpdaterConfig.Engine.FORK) {
                throw e;
            }
            // The usual cause is an app sandbox that permits using a runtime in-process but not
            // executing one, which is how Android launchers run the game.
            PackUpdater.LOGGER.warn(
                    "[PackUpdater] This platform will not let PackUpdater start a second JVM ({}). "
                            + "Installing in this process instead; the installer's window is not "
                            + "available and optional mods will follow the pack defaults.",
                    e.getMessage());
            runDirect(config, packUrl, gameDir);
            return;
        }

        if (first.exitCode != 0 && attemptGui && canFallback(config) && GuiCapability.looksLikeGuiFailure(first.output)) {
            PackUpdater.LOGGER.warn(
                    "[PackUpdater] The installer's window could not be opened ({}). "
                            + "Falling back to a silent update; optional mods will be limited to "
                            + "those enabled by default in the pack. See the log for the full reason.",
                    summarise(first.output));
            runFilteredFallback(config, packUrl, gameDir, tempDir, javaBin, bootstrapJar);
            return;
        }

        if (first.exitCode != 0) {
            throw new IllegalStateException("Bootstrapper exited with code " + first.exitCode);
        }
        PackUpdater.LOGGER.info("[PackUpdater] Update complete.");
    }

    /** Raised when the platform refuses to let us start the updater process at all. */
    private static final class ForkNotPermitted extends IllegalStateException {
        ForkNotPermitted(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Installs the pack inside this JVM, with no window.
     *
     * <p>The installer cannot simply be called here: it invokes {@link System#exit(int)} on
     * nearly every path including success, which would end the running game, and Java 21
     * refuses to let {@code System.exit} be trapped. So the download and verification loop is
     * performed directly instead.
     */
    private static void runDirect(UpdaterConfig config, String packUrl, Path gameDir) throws IOException {
        String side = FMLLoader.getDist() == Dist.CLIENT ? "client" : "server";
        PackUpdater.LOGGER.info("[PackUpdater] Installing {} into {} without a child process", packUrl, gameDir);

        InProcessInstaller.Result result = InProcessInstaller.sync(
                gameDir, packUrl, side, httpClient(), (message, done, total) -> {
                    updateLoadingScreen("(" + done + "/" + total + ") " + message);
                });

        PackUpdater.LOGGER.info(
                "[PackUpdater] Update complete: {} downloaded, {} already current, {} removed, {} skipped",
                result.installed(), result.validated(), result.removed(), result.skipped());
    }

    /** A finished installer run plus everything it printed, kept for failure diagnosis. */
    private record Run(int exitCode, String output) {}

    /**
     * Runs the installer again with no window, against an index that omits optional mods the
     * pack did not enable by default.
     *
     * <p>Needed because the installer's CLI path force-enables every optional mod, so simply
     * adding {@code -g} would install the union of all of them rather than the ones a player
     * would have kept.
     */
    private static void runFilteredFallback(
            UpdaterConfig config, String packUrl, Path gameDir, Path tempDir, String javaBin, Path bootstrapJar)
            throws Exception {

        Path packDir = Files.createDirectories(tempDir.resolve("pack"));
        OptionalModFilter.Result filtered = OptionalModFilter.build(packDir, packUrl, httpClient());
        warnUserAboutFallback(filtered);

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-D" + UpdaterConfig.KEY_INSTALLER_ASSET + "=" + config.installerAsset);
        command.add("-jar");
        command.add(bootstrapJar.toString());
        command.add(filtered.packFile().toUri().toString());
        if (!config.installerUrl.isEmpty()) {
            command.add("--bootstrap-update-url");
            command.add(config.installerUrl);
        }
        if (!config.installerToken.isEmpty()) {
            command.add("--bootstrap-update-token");
            command.add(config.installerToken);
        }
        command.add("-g");
        if (config.serverSide) {
            command.add("-s");
            command.add("server");
        }
        // The pack file lives in a temp dir, so the install root has to be stated explicitly.
        // Without this the installer would install into the temp dir instead of the instance.
        command.add("--pack-folder");
        command.add(gameDir.toString());

        PackUpdater.LOGGER.info(
                "[PackUpdater] Silent update against a filtered index: {} optional mods kept, {} omitted",
                filtered.retainedOptional(), filtered.dropped());

        Run run = execute(command, gameDir);
        if (run.exitCode != 0) {
            throw new IllegalStateException("Fallback bootstrapper exited with code " + run.exitCode);
        }
        PackUpdater.LOGGER.info("[PackUpdater] Update complete.");
    }

    private static Run execute(List<String> command, Path workingDir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The installer resolves its output paths relative to the working directory, so this
        // has to stay the game directory rather than the temp dir.
        builder.directory(workingDir.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (Exception e) {
            // Almost always the platform refusing to execute a second JVM. Android launchers
            // sandbox this, and they run the game in-process so their runtime is not executable.
            throw new ForkNotPermitted("could not execute " + command.get(0) + ": " + e, e);
        }

        StringBuilder captured = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                PackUpdater.LOGGER.info("[PackUpdater] {}", line);
                updateLoadingScreen(line);
                if (captured.length() < MAX_CAPTURE) {
                    captured.append(line).append('\n');
                }
            }
            return new Run(process.waitFor(), captured.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the updater", e);
        }
    }

    /** Warns on the loading screen before the silent run, so the player actually sees it. */
    private static void warnUserAboutFallback(OptionalModFilter.Result filtered) {
        String message = filtered.dropped() > 0
                ? String.format("Updater: silent install, %s optional mods skipped", filtered.dropped())
                : "Updater: silent install";
        PackUpdater.LOGGER.warn(
                "[PackUpdater] Running without the installer window. {} optional mods that the pack "
                        + "leaves disabled are being skipped, so those will not be installed.",
                filtered.dropped());
        try {
            ImmediateWindowHandler.updateProgress(message);
        } catch (Throwable ignored) {
            // Progress reporting is best effort.
        }
    }

    private static boolean forceFallback(UpdaterConfig config) {
        return config.compat == UpdaterConfig.Compat.AUTO
                && config.fallback == UpdaterConfig.Fallback.ALWAYS;
    }

    private static boolean canFallback(UpdaterConfig config) {
        return config.compat == UpdaterConfig.Compat.AUTO
                && config.fallback != UpdaterConfig.Fallback.NEVER;
    }

    /** True when this platform cannot be expected to open a window from a child JVM. */
    private static String preflightReason(UpdaterConfig config) {
        if (!config.gui || !canFallback(config)) {
            return null;
        }
        if (forceFallback(config)) {
            return "packupdater.fallback=always";
        }
        return GuiCapability.forkedGuiUnusable();
    }

    private static String summarise(String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && GuiCapability.looksLikeGuiFailure(trimmed)) {
                return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            }
        }
        return "no diagnostic output";
    }

    private static java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
    }

    private static final int MAX_CAPTURE = 64 * 1024;

    private static void startupProgressMeter() throws ReflectiveOperationException {
        List<ProgressMeter> progressList = StartupNotificationManager.getCurrentProgress();
        if (progressList.isEmpty()) {
            return;
        }
        earlyProgress = progressList.get(0);
        stepsField = ProgressMeter.class.getDeclaredField("steps");
        stepsField.setAccessible(true);
    }

    private static void updateLoadingScreen(String rawLine) {
        String cleanLine = rawLine.replace("[PackUpdater]", "").trim();
        if (cleanLine.isEmpty() || cleanLine.startsWith("UNSUPPORTED")) return;

        String displayMessage = "Updater: Working...";
        Matcher matcher = PROGRESS_PATTERN.matcher(cleanLine);

        if (matcher.find()) {
            int current = 0;
            int total = 0;
            try {
                current = Integer.parseInt(matcher.group(1));
                total = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {}

            String content = matcher.group(3);
            String progressStr = matcher.group(1) + "/" + matcher.group(2);

            if (content.contains("Downloaded")) {
                String modName = content.replace("Downloaded", "").trim();
                displayMessage = String.format("Downloading: %s (%s)", modName, progressStr);
            } else if (content.contains("already exists")) {
                String fileName = content.split(" ")[0];
                displayMessage = String.format("Verifying: %s (%s)", fileName, progressStr);
            } else {
                displayMessage = String.format("Processing: %s", progressStr);
            }

            setProgressBarState(current, total);
        } else {
            boolean isIndeterminate = true;

            if (cleanLine.contains("Current version") || cleanLine.contains("New version")) {
                displayMessage = "Checking for updates...";
            } else if (cleanLine.contains("Loading manifest") || cleanLine.contains("Loading pack")) {
                displayMessage = "Loading configuration...";
            } else if (cleanLine.contains("Checking local files") || cleanLine.contains("Comparing new files")) {
                displayMessage = "Scanning local files...";
            } else if (cleanLine.contains("invalidated")) {
                displayMessage = "Found updates...";
            } else if (cleanLine.contains("Already up to date")) {
                displayMessage = "Pack is up to date!";
                isIndeterminate = false;
            } else if (cleanLine.contains("Finished successfully")) {
                displayMessage = "Update Complete!";
                isIndeterminate = false;
            } else {
                return;
            }

            if (isIndeterminate) {
                setIndeterminate();
            } else {
                setProgressBarState(1, 1);
            }
        }

        ImmediateWindowHandler.updateProgress(displayMessage);
    }

    private static void setProgressBarState(int current, int total) {
        if (earlyProgress == null || stepsField == null || total <= 0) {
            return;
        }
        try {
            if (earlyProgress.steps() != total) {
                stepsField.setInt(earlyProgress, total);
            }
            earlyProgress.setAbsolute(current);
        } catch (ReflectiveOperationException | RuntimeException e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Could not set progress: {}", e.toString());
        }
    }

    private static void setIndeterminate() {
        if (earlyProgress == null || stepsField == null) {
            return;
        }
        try {
            if (earlyProgress.steps() != 0) {
                stepsField.setInt(earlyProgress, 0);
                earlyProgress.setAbsolute(0);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            PackUpdater.LOGGER.debug("[PackUpdater] Could not reset progress: {}", e.toString());
        }
    }
}