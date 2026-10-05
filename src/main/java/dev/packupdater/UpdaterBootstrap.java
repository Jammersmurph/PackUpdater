package dev.packupdater;

import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    public static void runUpdate(UpdaterConfig config, String packUrl) throws Exception {
        Path tempDir = Files.createTempDirectory("packupdater");
        Path bootstrapJar = tempDir.resolve("packupdater-bootstrap.jar");
        Path gameDir = UpdaterConfig.gameDirectory();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(bootstrapJar.toString());
        command.add(packUrl);

        // Self-update wiring for the bootstrapper. The installer release URL and asset name
        // are configurable; omitting the URL disables the self-update entirely.
        if (!config.installerUrl.isEmpty()) {
            command.add("--bootstrap-update-url");
            command.add(config.installerUrl);
        }
        command.add("--bootstrap-asset");
        command.add(config.installerAsset);
        if (!config.installerToken.isEmpty()) {
            command.add("--bootstrap-update-token");
            command.add(config.installerToken);
        }
        if (!config.gui) {
            command.add("-g");
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

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The installer resolves its output paths relative to the working directory, so this
        // has to stay the game directory rather than the temp dir.
        builder.directory(gameDir.toFile());
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                PackUpdater.LOGGER.info("[PackUpdater] {}", line);
                updateLoadingScreen(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Bootstrapper exited with code " + exitCode);
        }
        PackUpdater.LOGGER.info("[PackUpdater] Update complete.");
    }

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