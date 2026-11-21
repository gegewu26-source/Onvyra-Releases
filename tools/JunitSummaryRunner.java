import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Source-launchable JUnit 4 entry point; uses the exported runtime without a Gradle worker. */
class JunitSummaryRunner {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("No JUnit test classes selected");
        Class<?>[] tests = new Class<?>[args.length];
        for (int i = 0; i < tests.length; i++) tests[i] = Class.forName(args[i]);
        Result result = JUnitCore.runClasses(tests);
        for (Failure failure : result.getFailures()) {
            Throwable cause = failure.getException();
            String message = String.valueOf(cause.getMessage());
            if (message.length() > 1400) message = message.substring(0, 1400) + " [message truncated]";
            System.out.println("FAIL: " + failure.getTestHeader());
            System.out.println(cause.getClass().getName() + ": " + message);
            StackTraceElement[] stack = cause.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 12); i++) System.out.println("  at " + stack[i]);
            for (Throwable nested = cause.getCause(); nested != null; nested = nested.getCause()) {
                System.out.println("Caused by " + nested.getClass().getName() + ": " + nested.getMessage());
                StackTraceElement[] nestedStack = nested.getStackTrace();
                for (int i = 0; i < Math.min(nestedStack.length, 8); i++) System.out.println("  at " + nestedStack[i]);
            }
        }
        System.out.printf("JUNIT_SUMMARY classes=%d tests=%d failures=%d ignored=%d time_ms=%d%n",
            tests.length, result.getRunCount(), result.getFailureCount(), result.getIgnoreCount(), result.getRunTime());
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}
