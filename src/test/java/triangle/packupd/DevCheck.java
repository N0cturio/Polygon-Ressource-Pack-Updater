package triangle.packupd;

import java.nio.file.Path;

/**
 * Developer tool (test sources only, never in the shipped jar): runs the real update with the links baked from gradle.properties into a scratch
 * folder, without starting Minecraft. Usage: {@code gradlew checkPack -PgameDir=C:\temp\testgame}. It downloads into
 * {@code <gameDir>/resourcepacks} and edits {@code <gameDir>/options.txt}, nothing else. Run it twice: the second run must say "up to date".
 */
public final class DevCheck {
    private DevCheck() {}

    public static void main(String[] args) {
        Path game = Path.of(args.length > 0 ? args[0] : "build/checkPack-game");
        PackConfig cfg = PackConfig.load(game.resolve("config").resolve("polygonpackupd.json"));
        PackUpdater.Outcome o;
        try (HttpFetch http = new HttpFetch(cfg.connectTimeoutSec(), cfg.downloadTimeoutSec())) {
            o = new PackUpdater(game, cfg, http).run();
        }
        System.out.println("result: " + o + " (game folder " + game.toAbsolutePath() + ")");
        if (o == PackUpdater.Outcome.FAILED) System.exit(1);
    }
}
