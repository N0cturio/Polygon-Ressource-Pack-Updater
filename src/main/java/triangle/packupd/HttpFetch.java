package triangle.packupd;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP with redirects (https only), hard timeouts, a size cap, and a SHA-1 computed while streaming to disk. */
final class HttpFetch implements AutoCloseable {
    /** What a download produced. */
    record Downloaded(long size, String sha1) {}

    /** Safety limit for one download: the pack is about 33 MB today; anything bigger than this is refused instead of filling a disk. */
    static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;

    private final HttpClient client;
    private final int requestSec, downloadSec;
    private final long maxBytes;

    HttpFetch(int connectSec, int downloadSec) {
        this(connectSec, downloadSec, DEFAULT_MAX_BYTES);
    }

    HttpFetch(int connectSec, int downloadSec, long maxBytes) {
        this.maxBytes = maxBytes;
        this.requestSec = Math.max(2, connectSec * 2);
        this.downloadSec = Math.max(10, downloadSec);
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(Math.max(1, connectSec))).build();
    }

    long maxBytes() { return maxBytes; }

    /** Only https is accepted (plain http only for the machine itself, which the tests use), so nothing travels or redirects in the clear. */
    static void requireHttps(String url) throws IOException {
        URI u;
        try { u = URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("invalid link"); }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) throw new IOException("refusing a non-https link (" + scheme + "://" + host + ")");
    }

    private HttpRequest.Builder req(String url) throws IOException {
        requireHttps(url);
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PolygonPackUPD/1.0").timeout(Duration.ofSeconds(requestSec));
    }

    String getString(String url) throws IOException {
        try {
            HttpResponse<String> r = client.send(req(url).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + hostOf(url));
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Streams the body to {@code target}; the whole transfer must finish within the download timeout. */
    Downloaded download(String url, Path target) throws IOException {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PolygonPackUPD-download"); t.setDaemon(true); return t; });
        Future<Downloaded> f = ex.submit(() -> {
            HttpResponse<InputStream> r = client.send(req(url).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + hostOf(url));
                long declared = r.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) throw new IOException("the file is " + declared + " bytes, over the " + maxBytes + " byte safety limit");
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                long n = 0;
                try (DigestInputStream in = new DigestInputStream(body, md); var out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        n += k;
                        if (n > maxBytes) throw new IOException("response larger than the " + maxBytes + " byte safety limit");
                        out.write(buf, 0, k);
                    }
                }
                return new Downloaded(n, HexFormat.of().formatHex(md.digest()));
            }
        });
        try {
            return f.get(downloadSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException("download timed out after " + downloadSec + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } finally {
            ex.shutdownNow();
        }
    }

    /** Releases the HTTP client (its selector thread) as soon as the check is over, so nothing of this mod stays alive once the game starts. */
    @Override
    public void close() { client.close(); }

    static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return "?"; }
    }
}
