package triangle.packupd;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Settings. The links and the pack name are baked into the jar (packupd-defaults.properties, filled from gradle.properties), so players
 * configure nothing. config/polygonpackupd.json only exists to switch the mod off or to override a value for testing.
 */
record PackConfig(boolean enabled, String shareUrl, String packFileName, boolean keepEnabled,
                  int connectTimeoutSec, int downloadTimeoutSec, String manifestUrl) {

    static final String DEFAULT_NAME = "ResourcePack.zip";

    /** A plain file name only (letters, digits, space, dot, dash, underscore; no folder parts, no leading dot), ending in .zip; anything else falls back to the default. */
    static String safeName(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.toLowerCase().endsWith(".zip")) n = n + ".zip";
        if (n.length() > 100 || n.startsWith(".") || !n.matches("[A-Za-z0-9._ -]+")) {
            Log.warn("ignoring an unsafe pack file name, using " + DEFAULT_NAME);
            return DEFAULT_NAME;
        }
        return n;
    }

    static PackConfig load(Path configFile) {
        Properties d = new Properties();
        try (InputStream in = PackConfig.class.getResourceAsStream("/packupd-defaults.properties")) {
            if (in != null) d.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("could not read the built-in defaults: " + e);
        }
        boolean enabled = true, keep = true;
        String manifest = d.getProperty("manifestUrl", "").trim();
        String share = d.getProperty("shareUrl", "").trim(), name = d.getProperty("packFileName", DEFAULT_NAME).trim();
        int connect = 5, download = 180;
        if (Files.isRegularFile(configFile)) {
            try {
                Map<String, Object> j = MiniJson.object(Files.readString(configFile, StandardCharsets.UTF_8));
                if (j.get("enabled") instanceof Boolean b) enabled = b;
                if (j.get("keepEnabled") instanceof Boolean b) keep = b;
                String s = MiniJson.str(j, "shareUrl"), n = MiniJson.str(j, "packFileName"), m = MiniJson.str(j, "manifestUrl");
                if (s != null && !s.isBlank()) share = s.trim();
                if (n != null && !n.isBlank()) name = n.trim();
                if (m != null && !m.isBlank()) manifest = m.trim();
                connect = (int) MiniJson.num(j, connect, "connectTimeoutSec");
                download = (int) MiniJson.num(j, download, "downloadTimeoutSec");
            } catch (Exception e) {
                Log.warn("ignoring unreadable " + configFile.getFileName() + ": " + e);
            }
        } else {
            try {
                Files.createDirectories(configFile.toAbsolutePath().getParent());
                Files.writeString(configFile, "{\n  \"enabled\": true,\n  \"keepEnabled\": true\n}\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.warn("could not write the config template: " + e);
            }
        }
        return new PackConfig(enabled, share, safeName(name), keep, connect, download, manifest);
    }
}
