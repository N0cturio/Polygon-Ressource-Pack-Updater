package triangle.packupd;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs in Fabric's preLaunch phase: before Minecraft loads, nothing has opened the pack zip yet (so it can be replaced even on Windows) and
 * options.txt has not been read yet (so editing it is enough to start the game with the pack enabled). Never throws: a failure only means
 * the game starts with whatever pack is already installed.
 */
public final class PackUpdaterEntrypoint implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;
            PackConfig cfg = PackConfig.load(FabricLoader.getInstance().getConfigDir().resolve("polygonpackupd.json"));
            long t0 = System.currentTimeMillis();
            PackUpdater.Outcome o;
            try (HttpFetch http = new HttpFetch(cfg.connectTimeoutSec(), cfg.downloadTimeoutSec())) {
                o = new PackUpdater(FabricLoader.getInstance().getGameDir(), cfg, http).run();
            }
            Log.info("pack check finished: " + o + " in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            Log.error("unexpected failure, continuing without a pack check", t);
        }
    }
}
