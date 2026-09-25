package vd.runtime;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure unit tests for the owner's launch flags, marker argv and provenance predicate. No device,
 * no root and no shell is involved: this only exercises {@link LaunchPolicy} and {@link ShellCommands}.
 */
public class LaunchPolicyTest {
    private static final String COMPONENT = "com.example.app/.MainActivity";
    private static final int DISPLAY_ID = 7;

    @Test
    public void launchFlagsAreExactlyNewTaskAndMultipleTask() {
        assertEquals(0x10000000, LaunchPolicy.FLAG_ACTIVITY_NEW_TASK);
        assertEquals(0x08000000, LaunchPolicy.FLAG_ACTIVITY_MULTIPLE_TASK);
        assertEquals(0x00080000, LaunchPolicy.FLAG_ACTIVITY_NEW_DOCUMENT);
        // NEW_TASK | MULTIPLE_TASK, and explicitly NOT the document bit.
        assertEquals(0x18000000, LaunchPolicy.LAUNCH_FLAGS);
        assertEquals(0, LaunchPolicy.LAUNCH_FLAGS & LaunchPolicy.FLAG_ACTIVITY_NEW_DOCUMENT);
        // The owner always returns exactly the supported pair, whatever the caller restates.
        assertEquals(LaunchPolicy.LAUNCH_FLAGS, LaunchPolicy.resolveLaunchFlags(0));
        assertEquals(LaunchPolicy.LAUNCH_FLAGS,
                LaunchPolicy.resolveLaunchFlags(LaunchPolicy.FLAG_ACTIVITY_NEW_TASK));
        assertEquals(LaunchPolicy.LAUNCH_FLAGS,
                LaunchPolicy.resolveLaunchFlags(LaunchPolicy.LAUNCH_FLAGS));
    }

    @Test
    public void callerSuppliedUnsupportedFlagsFailClosed() {
        int[] unsupported = {
                LaunchPolicy.FLAG_ACTIVITY_NEW_DOCUMENT,
                LaunchPolicy.FLAG_ACTIVITY_NEW_DOCUMENT | LaunchPolicy.LAUNCH_FLAGS,
                0x00000001,
                0x08000000 | 0x00080000,
                Integer.MIN_VALUE,
                -1,
        };
        for (int flags : unsupported) {
            try {
                LaunchPolicy.resolveLaunchFlags(flags);
                fail("expected rejection for flags=0x" + Integer.toHexString(flags));
            } catch (IllegalArgumentException expected) {
                // fail closed: never silently ignore a bit the owner does not force.
            }
        }
    }

    @Test
    public void componentArgvCarriesOnly0x18000000AndAppendsMarker() {
        String marker = LaunchPolicy.MARKER_PREFIX + "11111111-2222-3333-4444-555555555555";
        String[] argv = LaunchPolicy.startArgv(DISPLAY_ID, null, COMPONENT, null,
                Collections.<String>emptyList(), marker);
        assertArrayEquals(new String[] {
                "/system/bin/am", "start", "--display", "7",
                "-n", COMPONENT,
                "-f", "402653184", // 0x18000000
                "-d", marker,
        }, argv);
        assertEquals(LaunchPolicy.LAUNCH_FLAGS, Integer.parseInt(argv[7]));
        // The document bit (0x00080000 == 524288) is never present in any argument.
        assertFalse(Arrays.asList(argv).contains("524288"));
        assertFalse(Arrays.asList(argv).contains("0x00080000"));
    }

    @Test
    public void markerIsRandomAndPrefixed() {
        String first = LaunchPolicy.newMarker();
        String second = LaunchPolicy.newMarker();
        assertTrue(first.startsWith("eta-vd://session/"));
        assertTrue(second.startsWith("eta-vd://session/"));
        assertNotEquals(first, second);
    }

    @Test
    public void blankMarkerIsRefusedInsteadOfLaunchingWithoutProvenance() {
        for (String marker : new String[] { null, "" }) {
            try {
                LaunchPolicy.startArgv(DISPLAY_ID, null, COMPONENT, null,
                        Collections.<String>emptyList(), marker);
                fail("expected rejection for marker=" + marker);
            } catch (IllegalArgumentException expected) {
                // no -d argument is ever built without a marker.
            }
        }
    }

    @Test
    public void provenanceRejectsMissingOrMismatchedMarker() {
        String marker = LaunchPolicy.MARKER_PREFIX + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertTrue(LaunchPolicy.provenanceMatches(marker, "com.example.app", marker, "com.example.app"));
        // Missing marker on either side is rejected.
        assertFalse(LaunchPolicy.provenanceMatches(null, "com.example.app", marker, "com.example.app"));
        assertFalse(LaunchPolicy.provenanceMatches(marker, "com.example.app", null, "com.example.app"));
        assertFalse(LaunchPolicy.provenanceMatches("", "com.example.app", "", "com.example.app"));
        // Data URI must match exactly, and the task must belong to the target package.
        assertFalse(LaunchPolicy.provenanceMatches(marker, "com.example.app", marker + "x", "com.example.app"));
        assertFalse(LaunchPolicy.provenanceMatches(marker, "com.example.app", marker, "other.pkg"));
        assertFalse(LaunchPolicy.provenanceMatches(marker, "com.example.app", marker, null));
        assertFalse(LaunchPolicy.provenanceMatches(marker, null, marker, "com.example.app"));
    }
}
