package triangle.packupd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole update flow against a local server that serves a manifest and a pack zip. */
class PackUpdaterTest {
    HttpServer server;
    String base;
    volatile byte[] current;
    volatile String manifest;
    final AtomicInteger zipDownloads = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/manifest.json", ex -> {
            byte[] b = manifest.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/ResourcePack.zip", ex -> {
            zipDownloads.incrementAndGet();
            ex.sendResponseHeaders(200, current.length);
            ex.getResponseBody().write(current);
            ex.close();
        });
        server.start();
        publish(zip("v1"), true);
    }

    @AfterEach
    void stop() { server.stop(0); }

    void publish(byte[] zip, boolean withUrl) throws Exception {
        current = zip;
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        manifest = "{\"sha1\":\"" + sha1 + "\",\"size\":" + zip.length + (withUrl ? ",\"url\":\"" + base + "/ResourcePack.zip\"" : "") + "}";
    }

    static byte[] zip(String marker) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("pack.mcmeta"));
            z.write(("{\"pack\":{\"pack_format\":34,\"description\":\"" + marker + "\"}}").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/minecraft/marker.txt"));
            z.write(marker.repeat(2000).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bo.toByteArray();
    }

    PackUpdater updater(Path game, String zipLink) {
        return new PackUpdater(game, new PackConfig(true, zipLink, "ResourcePack.zip", true, 3, 20, base + "/manifest.json"), new HttpFetch(3, 20));
    }

    @Test
    void installsSkipsAndUpdates(@TempDir Path game) throws Exception {
        Path pack = game.resolve("resourcepacks/ResourcePack.zip");
        assertEquals(PackUpdater.Outcome.INSTALLED, updater(game, "").run());
        assertArrayEquals(current, Files.readAllBytes(pack));
        assertTrue(Files.readString(game.resolve("options.txt")).contains("resourcePacks:[\"vanilla\",\"file/ResourcePack.zip\"]"));
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip.download")));

        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater(game, "").run());
        assertEquals(1, zipDownloads.get(), "an unchanged pack is not downloaded again");

        publish(zip("v2"), true);
        assertEquals(PackUpdater.Outcome.UPDATED, updater(game, "").run());
        assertArrayEquals(current, Files.readAllBytes(pack));
        assertEquals(1, Files.readString(game.resolve("options.txt")).split("file/ResourcePack.zip", -1).length - 1, "enabled once, never duplicated");
    }

    @Test
    void zipLinkFromConfigWhenManifestHasNoUrl(@TempDir Path game) throws Exception {
        publish(zip("v1"), false);
        assertEquals(PackUpdater.Outcome.INSTALLED, updater(game, base + "/ResourcePack.zip").run());
        assertEquals(PackUpdater.Outcome.FAILED, updater(Files.createTempDirectory(game, "b"), "").run(), "no url anywhere: a clear failure");
    }

    @Test
    void wrongHashKeepsTheOldPack(@TempDir Path game) throws Exception {
        assertEquals(PackUpdater.Outcome.INSTALLED, updater(game, "").run());
        byte[] v1 = Files.readAllBytes(game.resolve("resourcepacks/ResourcePack.zip"));
        current = zip("v2");
        manifest = "{\"sha1\":\"" + "0".repeat(40) + "\",\"url\":\"" + base + "/ResourcePack.zip\"}";   // the manifest promises a hash the bytes do not have
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
        assertArrayEquals(v1, Files.readAllBytes(game.resolve("resourcepacks/ResourcePack.zip")));
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip.download")));
    }

    @Test
    void sizeMismatchIsRejected(@TempDir Path game) throws Exception {
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(current));
        manifest = "{\"sha1\":\"" + sha1 + "\",\"size\":" + (current.length + 7) + ",\"url\":\"" + base + "/ResourcePack.zip\"}";
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
    }

    @Test
    void notAZipIsRejected(@TempDir Path game) throws Exception {
        publish("<html>sign in to view this file</html>".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
    }

    @Test
    void zipWithoutPackMcmetaIsRejected(@TempDir Path game) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("ResourcePack/pack.mcmeta"));      // zipped the folder instead of its contents
            z.write("{}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        publish(bo.toByteArray(), true);
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
    }

    @Test
    void garbageManifestIsAFailureNotACrash(@TempDir Path game) {
        manifest = "<html>not json</html>";
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
    }

    @Test
    void offlineKeepsTheInstalledPackEnabled(@TempDir Path game) throws Exception {
        assertEquals(PackUpdater.Outcome.INSTALLED, updater(game, "").run());
        Files.delete(game.resolve("options.txt"));
        server.stop(0);                                                      // no network at all
        assertEquals(PackUpdater.Outcome.FAILED, updater(game, "").run());
        assertTrue(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
        assertTrue(Files.readString(game.resolve("options.txt")).contains("file/ResourcePack.zip"), "the installed pack is still enabled when offline");
    }

    @Test
    void localEditIsHealed(@TempDir Path game) throws Exception {
        assertEquals(PackUpdater.Outcome.INSTALLED, updater(game, "").run());
        Files.write(game.resolve("resourcepacks/ResourcePack.zip"), zip("tampered"));
        assertEquals(PackUpdater.Outcome.UPDATED, updater(game, "").run());
        assertArrayEquals(current, Files.readAllBytes(game.resolve("resourcepacks/ResourcePack.zip")));
    }

    @Test
    void disabledOrUnconfiguredDoesNothing(@TempDir Path game) {
        assertEquals(PackUpdater.Outcome.DISABLED, new PackUpdater(game, new PackConfig(false, "", "ResourcePack.zip", true, 3, 20, base + "/manifest.json"), new HttpFetch(3, 20)).run());
        assertEquals(PackUpdater.Outcome.NOT_CONFIGURED, new PackUpdater(game, new PackConfig(true, "", "ResourcePack.zip", true, 3, 20, ""), new HttpFetch(3, 20)).run());
        assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
    }
}
