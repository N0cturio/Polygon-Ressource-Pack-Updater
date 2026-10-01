package triangle.packupd;

/** Console logging with a fixed prefix; Minecraft captures stdout into latest.log. */
final class Log {
    private Log() {}

    static void info(String msg) { System.out.println("[PolygonPackUPD] " + msg); }

    static void warn(String msg) { System.out.println("[PolygonPackUPD] WARN " + msg); }

    static void error(String msg, Throwable t) {
        System.out.println("[PolygonPackUPD] ERROR " + msg + (t == null ? "" : ": " + t));
    }
}
