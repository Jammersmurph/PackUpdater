package dev.packupdater.modern;

import dev.packupdater.UpdaterStartup;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/**
 * Updates before the built-in mods-folder locator takes its directory snapshot.
 * Unlike graphics bootstrap hooks, this also runs on dedicated servers.
 */
public final class ModernUpdateLocator implements IModFileCandidateLocator {
    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        // Legacy ModLauncher has already run the transformation service. Do not
        // resolve the modern FMLLoader methods on those releases.
        if (UpdaterStartup.hasStarted()) {
            return;
        }
        FMLLoader loader = FMLLoader.getCurrent();
        UpdaterStartup.run(loader.getGameDir(),
                loader.getDist() == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER ? "server" : "client");
    }
}
