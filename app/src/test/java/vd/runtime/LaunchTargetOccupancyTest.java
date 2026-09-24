package vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class LaunchTargetOccupancyTest {
    private static final String TARGET = "com.example.target";
    /** Package of the owner app itself, as observed in the real-device root #249 / #242 evidence. */
    private static final String ETA = "io.github.mangi.eta";
    private static LaunchTargetOccupancy.Root root(int id, String base, String baseActivity,
            String top, String real, boolean fieldsKnown, int activityCount,
            boolean childIdsKnown, int[] childIds, boolean childNamesKnown, String[] childNames) {
        return root(id, base, baseActivity, top, real, fieldsKnown, activityCount,
                childIdsKnown, childIds, childNamesKnown, childNames, false, false);
    }
    private static LaunchTargetOccupancy.Root root(int id, String base, String baseActivity,
            String top, String real, boolean fieldsKnown, int activityCount,
            boolean childIdsKnown, int[] childIds, boolean childNamesKnown, String[] childNames,
            boolean emptyOrganizerProven, boolean organizerEvidenceValid) {
        return new LaunchTargetOccupancy.Root(id, base, baseActivity, top, real, null,
                fieldsKnown, activityCount, childIdsKnown, childIds, childNamesKnown, childNames,
                emptyOrganizerProven, organizerEvidenceValid);
    }
    /** A root whose child arrays are readable and empty (no nested tasks). */
    private static LaunchTargetOccupancy.Root plain(int id, String base, String baseActivity,
            String top, String real, boolean fieldsKnown, int activityCount) {
        return root(id, base, baseActivity, top, real, fieldsKnown, activityCount,
                true, new int[0], true, new String[0]);
    }
    private static LaunchTargetOccupancy.Decision decide(LaunchTargetOccupancy.Root root,
            LaunchTargetOccupancy.Recent... recents) {
        return LaunchTargetOccupancy.decide(TARGET, Collections.singletonList(root),
                Arrays.asList(recents));
    }

    @Test public void provenEmptyShellDoesNotBlockUnrelatedTarget() {
        // A self marker childTaskIds entry (the root's own id, or -1) is not a foreign task.
        assertFalse(decide(plain(6, null, null, null, null, true, 0)).rejects());
        assertFalse(decide(root(6, null, null, null, null, true, 0, true,
                new int[]{6}, true, new String[]{null})).rejects());
        assertFalse(decide(root(6, null, null, null, null, true, 0, true,
                new int[]{-1, 6}, true, new String[]{null, null})).rejects());
    }
    @Test public void activeTargetInAnyComponentRefusesEvenWithZeroActivities() {
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(plain(6, null, null, TARGET, null, true, 0)).code);
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(plain(7, "other.app", null, null, TARGET, true, 1)).code);
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(plain(8, TARGET, null, null, null, false, -1)).code);
    }
    @Test public void identifiedHomeRootWithKnownNonTargetChildIsClear() {
        // Mirrors the observed root #1: a HOME root whose launcher child is not the target.
        assertFalse(decide(root(1, "com.bbk.launcher2", "com.bbk.launcher2", "com.bbk.launcher2",
                "com.bbk.launcher2", true, 1, true, new int[]{2}, true,
                new String[]{"com.bbk.launcher2"})).rejects());
    }
    @Test public void targetChildTaskRefuses() {
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(1, "com.bbk.launcher2", null, "com.bbk.launcher2", null, true, 1,
                        true, new int[]{2}, true, new String[]{TARGET})).code);
    }
    @Test public void emptyOrganizerWithoutChildIdentityNeverClears() {
        // Mirrors the observed root #3: an empty organizer whose child tasks cannot be named and
        // whose emptiness was not proven by a TaskOrganizer enumeration.
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(3, null, null, null, null, true, 0, true, new int[]{4, 5}, true,
                        new String[]{null, null})).code);
    }
    @Test public void emptyOrganizerWithIdentifiedNonTargetChildrenIsClear() {
        assertFalse(decide(root(3, null, null, null, null, true, 0, true, new int[]{4, 5}, true,
                new String[]{"com.other.one", "com.other.two"})).rejects());
    }
    @Test public void organizerEvidenceClearsUnnamedEmptyChildren() {
        // Mirrors root #3: an identity-free empty organizer whose child tasks the platform did not
        // name. Cleared only when the TaskOrganizer enumeration matched the child ids exactly.
        assertFalse(decide(root(3, null, null, null, null, true, 0, true, new int[]{4, 5}, true,
                new String[]{null, null}, true, true)).rejects());
        // The raw childTaskNames array may be entirely absent on the same device.
        assertFalse(decide(root(3, null, null, null, null, true, 0, true, new int[]{4, 5}, false,
                null, true, true)).rejects());
    }
    @Test public void organizerEvidenceBooleansAreRequired() {
        // emptyOrganizerProven without valid evidence is not trusted.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(3, null, null, null, null, true, 0,
                true, new int[]{4, 5}, true, new String[]{null, null}, true, false)).code);
        // Valid evidence without a positive proof stays unknown.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(3, null, null, null, null, true, 0,
                true, new int[]{4, 5}, true, new String[]{null, null}, false, true)).code);
    }
    @Test public void missingOrganizerProofForUnnamedForeignChildStaysUnknown() {
        // Mirrors root #1 with an unnamed launcher child and no organizer evidence.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(1, "com.bbk.launcher2", null,
                "com.bbk.launcher2", null, true, 1, true, new int[]{2}, true,
                new String[]{null})).code);
    }
    @Test public void selfMarkerChildWithoutRawNamesIsNotMisrejected() {
        // root #6: childTaskIds={6} (and/or -1) is the root's own marker, so a missing childTaskNames
        // raw array must not turn it into an unknown child.
        // The collector must first normalize a genuinely absent raw array to a parallel
        // all-null array. A bare policy snapshot with missing names remains unknown.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(6, null, null, null, null,
                true, 0, true, new int[]{6}, false, null)).code);
        assertFalse(decide(root(6, null, null, null, null, true, 0, true,
                new int[]{6}, true, new String[]{null})).rejects());
        assertFalse(decide(root(6, null, null, null, null, true, 0, true,
                new int[]{-1, 6}, true, new String[]{null, null})).rejects());
        // A real foreign child id without a name stays unknown.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(6, null, null, null, null, true, 0,
                true, new int[]{6, 7}, false, null)).code);
    }
    @Test public void malformedChildIdsNeverProveAnEmptyShell() {
        int[][] bad = {new int[]{-2}, new int[]{6, -2}, new int[]{0},
                new int[]{6, 6}, new int[]{-1, -1}, new int[]{7, 7}};
        for (int[] ids : bad) {
            String[] names = new String[ids.length];
            java.util.Arrays.fill(names, "com.other.app");
            assertEquals(LaunchTargetOccupancy.UNKNOWN,
                    decide(root(6, null, null, null, null, true, 0,
                            true, ids, true, names)).code);
        }
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(6, null, null, null, null,
                true, 0, true, null, true, new String[0])).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(0, null, null, null, null,
                true, 0, true, new int[0], true, new String[0])).code);
        // A reliable target identity still wins over another malformed root.
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                LaunchTargetOccupancy.decide(TARGET, Arrays.asList(
                        root(6, null, null, null, null, true, 0, true,
                                new int[]{-2}, false, null),
                        plain(7, TARGET, null, null, null, true, 1)),
                        Collections.emptyList()).code);
    }
    @Test public void organizerProofCannotContradictKnownIdentities() {
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(1, "com.bbk.launcher2", null, null, null, true, 1,
                        true, new int[]{2}, true, new String[]{null}, true, true)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(3, null, null, null, null, true, 1,
                        true, new int[]{4}, true, new String[]{null}, true, true)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(3, null, null, null, null, true, 0,
                        true, new int[]{4, 5}, true,
                        new String[]{"com.other.app", null}, true, true)).code);
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(3, null, null, null, null, true, 0,
                        true, new int[]{4, 5}, true,
                        new String[]{TARGET, null}, true, true)).code);
        assertEquals(LaunchTargetOccupancy.RECENT,
                decide(root(3, null, null, null, null, true, 0,
                        true, new int[]{-2}, false, null),
                        new LaunchTargetOccupancy.Recent(TARGET, null, null, null, null, true)).code);
    }
    @Test public void aNamedSelfMarkerIsNotAnEmptyShell() {
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(6, null, null, null, null, true, 0,
                        true, new int[]{6}, true, new String[]{TARGET})).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0,
                        true, new int[]{6}, true, new String[]{"com.other.app"})).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0,
                        true, new int[]{-1}, true, new String[]{"com.other.app"})).code);
    }
    @Test public void missingOrNonParallelChildArraysFailClosed() {
        // childTaskIds unreadable.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(3, null, null, null, null, true, 0,
                false, null, false, null)).code);
        // childTaskNames missing.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(3, null, null, null, null, true, 0,
                true, new int[]{4, 5}, false, null)).code);
        // childTaskNames not parallel with childTaskIds.
        assertEquals(LaunchTargetOccupancy.UNKNOWN, decide(root(3, null, null, null, null, true, 0,
                true, new int[]{4, 5}, true, new String[]{"com.other.one"})).code);
    }
    @Test public void recentTargetRefusesEvenWhenRootInventoryContainsAnUnknown() {
        assertEquals(LaunchTargetOccupancy.RECENT,
                decide(root(3, null, null, null, null, false, -1, false, null, false, null),
                        new LaunchTargetOccupancy.Recent(null, null, null, TARGET, null, true)).code);
    }
    @Test public void recentTargetStillWinsWhenRootListIsEmpty() {
        assertEquals(LaunchTargetOccupancy.RECENT,
                LaunchTargetOccupancy.decide(TARGET, Collections.emptyList(),
                        Collections.singletonList(new LaunchTargetOccupancy.Recent(
                                null, null, null, TARGET, null, true))).code);
    }
    @Test public void unknownInventoryAndNonemptyShellNeverClear() {
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, null, Collections.emptyList()).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, Collections.emptyList(), Collections.emptyList()).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                LaunchTargetOccupancy.decide(TARGET, Collections.singletonList(
                        plain(6, "other.app", null, null, null, true, 1)), null).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(plain(6, null, null, null, null, true, 1)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0, false, null, false, null)).code);
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(6, null, null, null, null, true, 0, true, new int[]{7}, false, null)).code);
    }
    @Test public void aKnownNonTargetTopActivityIdentifiesTheRoot() {
        assertFalse(decide(plain(6, null, null, "other.app", null, true, 1)).rejects());
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(plain(6, null, null, null, null, false, 0)).code);
    }
    @Test public void rootChildMarkersAreNotMistakenForASecondTask() {
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[0]));
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[]{6}));
        assertFalse(LaunchTargetOccupancy.hasForeignChild(6, new int[]{-1, 6}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, new int[]{6, 7}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, new int[]{-2}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, new int[]{0}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(-1, new int[]{-1}));
        assertTrue(LaunchTargetOccupancy.hasForeignChild(6, null));
    }
    @Test public void identifiedOtherIsClearButUnidentifiedRecentIsNot() {
        LaunchTargetOccupancy.Root other=plain(9, "other.app", "other.app", null, null, true, 1);
        assertFalse(decide(other, new LaunchTargetOccupancy.Recent("other.app", null,
                null, null, null, true)).rejects());
        assertTrue(decide(other, new LaunchTargetOccupancy.Recent(null, null,
                null, null, null, true)).rejects());
    }
    @Test public void realDeviceSelfMarkerEchoingOwnIdentityIsClear() {
        // Mirrors real-device root #249: base/baseActivity/topActivity/realActivity are all the
        // root's own package and the self-marker child slot repeats that same package, so the root
        // is not the target and a launch of another package must not be refused here.
        assertFalse(decide(root(249, ETA, ETA, ETA, ETA, true, 1, true,
                new int[]{249}, true, new String[]{ETA})).rejects());
        // Mirrors a root like #242 whose readable identity fields are a subset: the echo must still
        // match every non-empty identity field.
        assertFalse(decide(root(242, ETA, null, ETA, null, true, 2, true,
                new int[]{242}, true, new String[]{ETA})).rejects());
    }
    @Test public void selfMarkerNameMustEchoEveryReadableIdentity() {
        // base=Eta but topActivity=other: the self-marker name contradicts one identity field, so the
        // root is not clearable even though it matches the base.
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(242, ETA, ETA, "com.other.app", ETA, true, 1, true,
                        new int[]{242}, true, new String[]{ETA})).code);
        // A self-marker name on an identity-free root has no readable identity to echo.
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(242, null, null, null, null, true, 0, true,
                        new int[]{242}, true, new String[]{ETA})).code);
    }
    @Test public void selfMarkerNameEqualToTargetStillRefuses() {
        assertEquals(LaunchTargetOccupancy.ACTIVE,
                decide(root(249, ETA, ETA, ETA, ETA, true, 1, true,
                        new int[]{249}, true, new String[]{TARGET})).code);
    }
    @Test public void minusOneMarkerNameIsNeverAnEcho() {
        // The -1 non-task marker never names a task, so any name on it is a contradiction, even when
        // the name matches the root's own identity.
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(249, ETA, ETA, ETA, ETA, true, 1, true,
                        new int[]{-1}, true, new String[]{ETA})).code);
    }
    @Test public void selfMarkerEchoDoesNotClearAnUnnamedForeignChild() {
        // The echo only explains the root's own marker; an unnamed foreign child still fails closed.
        assertEquals(LaunchTargetOccupancy.UNKNOWN,
                decide(root(249, ETA, ETA, ETA, ETA, true, 1, true,
                        new int[]{249, 250}, true, new String[]{ETA, null})).code);
        // A readable, identified non-target foreign child alongside the echo is still clear.
        assertFalse(decide(root(249, ETA, ETA, ETA, ETA, true, 1, true,
                new int[]{249, 250}, true, new String[]{ETA, "com.other.app"})).rejects());
    }
}
