package triangle.packupd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads the manifest, compares its SHA-1 with the installed pack, downloads the pack when they differ (verified by SHA-1, size and a look inside
 * the zip), swaps it into resourcepacks/ atomically and makes sure the game starts with it enabled. Every failure leaves the previous pack in
 * place: an update never breaks a working install.
 */
final class PackUpdater {
    enum Outcome { DISABLED, NOT_CONFIGURED, UP_TO_DATE, INSTALLED, UPDATED, FAILED }

    /** What the manifest says about the current pack. */
    private record Source(String downloadUrl, long size, String sha1) {}

    private final Path gameDir;
    private final PackConfig cfg;
    private final HttpFetch http;

    PackUpdater(Path gameDir, PackConfig cfg, HttpFetch http) {
        this.gameDir = gameDir;
        this.cfg = cfg;
        this.http = http;
    }

    Outcome run() {
        if (!cfg.enabled()) { Log.info("disabled in the config"); return Outcome.DISABLED; }
        if (cfg.manifestUrl().isBlank()) { Log.info("no manifest link configured, nothing to do"); return Outcome.NOT_CONFIGURED; }
        Path packs = gameDir.resolve("resourcepacks");
        Path target = packs.resolve(cfg.packFileName());
        Path tmp = packs.resolve(cfg.packFileName() + ".download");
        Outcome outcome;
        try {
            Files.createDirectories(packs);
            Files.deleteIfExists(tmp);
            boolean existed = Files.isRegularFile(target);
            Source src = readManifest();
            if (src.size() > http.maxBytes()) {
                throw new IOException("the pack is " + src.size() + " bytes, over the " + http.maxBytes() + " byte safety limit (raise it in the mod if the pack really grew)");
            }
            if (existed && src.sha1().equalsIgnoreCase(sha1Of(target))) {
                Log.info(cfg.packFileName() + " is up to date");
                outcome = Outcome.UP_TO_DATE;
            } else {
                Log.info((existed ? "updating " : "installing ") + cfg.packFileName() + " ...");
                HttpFetch.Downloaded d = http.download(src.downloadUrl(), tmp);
                verify(tmp, d, src);
                swap(tmp, target);
                Log.info(cfg.packFileName() + (existed ? " updated" : " installed") + " (" + d.size() + " bytes)");
                outcome = existed ? Outcome.UPDATED : Outcome.INSTALLED;
            }
        } catch (Exception e) {
            Log.error("could not check or update the pack, keeping the current one", e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            outcome = Outcome.FAILED;
        }
        if (Files.isRegularFile(target) && (cfg.keepEnabled() || outcome == Outcome.INSTALLED || outcome == Outcome.UPDATED)) {
            try {
                if (OptionsFile.ensureEnabled(gameDir.resolve("options.txt"), "file/" + cfg.packFileName())) Log.info("enabled file/" + cfg.packFileName() + " in options.txt");
            } catch (IOException e) {
                Log.error("could not enable the pack in options.txt", e);
            }
        }
        return outcome;
    }

    /** The manifest is a tiny JSON next to the zip: {"sha1": "...", "size": 123, "url": "optional zip link"}. */
    private Source readManifest() throws IOException {
        Map<String, Object> j;
        try {
            j = MiniJson.object(http.getString(LinkNormalizer.direct(cfg.manifestUrl())));
        } catch (RuntimeException e) {
            throw new IOException("unreadable manifest: " + e.getMessage());
        }
        String sha1 = MiniJson.str(j, "sha1");
        if (sha1 == null || !sha1.matches("(?i)[0-9a-f]{40}")) throw new IOException("the manifest has no valid \"sha1\"");
        String url = MiniJson.str(j, "url");
        if (url == null || url.isBlank()) url = cfg.shareUrl();
        if (url == null || url.isBlank()) throw new IOException("the manifest has no \"url\" and no zip link is configured");
        return new Source(LinkNormalizer.direct(url.trim()), MiniJson.num(j, -1, "size"), sha1);
    }

    private void verify(Path file, HttpFetch.Downloaded d, Source src) throws IOException {
        if (src.size() > 0 && d.size() != src.size()) throw new IOException("size mismatch: got " + d.size() + ", expected " + src.size());
        if (!src.sha1().equalsIgnoreCase(d.sha1())) throw new IOException("SHA-1 mismatch, the download is corrupted");
        try (ZipFile z = new ZipFile(file.toFile())) {
            if (z.getEntry("pack.mcmeta") == null) throw new IOException("the downloaded file is not a resource pack (no pack.mcmeta)");
        } catch (ZipException e) {
            throw new IOException("the downloaded file is not a zip: " + e.getMessage());
        }
    }

    private static void swap(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha1Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
