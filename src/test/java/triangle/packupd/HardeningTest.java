package triangle.packupd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 1.0.3 safety limits: file names, https only, the download cap. */
class HardeningTest {
    @Test
    void packFileNamesMustBePlain() {
        assertEquals("ResourcePack.zip", PackConfig.safeName("ResourcePack"));
        assertEquals("My Pack-2.zip", PackConfig.safeName("My Pack-2.zip"));
        for (String bad : new String[] {"../evil.zip", "..\\evil", "a/b.zip", "C:\\x.zip", ".hidden.zip", "na\u0000me", "x".repeat(120), "a*b.zip", "con|x"}) {
            assertEquals("ResourcePack.zip", PackConfig.safeName(bad), bad);
        }
        assertEquals("ResourcePack.zip", PackConfig.safeName(""));
        assertEquals("ResourcePack.zip", PackConfig.safeName(null));
    }

    @Test
    void onlyHttpsLinksAreAccepted() throws Exception {
        HttpFetch.requireHttps("https://drive.google.com/x");
        HttpFetch.requireHttps("http://127.0.0.1:8080/x");        // the local machine, used by the tests
        for (String bad : new String[] {"http://example.com/pack.zip", "ftp://example.com/pack.zip", "file:///C:/pack.zip", "//example.com/x", "not a url"}) {
            assertThrows(IOException.class, () -> HttpFetch.requireHttps(bad), bad);
        }
        try (HttpFetch http = new HttpFetch(2, 5)) {
            assertThrows(IOException.class, () -> http.getString("http://example.com/manifest.json"));
        }
    }

    @Test
    void manifestPointingAtPlainHttpIsRefused(@TempDir Path game) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        s.createContext("/m", ex -> {
            byte[] b = ("{\"sha1\":\"" + "a".repeat(40) + "\",\"url\":\"http://example.com/ResourcePack.zip\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        try {
            PackUpdater u = new PackUpdater(game, new PackConfig(true, "", "ResourcePack.zip", true, 3, 20, base + "/m"), new HttpFetch(3, 20));
            assertEquals(PackUpdater.Outcome.FAILED, u.run());
            assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void downloadsOverTheCapAreRefused(@TempDir Path game) throws Exception {
        byte[] zip = PackUpdaterTest.zip("big");                       // a valid pack, but larger than the tiny cap used here
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        boolean[] lie = {false};
        s.createContext("/m", ex -> {
            // lie = the manifest claims a small size while the body is big: the streaming cap must still stop it
            byte[] b = ("{\"sha1\":\"" + sha1 + "\",\"size\":" + (lie[0] ? 10 : zip.length) + ",\"url\":\"" + base + "/z\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.createContext("/z", ex -> {
            ex.getResponseHeaders().add("Transfer-Encoding", "chunked");
            ex.sendResponseHeaders(200, 0);                             // chunked: no Content-Length, only the running count can stop it
            ex.getResponseBody().write(zip);
            ex.close();
        });
        s.start();
        try {
            long cap = zip.length / 2;
            assertTrue(zip.length > cap);
            PackConfig cfg = new PackConfig(true, "", "ResourcePack.zip", true, 3, 20, base + "/m");
            assertEquals(PackUpdater.Outcome.FAILED, new PackUpdater(game, cfg, new HttpFetch(3, 20, cap)).run(), "declared size over the cap");
            lie[0] = true;
            assertEquals(PackUpdater.Outcome.FAILED, new PackUpdater(game, cfg, new HttpFetch(3, 20, cap)).run(), "body over the cap with a lying manifest");
            assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip")));
            assertFalse(Files.exists(game.resolve("resourcepacks/ResourcePack.zip.download")));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void defaultCapIs50Megabytes() {
        assertEquals(50L * 1024 * 1024, HttpFetch.DEFAULT_MAX_BYTES);
        assertTrue(34_683_366L < HttpFetch.DEFAULT_MAX_BYTES, "today's pack fits");
    }
}
