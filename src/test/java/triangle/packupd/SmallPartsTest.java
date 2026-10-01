package triangle.packupd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmallPartsTest {
    @Test
    void jsonReaderHandlesNestedObjectsEscapesAndNumbers() {
        Map<String, Object> j = MiniJson.object("{\"name\":\"ResourcePack.zip\",\"size\":34680123,\"file\":{\"hashes\":{\"sha1Hash\":\"ABCDEF\",\"crc32Hash\":\"1\"},\"mimeType\":\"application/zip\"},"
            + "\"@content.downloadUrl\":\"https://x.example/dl?a=1\\u0026b=2\",\"list\":[1,2.5,true,null,\"\\\"q\\\"\"]}");
        assertEquals("ResourcePack.zip", MiniJson.str(j, "name"));
        assertEquals(34680123L, MiniJson.num(j, -1, "size"));
        assertEquals("ABCDEF", MiniJson.str(j, "file", "hashes", "sha1Hash"));
        assertEquals("https://x.example/dl?a=1&b=2", MiniJson.str(j, "@content.downloadUrl"));
        assertNull(MiniJson.str(j, "file", "nothing"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":"));
    }

    @Test
    void optionsCreatedWhenMissing(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("options.txt");
        assertTrue(OptionsFile.ensureEnabled(f, "file/ResourcePack.zip"));
        assertEquals("version:3955\nresourcePacks:[\"vanilla\",\"file/ResourcePack.zip\"]\n", Files.readString(f));
    }

    @Test
    void optionsKeepEverythingElseAndAreIdempotent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("options.txt");
        Files.writeString(f, "version:3955\r\nfov:0.5\r\nresourcePacks:[\"vanilla\",\"file/Other.zip\"]\r\nlang:fr_fr\r\n");
        assertTrue(OptionsFile.ensureEnabled(f, "file/ResourcePack.zip"));
        assertEquals("version:3955\r\nfov:0.5\r\nresourcePacks:[\"vanilla\",\"file/Other.zip\",\"file/ResourcePack.zip\"]\r\nlang:fr_fr\r\n", Files.readString(f));
        assertFalse(OptionsFile.ensureEnabled(f, "file/ResourcePack.zip"));
    }

    @Test
    void optionsAddLineWhenAbsentAndKeepVersion(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("options.txt");
        Files.writeString(f, "version:3955\nfov:0.5\n");
        assertTrue(OptionsFile.ensureEnabled(f, "file/ResourcePack.zip"));
        assertEquals("version:3955\nfov:0.5\nresourcePacks:[\"vanilla\",\"file/ResourcePack.zip\"]\n", Files.readString(f));
    }

    @Test
    void arrayRoundTripWithEscapes() {
        List<String> l = OptionsFile.parseArray("[\"a\",\"b\\\"c\",\"d\\\\e\"]");
        assertEquals(List.of("a", "b\"c", "d\\e"), l);
        assertEquals("[\"a\",\"b\\\"c\",\"d\\\\e\"]", OptionsFile.toArray(l));
    }

    @Test
    void configDefaultsAndOverrides(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config").resolve("polygonpackupd.json");
        PackConfig c = PackConfig.load(f);                       // writes the template, uses the built-in defaults
        assertTrue(Files.isRegularFile(f));
        assertTrue(c.enabled() && c.keepEnabled());
        assertTrue(c.packFileName().endsWith(".zip"));
        Files.writeString(f, "{\"enabled\": false, \"shareUrl\": \"https://example.com/z.zip\", \"manifestUrl\": \"https://example.com/m.json\", \"packFileName\": \"Mine\"}");
        PackConfig o = PackConfig.load(f);
        assertFalse(o.enabled());
        assertEquals("https://example.com/z.zip", o.shareUrl());
        assertEquals("https://example.com/m.json", o.manifestUrl());
        assertEquals("Mine.zip", o.packFileName());
    }

    @Test
    void googleDriveLinksAreNormalized() {
        assertEquals("https://drive.usercontent.google.com/download?id=1AbC_dE-9&export=download&confirm=t",
            LinkNormalizer.direct("https://drive.google.com/file/d/1AbC_dE-9/view?usp=sharing"));
        assertEquals("https://drive.usercontent.google.com/download?id=1AbC_dE-9&export=download&confirm=t",
            LinkNormalizer.direct("https://drive.google.com/uc?export=download&id=1AbC_dE-9"));
        assertEquals("https://example.com/ResourcePack.zip", LinkNormalizer.direct("https://example.com/ResourcePack.zip"));
    }
}
