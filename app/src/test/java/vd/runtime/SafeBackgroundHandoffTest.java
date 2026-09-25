package vd.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class SafeBackgroundHandoffTest {
    static final class Fake implements SafeBackgroundHandoff.Backend {
        final List<String> events = new ArrayList<String>();
        final List<Integer> destinations = new ArrayList<Integer>();
        boolean unavailable, applyThrows, destinationUnknown, focusAfterMove, stageOccupied;
        boolean hideThrows, createThrows;
        int moved, applies, deletes, focusRejections;
        int sourceReads, sourceLostOnRead, destinationReads, destinationLostOnRead, failApplyId;
        HandoffProgress observedProgress;
        final Object staging = new Object();
        @Override public void preflight() throws Exception {
            events.add("preflight");
            if (unavailable) throw new NoSuchMethodException("reparent");
        }
        @Override public Object createStaging() {
            events.add("create");
            if (createThrows) throw new IllegalStateException("create reply lost");
            return staging;
        }
        @Override public void checkStaging(Object value) {
            assertSame(staging, value);
            events.add("stage");
            if (moved != 0 && stageOccupied) throw new IllegalStateException("occupied");
        }
        @Override public void checkSource(int id) {
            events.add("source" + id);
            if (++sourceReads == sourceLostOnRead) throw new IllegalStateException("identity lost");
        }
        @Override public void checkDestination(int id) {
            events.add("destination" + id);
            if (++destinationReads == destinationLostOnRead || destinationUnknown) {
                throw new IllegalStateException("identity changed");
            }
            assertTrue(destinations.contains(id));
        }
        @Override public void checkFocus() throws FocusWitness.Rejected {
            events.add("focus");
            if (moved != 0 && focusAfterMove) {
                focusRejections++;
                throw new FocusWitness.Rejected(FocusWitness.Reason.TASK_CHANGED);
            }
        }
        @Override public void hide(int id) {
            events.add("hide" + id);
            if (observedProgress != null) {
                assertTrue(observedProgress.snapshot().mutationAttemptedTaskIds.contains(id));
            }
            if (hideThrows) throw new IllegalStateException("private-token: hide reply lost");
        }
        @Override public void relocateAndRestore(int id, Object value) {
            assertSame(staging, value);
            events.add("apply" + id);
            applies++;
            moved = id; // Simulate an IPC that committed even if its reply subsequently fails.
            destinations.add(id);
            if (applyThrows || failApplyId == id) throw new IllegalStateException("reply lost");
        }
        @Override public void deleteStaging(Object value) {
            assertSame(staging, value);
            events.add("delete");
            deletes++;
        }
    }

    @Test public void taskEightFocusFailureReportsRelocatedButNotCompleted() throws Exception {
        Fake fake = new Fake();
        fake.focusAfterMove = true;
        HandoffProgress progress = new HandoffProgress();
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, progress);
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals(OwnerHandoff.HANDOFF_UNCERTAIN, failure.code);
        assertFalse(failure.retryable());
        assertTrue(failure.sideEffectsAttempted());
        assertEquals("move:8:Focus_TASK_CHANGED;moved=[] removed=[]", failure.getMessage());
        assertEquals(Collections.singletonList(8), failure.progress().mutationAttemptedTaskIds);
        assertEquals(Collections.singletonList(8), failure.progress().relocatedTaskIds);
        assertTrue(failure.progress().completedTaskIds.isEmpty());
        // Exercise the dispatcher encoder with the actual thrown failure, not a synthetic summary.
        JSONObject wire = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_HANDOFF, failure);
        assertTrue(wire.getBoolean("sideEffectsAttempted"));
        assertEquals("move:8", wire.getString("handoffPhase"));
        assertEquals("[8]", wire.getJSONArray("attemptedTaskIds").toString());
        assertEquals("[8]", wire.getJSONArray("relocatedTaskIds").toString());
        assertEquals("[]", wire.getJSONArray("completedTaskIds").toString());
        assertEquals(1, fake.applies);
        assertEquals(0, fake.deletes);
        assertEquals(1, fake.focusRejections); // No sampling to turn a mismatch into success.
        assertFalse(fake.events.contains("source9"));
        assertEquals("destination8", fake.events.get(fake.events.size() - 2));
        assertEquals("focus", fake.events.get(fake.events.size() - 1));
        int events = fake.events.size();
        assertFalse(assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Collections.singletonList(8))).retryable());
        assertFalse(assertThrows(OwnerHandoff.HandoffFailure.class, handoff::preflight).retryable());
        assertEquals(events, fake.events.size()); // No second migration or compensating cleanup.
    }

    @Test public void unavailableApiFailsBeforeAnySideEffect() {
        Fake fake = new Fake();
        fake.unavailable = true;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                handoff::preflight);
        assertEquals(OwnerHandoff.HANDOFF_PREFLIGHT_FAILED, failure.code);
        assertTrue(failure.retryable());
        assertFalse(failure.sideEffectsAttempted());
        assertTrue(failure.progress().mutationAttemptedTaskIds.isEmpty());
        assertEquals(Collections.singletonList("preflight"), fake.events);
    }

    @Test public void successfulSequenceChecksBeforeMutationAndDeletesOnlyAfterCompletion() throws Exception {
        Fake fake = new Fake();
        HandoffProgress progress = new HandoffProgress();
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, progress);
        handoff.preflight();
        handoff.move(Collections.singletonList(8));
        assertEquals(Arrays.asList("preflight", "focus", "create", "focus",
                "source8", "focus", "stage", "hide8", "source8", "focus",
                "stage", "apply8", "destination8", "focus", "stage", "destination8",
                "focus", "destination8", "delete", "focus"), fake.events);
        assertEquals(Collections.singletonList(8), progress.snapshot().completedTaskIds);
        assertEquals(1, fake.applies);
        assertEquals(1, fake.deletes);
    }

    @Test public void ambiguousApplyNeverInfersRelocationOrAttemptsCleanup() throws Exception {
        Fake fake = new Fake();
        fake.applyThrows = true;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals(Collections.singletonList(8), failure.progress().mutationAttemptedTaskIds);
        assertTrue(failure.progress().relocatedTaskIds.isEmpty());
        assertTrue(failure.progress().completedTaskIds.isEmpty());
        assertFalse(failure.retryable());
        assertEquals(1, fake.applies);
        assertEquals(0, fake.deletes);
        assertFalse(fake.events.contains("destination8"));
    }

    @Test public void ambiguousHideRecordsAttemptBeforeMutationWithoutLeakingCause() throws Exception {
        Fake fake = new Fake();
        fake.hideThrows = true;
        fake.observedProgress = new HandoffProgress();
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, fake.observedProgress);
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Collections.singletonList(8)));
        assertEquals("hide:8", failure.phase());
        assertEquals(Collections.singletonList(8), failure.progress().mutationAttemptedTaskIds);
        assertTrue(failure.progress().relocatedTaskIds.isEmpty());
        assertTrue(failure.progress().completedTaskIds.isEmpty());
        assertEquals(0, fake.applies);
        assertEquals(0, fake.deletes);
        JSONObject wire = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_HANDOFF, failure);
        assertTrue(wire.getBoolean("sideEffectsAttempted"));
        assertEquals("[8]", wire.getJSONArray("attemptedTaskIds").toString());
        assertFalse(wire.toString().contains("private-token"));
        assertFalse(wire.getBoolean("retryable"));
    }

    @Test public void lostSourceIdentityBeforeOrAfterHideStopsAllFurtherMutation() throws Exception {
        for (int lostRead : new int[]{1, 2}) {
            Fake fake = new Fake();
            fake.sourceLostOnRead = lostRead;
            SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
            handoff.preflight();
            OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                    () -> handoff.move(Arrays.asList(8, 9)));
            assertEquals(lostRead == 1 ? "before:8" : "hide:8", failure.phase());
            assertEquals(lostRead == 1 ? Collections.emptyList() : Collections.singletonList(8),
                    failure.progress().mutationAttemptedTaskIds);
            assertTrue(failure.progress().relocatedTaskIds.isEmpty());
            assertTrue(failure.progress().completedTaskIds.isEmpty());
            assertFalse(failure.retryable());
            assertEquals(0, fake.applies);
            assertEquals(0, fake.deletes);
            assertFalse(fake.events.contains("source9"));
        }
    }

    @Test public void unverifiedDestinationIsNotReportedAsRelocated() throws Exception {
        Fake fake = new Fake();
        fake.destinationUnknown = true;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Collections.singletonList(8)));
        assertTrue(failure.progress().relocatedTaskIds.isEmpty());
        assertFalse(failure.retryable());
        assertEquals(0, fake.deletes);
        assertEquals("destination8", fake.events.get(fake.events.size() - 1));
    }

    @Test public void lostDestinationDuringValidationDoesNotRecordCompletionOrDelete() throws Exception {
        Fake fake = new Fake();
        fake.destinationLostOnRead = 2;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals("complete:8", failure.phase());
        assertEquals(Collections.singletonList(8), failure.progress().relocatedTaskIds);
        assertTrue(failure.progress().completedTaskIds.isEmpty());
        assertFalse(fake.events.contains("source9"));
        assertEquals(1, fake.applies);
        assertEquals(0, fake.deletes);
    }

    @Test public void laterAmbiguousApplyPreservesEarlierCompletedTask() throws Exception {
        Fake fake = new Fake();
        fake.failApplyId = 9;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals("move:9", failure.phase());
        assertEquals(Arrays.asList(8, 9), failure.progress().mutationAttemptedTaskIds);
        assertEquals(Collections.singletonList(8), failure.progress().relocatedTaskIds);
        assertEquals(Collections.singletonList(8), failure.progress().completedTaskIds);
        assertEquals(2, fake.applies);
        assertEquals(0, fake.deletes);
        assertFalse(fake.events.contains("destination9"));
    }

    @Test public void lostEarlierDestinationBeforeCleanupPreservesHistoryButDoesNotDelete() throws Exception {
        Fake fake = new Fake();
        fake.destinationLostOnRead = 5; // Two proofs per task, then recheck task 8 before cleanup.
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals("staging:delete", failure.phase());
        assertEquals(Arrays.asList(8, 9), failure.progress().completedTaskIds);
        assertFalse(failure.retryable());
        assertEquals(2, fake.applies);
        assertEquals(0, fake.deletes);
        assertEquals("destination8", fake.events.get(fake.events.size() - 1));
    }

    @Test public void ambiguousStagingCreationHasSideEffectsButNoSelectedTaskAttempts() throws Exception {
        Fake fake = new Fake();
        fake.createThrows = true;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Collections.singletonList(8)));
        assertEquals("staging:create", failure.phase());
        assertTrue(failure.sideEffectsAttempted());
        assertTrue(failure.progress().mutationAttemptedTaskIds.isEmpty());
        assertEquals(0, fake.applies);
        assertEquals(0, fake.deletes);
        assertEquals(Arrays.asList("preflight", "focus", "create"), fake.events);
    }

    @Test public void occupiedStagingAfterMoveStopsBeforeCompletionAndDeletion() throws Exception {
        Fake fake = new Fake();
        fake.stageOccupied = true;
        SafeBackgroundHandoff handoff = new SafeBackgroundHandoff(fake, new HandoffProgress());
        handoff.preflight();
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class,
                () -> handoff.move(Arrays.asList(8, 9)));
        assertEquals(Collections.singletonList(8), failure.progress().relocatedTaskIds);
        assertTrue(failure.progress().completedTaskIds.isEmpty());
        assertFalse(failure.retryable());
        assertEquals(1, fake.applies);
        assertEquals(0, fake.deletes);
    }
}
