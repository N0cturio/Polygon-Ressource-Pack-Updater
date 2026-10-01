package triangle.packupd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits the game's options.txt to enable a resource pack. It runs before Minecraft reads the file, so the game simply starts with the pack
 * selected. The pack id is "file/&lt;zip name&gt;". A new entry goes last (last = highest priority). Everything else in the file is kept.
 */
final class OptionsFile {
    /** Data version of Minecraft 1.21.1: a freshly created options.txt must carry it or the game would run its old-version data fixers. */
    static final int DATA_VERSION_1_21_1 = 3955;
    private static final String KEY = "resourcePacks:";
    private static final Pattern STR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private OptionsFile() {}

    /** @return true when the file was changed */
    static boolean ensureEnabled(Path file, String packId) throws IOException {
        boolean existed = Files.isRegularFile(file);
        String text = existed ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);

        int idx = -1;
        for (int k = 0; k < lines.size(); k++) {
            if (lines.get(k).startsWith(KEY)) { idx = k; break; }
        }
        if (idx >= 0) {
            List<String> packs = parseArray(lines.get(idx).substring(KEY.length()));
            if (packs.contains(packId)) return false;
            packs.add(packId);
            lines.set(idx, KEY + toArray(packs));
        } else {
            if (lines.isEmpty()) lines.add("version:" + DATA_VERSION_1_21_1);
            lines.add(KEY + toArray(new ArrayList<>(List.of("vanilla", packId))));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, String.join(nl, lines) + nl, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    static List<String> parseArray(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = STR.matcher(json);
        while (m.find()) out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return out;
    }

    static String toArray(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int k = 0; k < items.size(); k++) {
            if (k > 0) b.append(',');
            b.append('"').append(items.get(k).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append(']').toString();
    }
}
