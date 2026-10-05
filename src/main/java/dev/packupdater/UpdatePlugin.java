package dev.packupdater;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformationService.Resource;
import cpw.mods.modlauncher.api.ITransformer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the auto-updater during ModLauncher's transformation-service phase, which is early
 * enough for the NeoForge loading screen to still be up and can therefore show progress.
 */
public class UpdatePlugin implements ITransformationService {

    @Override
    public @NotNull String name() {
        return PackUpdater.MODID;
    }

    @Override
    public void initialize(IEnvironment environment) {
        UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.gameDirectory());

        if (config.skip) {
            PackUpdater.LOGGER.info("[PackUpdater] Skipping update check because '{}' is set.", UpdaterConfig.KEY_SKIP);
            return;
        }

        String packUrl = config.effectivePackUrl();
        if (packUrl.isEmpty()) {
            PackUpdater.LOGGER.warn("[PackUpdater] No pack URL configured ({} is blank), skipping update.", UpdaterConfig.KEY_URL);
            return;
        }

        PackUpdater.LOGGER.info("[PackUpdater] Updating pack from {} (installer: {})", packUrl,
                config.installerUrl.isEmpty() ? "self-update disabled" : config.installerUrl);
        try {
            UpdaterBootstrap.runUpdate(config, packUrl);
        } catch (Exception e) {
            // A failed update must never prevent the game from starting; the pack's own
            // hash verification will catch anything left in a bad state on the next run.
            PackUpdater.LOGGER.error("[PackUpdater] Update failed: {}", e.toString(), e);
        }
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {}

    @Override
    public @NotNull List<ITransformer<?>> transformers() {
        return new ArrayList<>();
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        return List.of();
    }

    @Override
    public List<Resource> completeScan(IModuleLayerManager layerManager) {
        return List.of();
    }
}