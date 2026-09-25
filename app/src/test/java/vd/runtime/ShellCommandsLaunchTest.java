package vd.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * 纯 Java 覆盖启动 argv 构造与 flags 约束：不执行任何 shell，也不读取设备。
 *
 * <p>启动标记（{@code eta-vd://session/...}）必须作为一个字面参数出现在 argv 中，
 * 且 {@code NEW_DOCUMENT} / {@code MULTIPLE_TASK} 绝不出现在 {@code -f} 值里。
 */
public class ShellCommandsLaunchTest {
    private static final int DISPLAY = 5;
    private static final String PACKAGE = "com.example.target";
    private static final String COMPONENT = "com.example.target/.MainActivity";
    private static final String ACTION = "android.intent.action.MAIN";
    private static final int FLAGS = LaunchAdmission.FLAG_ACTIVITY_NEW_TASK;

    @Test public void explicitComponentLaunchCarriesTheMarkerAsOneLiteralArgument() {
        String marker = LaunchAdmission.newMarker();
        String[] argv = ShellCommands.amStartArgv(DISPLAY, null, COMPONENT, null,
                Collections.<String>emptyList(), FLAGS, marker);
        assertArrayEquals(new String[] {
                "/system/bin/am", "start", "--display", "5", "-n", COMPONENT,
                "-f", Integer.toString(FLAGS), "-d", marker}, argv);
        assertEquals(1, count(argv, "-d"));
        assertEquals(1, count(argv, "-f"));
    }

    @Test public void actionLaunchAppendsMarkerAfterTheTrailingPackage() {
        String marker = LaunchAdmission.newMarker();
        List<String> categories = Arrays.asList("android.intent.category.LAUNCHER");
        String[] argv = ShellCommands.amStartArgv(DISPLAY, PACKAGE, null, ACTION, categories,
                FLAGS, marker);
        assertArrayEquals(new String[] {
                "/system/bin/am", "start", "--display", "5", "-a", ACTION,
                "-c", "android.intent.category.LAUNCHER", "-f", Integer.toString(FLAGS),
                PACKAGE, "-d", marker}, argv);
    }

    @Test public void markerIsNeverForcedWhenAbsent() {
        String[] argv = ShellCommands.amStartArgv(DISPLAY, null, COMPONENT, null,
                Collections.<String>emptyList(), FLAGS, null);
        assertEquals(0, count(argv, "-d"));
    }

    @Test public void zeroFlagsOmitTheFlagOption() {
        String[] argv = ShellCommands.amStartArgv(DISPLAY, null, COMPONENT, null,
                Collections.<String>emptyList(), 0, null);
        assertEquals(0, count(argv, "-f"));
    }

    @Test public void admittedFlagsNeverCarryNewDocumentOrMultipleTask() {
        int admitted = LaunchAdmission.admittedFlags(0);
        String[] argv = ShellCommands.amStartArgv(DISPLAY, null, COMPONENT, null,
                Collections.<String>emptyList(), admitted, LaunchAdmission.newMarker());
        String flagValue = valueOf(argv, "-f");
        assertEquals(Integer.toString(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK), flagValue);
        int parsed = Integer.parseInt(flagValue);
        assertEquals(0, parsed & LaunchAdmission.FLAG_ACTIVITY_NEW_DOCUMENT);
        assertEquals(0, parsed & LaunchAdmission.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Test public void mainDisplayIsAlwaysRefused() {
        assertDisplayRefused(0);
        assertDisplayRefused(-1);
    }

    @Test public void missingLaunchTargetIsRefused() {
        try {
            ShellCommands.amStartArgv(DISPLAY, null, null, null, Collections.<String>emptyList(),
                    FLAGS, "eta-vd://session/x");
            fail("expected refusal");
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }

    @Test public void markerIsASeparateLiteralArgvElement() {
        String marker = "eta-vd://session/x; rm -rf";
        String[] argv = ShellCommands.amStartArgv(DISPLAY, null, COMPONENT, null,
                Collections.<String>emptyList(), FLAGS, marker);
        assertTrue(argv[argv.length - 2].equals("-d"));
        assertTrue(argv[argv.length - 1].equals(marker));
    }

    private static int count(String[] argv, String token) {
        int n = 0;
        for (String value : argv) {
            if (token.equals(value)) {
                n++;
            }
        }
        return n;
    }

    private static String valueOf(String[] argv, String token) {
        for (int i = 0; i + 1 < argv.length; i++) {
            if (token.equals(argv[i])) {
                return argv[i + 1];
            }
        }
        return null;
    }

    private static void assertDisplayRefused(int displayId) {
        try {
            ShellCommands.amStartArgv(displayId, null, COMPONENT, null,
                    Collections.<String>emptyList(), FLAGS, null);
            fail("expected refusal for display " + displayId);
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }
}
