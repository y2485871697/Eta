package vd.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class HandoffProgressTest {
    @Test public void focusFailureWireDistinguishesAttemptedRelocatedAndCompleted() throws Exception {
        HandoffProgress progress = new HandoffProgress();
        progress.attempted(7); progress.relocated(7); progress.completed(7);
        progress.attempted(8); progress.relocated(8);
        OwnerHandoff.HandoffFailure failure = new OwnerHandoff.HandoffFailure(
                "move:8", new FocusWitness.Rejected(FocusWitness.Reason.TASK_CHANGED), true,
                "moved=[7] removed=[]", progress.snapshot());
        // Later observations cannot rewrite the already captured failure history.
        progress.completed(8);
        JSONObject wire = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_HANDOFF, failure);
        assertFalse(wire.getBoolean("ok"));
        assertEquals(OwnerHandoff.HANDOFF_UNCERTAIN, wire.getString("error"));
        assertFalse(wire.getBoolean("retryable"));
        assertEquals("move:8:Focus_TASK_CHANGED;moved=[7] removed=[]", wire.getString("message"));
        assertTrue(wire.get("sideEffectsAttempted") instanceof Boolean);
        assertTrue(wire.getBoolean("sideEffectsAttempted"));
        assertTrue(wire.get("handoffPhase") instanceof String);
        assertEquals("move:8", wire.getString("handoffPhase"));
        assertEquals("[7,8]", wire.getJSONArray("attemptedTaskIds").toString());
        assertEquals("[7,8]", wire.getJSONArray("relocatedTaskIds").toString());
        assertEquals("[7]", wire.getJSONArray("completedTaskIds").toString());
        for (String name : Arrays.asList("attemptedTaskIds", "relocatedTaskIds", "completedTaskIds")) {
            assertTrue(wire.get(name) instanceof JSONArray);
            JSONArray ids = wire.getJSONArray(name);
            for (int i = 0; i < ids.length(); i++) assertTrue(ids.get(i) instanceof Integer);
        }
        JSONObject history = wire.getJSONObject("handoffProgress");
        assertEquals(3, history.length());
        assertEquals("[7,8]", history.getJSONArray("mutationAttemptedTaskIds").toString());
        assertEquals("[7,8]", history.getJSONArray("relocatedTaskIds").toString());
        assertEquals("[7]", history.getJSONArray("completedTaskIds").toString());
        assertFalse(wire.has("sourceEmpty"));
        assertFalse(wire.has("handedOff"));
        assertFalse(wire.has("keptTaskIds"));
    }

    @Test public void legacyFailureConstructorProducesEmptyHistoryAndKeepsClassification() throws Exception {
        for (boolean mutated : new boolean[]{false, true}) {
            OwnerHandoff.HandoffFailure failure = new OwnerHandoff.HandoffFailure(
                    mutated ? "anchor:launch" : "preflight:capability",
                    new NoSuchMethodException("private-token-not-a-diagnostic"), mutated,
                    "moved=[] removed=[]");
            JSONObject wire = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_HANDOFF, failure);
            assertEquals(!mutated, wire.getBoolean("retryable"));
            assertTrue(wire.get("sideEffectsAttempted") instanceof Boolean);
            assertEquals(mutated, wire.getBoolean("sideEffectsAttempted"));
            assertEquals(mutated ? "anchor:launch" : "preflight:capability",
                    wire.getString("handoffPhase"));
            for (String name : Arrays.asList("attemptedTaskIds", "relocatedTaskIds", "completedTaskIds")) {
                assertTrue(wire.get(name) instanceof JSONArray);
                assertEquals(0, wire.getJSONArray(name).length());
            }
            JSONObject history = wire.getJSONObject("handoffProgress");
            assertEquals(0, history.getJSONArray("mutationAttemptedTaskIds").length());
            assertEquals(0, history.getJSONArray("relocatedTaskIds").length());
            assertEquals(0, history.getJSONArray("completedTaskIds").length());
            assertFalse(wire.toString().contains("private-token"));
        }
    }

    @Test public void historyArraysContainOnlyBoundedUniquePositiveIntegerIds() throws Exception {
        List<Object> dirty = Arrays.<Object>asList(8, 8, null, -1, 0, "9", 10L, 11.0, true,
                "eta-vd-anchor://handoff/private", 12);
        HandoffProgress.Snapshot filtered = new HandoffProgress.Snapshot(dirty, dirty, dirty);
        assertEquals(Arrays.asList(8, 12), filtered.mutationAttemptedTaskIds);
        assertEquals(Arrays.asList(8, 12), filtered.relocatedTaskIds);
        assertEquals(Arrays.asList(8, 12), filtered.completedTaskIds);
        assertThrows(UnsupportedOperationException.class, () -> filtered.completedTaskIds.add(13));
        List<Integer> many = new ArrayList<Integer>();
        for (int id = 1; id <= 300; id++) many.add(id);
        JSONObject history = new HandoffProgress.Snapshot(many, many, many).toJson();
        for (String name : Arrays.asList("mutationAttemptedTaskIds", "relocatedTaskIds", "completedTaskIds")) {
            JSONArray ids = history.getJSONArray(name);
            assertEquals(256, ids.length());
            Set<Integer> seen = new HashSet<Integer>();
            for (int i = 0; i < ids.length(); i++) {
                assertTrue(ids.get(i) instanceof Integer);
                assertTrue(ids.getInt(i) > 0);
                assertTrue(seen.add(ids.getInt(i)));
            }
        }
        assertFalse(filtered.toJson().toString().contains("private"));
    }

    @Test public void liveRecorderRequiresOrderedStagesAndDoesNotDuplicateIds() {
        HandoffProgress progress = new HandoffProgress();
        assertThrows(IllegalStateException.class, () -> progress.attempted(0));
        assertThrows(IllegalStateException.class, () -> progress.relocated(8));
        progress.attempted(8); progress.attempted(8);
        assertThrows(IllegalStateException.class, () -> progress.completed(8));
        progress.relocated(8); progress.completed(8);
        assertEquals(Collections.singletonList(8), progress.snapshot().completedTaskIds);
        for (int id = 9; id <= 263; id++) progress.attempted(id);
        assertThrows(IllegalStateException.class, () -> progress.attempted(264));
        assertEquals(256, progress.snapshot().mutationAttemptedTaskIds.size());
    }

    @Test public void unrelatedFailureDoesNotPretendToHaveHandoffHistory() {
        JSONObject wire = OwnerCommandDispatcher.failureResponse("status",
                new OwnerException(OwnerProtocol.ERROR_INTERNAL, "example"));
        for (String name : Arrays.asList("handoffProgress", "sideEffectsAttempted", "handoffPhase",
                "attemptedTaskIds", "relocatedTaskIds", "completedTaskIds")) {
            assertFalse(wire.has(name));
        }
    }
}
