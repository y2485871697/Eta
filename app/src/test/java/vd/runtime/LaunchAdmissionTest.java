package vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * 纯 Java 覆盖最小安全启动准入策略 {@link LaunchAdmission}：不需要设备，也不启动 owner。
 * <ul>
 *   <li>标记：{@code eta-vd://session/<uuid>} 的生成与格式校验，且每次启动都不相同。</li>
 *   <li>flags 限制：只请求 {@code NEW_TASK}；请求 {@code NEW_DOCUMENT} / {@code MULTIPLE_TASK}
 *       或任何越界位都直接拒绝，绝不静默降级。</li>
 *   <li>标记身份预检：只有 data URI 与目标包<b>精确</b>相等才算命中；同包但标记不同/缺失一律不收养。</li>
 * </ul>
 */
public class LaunchAdmissionTest {
    private static final String TARGET = "com.example.target";
    private static final String OTHER = "com.example.other";

    @Test public void markerIsAnEtaVdSessionUri() {
        String marker = LaunchAdmission.newMarker();
        assertTrue(marker.startsWith("eta-vd://session/"));
        assertTrue(LaunchAdmission.isMarker(marker));
        assertTrue(marker.length() > LaunchAdmission.MARKER_PREFIX.length());
    }

    @Test public void everyLaunchGetsADistinctMarker() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            seen.add(LaunchAdmission.newMarker());
        }
        assertEquals(1000, seen.size());
    }

    @Test public void onlyWellFormedMarkersAreAccepted() {
        assertFalse(LaunchAdmission.isMarker(null));
        assertFalse(LaunchAdmission.isMarker(""));
        assertFalse(LaunchAdmission.isMarker("eta-vd://session/"));
        assertFalse(LaunchAdmission.isMarker("eta-vd://session"));
        assertFalse(LaunchAdmission.isMarker("https://example.com/session/x"));
        assertFalse(LaunchAdmission.isMarker("eta-vd://session/x\n"));
    }

    @Test public void launchFlagsAreExactlyNewTask() {
        assertEquals(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK, LaunchAdmission.admittedFlags(0));
        assertEquals(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK,
                LaunchAdmission.admittedFlags(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK));
        assertEquals(0, LaunchAdmission.LAUNCH_FLAGS & LaunchAdmission.FORBIDDEN_TASK_FLAGS);
    }

    @Test public void newDocumentAndMultipleTaskAreRefusedNotDowngraded() {
        assertRefused(LaunchAdmission.FLAG_ACTIVITY_NEW_DOCUMENT);
        assertRefused(LaunchAdmission.FLAG_ACTIVITY_MULTIPLE_TASK);
        assertRefused(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK | LaunchAdmission.FLAG_ACTIVITY_NEW_DOCUMENT);
        assertRefused(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK | LaunchAdmission.FLAG_ACTIVITY_MULTIPLE_TASK);
        // The reference launch shape this candidate exists to contrast with.
        assertRefused(LaunchAdmission.FLAG_ACTIVITY_NEW_TASK
                | LaunchAdmission.FLAG_ACTIVITY_NEW_DOCUMENT
                | LaunchAdmission.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Test public void unrelatedFlagsAreRefused() {
        assertRefused(0x04000000); // FLAG_ACTIVITY_CLEAR_TOP
        assertRefused(0x00008000); // FLAG_ACTIVITY_CLEAR_TASK
        assertRefused(0x20000000); // FLAG_ACTIVITY_SINGLE_TOP
    }

    @Test public void provenanceNeedsTheExactMarkerAndPackage() {
        String marker = LaunchAdmission.newMarker();
        assertTrue(LaunchAdmission.isProvenanceMatch(marker, TARGET, marker, TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, LaunchAdmission.newMarker(), TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, null, TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, marker, null));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, marker, OTHER));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, null, null));
    }

    @Test public void samePackageWithAForeignOrMissingMarkerIsNeverAdopted() {
        String marker = LaunchAdmission.newMarker();
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, null, TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, "", TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, "eta-vd://session/other", TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(marker, TARGET, TARGET, TARGET));
    }

    @Test public void malformedMarkerOrUnknownInputNeverMatches() {
        assertFalse(LaunchAdmission.isProvenanceMatch(null, TARGET, "eta-vd://session/x", TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch("eta-vd://session/", TARGET,
                "eta-vd://session/", TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(LaunchAdmission.newMarker(), null,
                "eta-vd://session/x", TARGET));
        assertFalse(LaunchAdmission.isProvenanceMatch(LaunchAdmission.newMarker(), "",
                "eta-vd://session/x", TARGET));
    }

    private static void assertRefused(int flags) {
        try {
            LaunchAdmission.admittedFlags(flags);
            fail("expected refusal for flags 0x" + Integer.toHexString(flags));
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }
}
