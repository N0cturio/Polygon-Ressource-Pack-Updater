package triangle.packupd;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the share links people copy from Google Drive into links that download the file itself. Anything else is left alone. */
final class LinkNormalizer {
    private static final Pattern DRIVE_PATH = Pattern.compile("/file/d/([A-Za-z0-9_-]+)");
    private static final Pattern DRIVE_ID = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");

    private LinkNormalizer() {}

    static String direct(String url) {
        if (url == null || url.isBlank()) return url;
        String host;
        try { host = URI.create(url).getHost(); } catch (IllegalArgumentException e) { return url; }
        if (host == null) return url;
        host = host.toLowerCase();
        if (host.equals("drive.google.com") || host.equals("docs.google.com")) {
            Matcher m = DRIVE_PATH.matcher(url);
            String id = m.find() ? m.group(1) : null;
            if (id == null) { Matcher q = DRIVE_ID.matcher(url); if (q.find()) id = q.group(1); }
            // confirm=t skips the "can't scan for viruses" page that appears for big files
            if (id != null) return "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
            return url;
        }
        return url;
    }
}
