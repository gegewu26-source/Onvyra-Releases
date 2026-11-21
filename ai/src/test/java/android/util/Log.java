package android.util;

/**
 * JVM-only logging stub for provider HTTP integration tests.
 *
 * Only Android logging is replaced: network calls, stream parsing, cancellation,
 * and all other Android APIs keep their normal test behavior. This class lives
 * in src/test and is never packaged into the Android application.
 */
public final class Log {
    private Log() {}

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int priority, String tag, String message) { return 0; }
    public static String getStackTraceString(Throwable error) {
        if (error == null) return "";
        java.io.StringWriter result = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(result));
        return result.toString();
    }
    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
}
