package vd.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure argv builders. No shell string is ever built: every element below becomes one literal
 * argument of {@link ProcessBuilder}.
 *
 * <p>The display id is always an explicit argument and must never be {@code 0}. A virtual display
 * is owned by a secondary display id, so a mistaken {@code 0} can never move the user's foreground
 * task onto main; callers get an {@link IllegalArgumentException} instead of a silent fallback.
 */
final class ShellCommands {
    static final String AM = "/system/bin/am";
    static final String INPUT = "/system/bin/input";

    private static final String ACTION_MAIN = "android.intent.action.MAIN";
    private static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    private ShellCommands() {
    }

    /** {@code am start --display <id> ...} selecting exactly one of component, action or launcher. */
    static String[] amStartArgv(int displayId, String packageName, String component, String action,
            List<String> categories, int flags) {
        requireSecondaryDisplay(displayId);
        if (packageName == null && component == null && action == null) {
            throw new IllegalArgumentException("launch target");
        }
        List<String> argv = new ArrayList<String>();
        argv.add(AM);
        argv.add("start");
        argv.add("--display");
        argv.add(Integer.toString(displayId));
        if (component != null) {
            argv.add("-n");
            argv.add(component);
        } else if (action != null) {
            argv.add("-a");
            argv.add(action);
            if (categories != null) {
                for (int i = 0; i < categories.size(); i++) {
                    argv.add("-c");
                    argv.add(categories.get(i));
                }
            }
        } else {
            argv.add("-a");
            argv.add(ACTION_MAIN);
            argv.add("-c");
            argv.add(CATEGORY_LAUNCHER);
        }
        if (flags != 0) {
            argv.add("-f");
            argv.add(Integer.toString(flags));
        }
        if (component == null && packageName != null) {
            argv.add(packageName);
        }
        return argv.toArray(new String[0]);
    }

    /**
     * {@code am start} with an explicit data URI used as the launch identity marker. The URI is a
     * single literal argument, so no user text can become an extra {@code am} option. A {@code null}
     * data URI leaves the base argv untouched.
     */
    static String[] amStartArgv(int displayId, String packageName, String component, String action,
            List<String> categories, int flags, String dataUri) {
        String[] base = amStartArgv(displayId, packageName, component, action, categories, flags);
        if (dataUri == null) {
            return base;
        }
        String[] withData = java.util.Arrays.copyOf(base, base.length + 2);
        withData[base.length] = "-d";
        withData[base.length + 1] = dataUri;
        return withData;
    }

    static String[] inputTapArgv(int displayId, int x, int y) {
        requireSecondaryDisplay(displayId);
        return new String[] {INPUT, "-d", Integer.toString(displayId), "tap",
                Integer.toString(x), Integer.toString(y)};
    }

    static String[] inputSwipeArgv(int displayId, int x1, int y1, int x2, int y2, long durationMs) {
        requireSecondaryDisplay(displayId);
        long duration = Math.max(1L, Math.min(durationMs, 60_000L));
        return new String[] {INPUT, "-d", Integer.toString(displayId), "swipe",
                Integer.toString(x1), Integer.toString(y1), Integer.toString(x2), Integer.toString(y2),
                Long.toString(duration)};
    }

    static String[] inputKeyArgv(int displayId, int keyCode) {
        requireSecondaryDisplay(displayId);
        return new String[] {INPUT, "-d", Integer.toString(displayId), "keyevent",
                Integer.toString(keyCode)};
    }

    /** Text is a single literal argument; the platform {@code input text} joins argv with spaces. */
    static String[] inputTextArgv(int displayId, String text) {
        requireSecondaryDisplay(displayId);
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text");
        }
        return new String[] {INPUT, "-d", Integer.toString(displayId), "text", text};
    }

    private static void requireSecondaryDisplay(int displayId) {
        if (displayId <= 0) {
            throw new IllegalArgumentException("displayId");
        }
    }
}
