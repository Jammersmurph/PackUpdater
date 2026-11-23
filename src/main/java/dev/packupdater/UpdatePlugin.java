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
        LoaderAccess.startLegacy();
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
