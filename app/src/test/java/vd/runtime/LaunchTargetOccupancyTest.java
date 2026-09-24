package vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class LaunchTargetOccupancyTest {
    private static final String TARGET = "com.example.target";
    private static LaunchTargetOccupancy.Root root(int id, String base, String baseActivity,
            String top, String real, boolean fieldsKnown, int activityCount,
            boolean childrenKnown, boolean foreignChild) {
        return new LaunchTargetOccupancy.Root(id, base, baseActivity, top, real, null,
                fieldsKnown, activityCount, childrenKnown, foreignChild);
    }
    private static LaunchTargetOccupancy.Decision decide(LaunchTargetOccupancy.Root root,
            LaunchTargetOccupancy.Recent... recents) {
        return LaunchTargetOccupancy.decide(TARGET, Collections.singletonList(root),
                Arrays.asList(recents));
    }

    @Test public void provenEmptyShellDoesNotBlockUnrelatedTarget() {
        // childTaskIds may contain the root's own id; foreignChild=false represents no other task.
        assertFalse(decide(root(6, null, null, null, null, true, 0, true, false)).rejects());
    }
    @Test public void activeTargetInAnyComponentRefusesEvenWithZeroActivities() {
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(6, null, null, TARGET, null, true, 0, true, false)).code);
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(7, "other.app", null, null, TARGET, true, 1, true, false)).code);
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(8, TARGET, null, null, null, false, -1, false, true)).code);
    }
    @Test public void recentTargetRefusesEvenWhenRootInventoryContainsAnUnknown() {
        assertEquals(LaunchTargetOccupancy.RECENT,
                decide(root(3, null, null, null, null, false, -1, false, true),
                        new LaunchTargetOccupancy.Recent(null, null, null, TARGET, null, true)).code);
    }
    @Test public void unknownInventoryAndNonemptyShellNeverClear() {
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, null, Collections.emptyList()).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, Collections.emptyList(), Collections.emptyList()).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, Collections.singletonList(
                        root(6, "other.app", null, null, null, true, 1, true, false)), null).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 1, true, false)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0, false, false)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0, true, true)).code);
    }
    @Test public void aRecentTargetStillWinsWhenRootListIsEmpty() {
        assertEquals(LaunchTargetOccupancy.RECENT,
                LaunchTargetOccupancy.decide(TARGET, Collections.emptyList(),
                        Collections.singletonList(new LaunchTargetOccupancy.Recent(
                                null, null, null, TARGET, null, true))).code);
    }
    @Test public void aKnownNonTargetTopActivityIdentifiesTheRoot() {
        assertFalse(decide(root(6, null, null, "other.app", null, true, 1, true, false)).rejects());
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, false, 0, true, false)).code);
    }
    @Test public void rootChildMarkersAreNotMistakenForASecondTask() {
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[0]));
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[]{6}));
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[]{-1, 6}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, new int[]{6, 7}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(-1, new int[]{-1}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, null));
    }
    @Test public void identifiedOtherIsClearButUnidentifiedRecentIsNot() {
        LaunchTargetOccupancy.Root other=root(9, "other.app", "other.app", null, null,
                true, 1, true, false);
        assertFalse(decide(other, new LaunchTargetOccupancy.Recent("other.app", null,
                null, null, null, true)).rejects());
        assertTrue(decide(other, new LaunchTargetOccupancy.Recent(null, null,
                null, null, null, true)).rejects());
    }
}
