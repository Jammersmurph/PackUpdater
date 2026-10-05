package dev.packupdater;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PackUpdater.MODID)
public class PackUpdater {
    public static final String MODID = "packupdater";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackUpdater() {}
}