package transferstation.transferstation_whimsicalideas;

public class DebugConfig {
    private static boolean strictParsing = false;
    private static boolean debugLogging = false;

    public static boolean isStrictParsing() {
        return strictParsing;
    }

    public static void setStrictParsing(boolean v) {
        strictParsing = v;
    }

    public static boolean isDebugLogging() {
        return debugLogging;
    }

    public static void setDebugLogging(boolean v) {
        debugLogging = v;
    }

    public static void toggleStrictParsing() {
        strictParsing = !strictParsing;
    }

    public static void toggleDebugLogging() {
        debugLogging = !debugLogging;
    }

    public static String getStatus() {
        return "Strict parsing: " + (strictParsing ? "§aON" : "§7OFF")
            + " §r| Debug logging: " + (debugLogging ? "§aON" : "§7OFF");
    }
}
