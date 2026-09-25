package vd.runtime;

import android.content.Intent;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import vd.runtime.FocusWitnessSnapshotTest.Root;
import vd.runtime.FocusWitnessSnapshotTest.Token;
import static org.junit.Assert.*;

/** No device mutations: exercise the production query contract and its preflight consumer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class MainDisplayFocusTest {
    /** RunningTaskInfo-shaped leaf: deliberately has no RootTaskInfo child arrays. */
    public static final class Leaf {
        public int taskId, displayId = 0, userId = 0, parentTaskId = -1;
        public Object token;
        public boolean isFocused = true;
        public Intent baseIntent = new Intent().setClassName("com.example.launcher", "com.example.launcher.Main");
        Leaf(int id, Object binder) { taskId = id; token = new Token(binder); }
    }

    private static Root root(int id, Object binder, int... children) {
        Root root = new Root(binder);
        root.taskId = id;
        root.childTaskIds = children;
        root.childTaskNames = new String[children.length];
        Arrays.fill(root.childTaskNames, "com.example.launcher");
        return root;
    }

    private static Map<Integer, Object> inventory(Root... roots) {
        Map<Integer, Object> result = new LinkedHashMap<>();
        for (Root root : roots) result.put(root.taskId, root);
        return result;
    }

    private static final class Atm implements MainDisplayFocus.Atm {
        Object tasks, globalFocus;
        Exception failure;
        int taskReads, globalReads, mutations;
        Atm(Object tasks) { this.tasks = tasks; }
        public Object call(String method, Class<?>[] types, Object... args) throws Exception {
            if ("getFocusedRootTaskInfo".equals(method)) { globalReads++; return globalFocus; }
            if (!"getTasks".equals(method)) {
                mutations++;
                throw new AssertionError("Only a read-only getTasks call is permitted");
            }
            taskReads++;
            assertArrayEquals(new Class<?>[]{int.class, boolean.class, boolean.class, int.class}, types);
            assertArrayEquals(new Object[]{MainDisplayFocus.TASK_LIMIT, false, true, 0}, args);
            if (failure != null) throw failure;
            return tasks;
        }
    }

    private static final class Reader implements OwnerFocusPreflight.Source {
        final Map<Integer, Object> inventory;
        final Map<Integer, OwnerHandoff.Task> owned = new LinkedHashMap<>();
        final Atm atm;
        Object[] taskFrames;
        int reads, checks;
        Reader(Map<Integer, Object> inventory, Object tasks) throws Exception {
            this.inventory = new LinkedHashMap<>(inventory);
            Root delivery = root(20, new Object(), 20);
            delivery.displayId = 3;
            this.inventory.put(20, delivery);
            owned.put(20, new OwnerHandoff.Task(delivery));
            atm = new Atm(tasks);
            // Last input was on display 3, so the global ATM query would return this root.
            atm.globalFocus = delivery;
        }
        public void verifyDisplay() { checks++; }
        public Map<Integer, Object> roots() { reads++; return new LinkedHashMap<>(inventory); }
        public FocusWitness focusedWitness(Map<Integer, Object> current) throws Exception {
            if (taskFrames != null) atm.tasks = taskFrames[Math.min(reads - 1, taskFrames.length - 1)];
            return MainDisplayFocus.read(current, atm);
        }
        public long nanoTime() { return 0L; }
        OwnerFocusPreflight.Sample preflight() throws Exception {
            return OwnerFocusPreflight.capture(this, 3, owned, Collections.singleton(20));
        }
    }

    private static OwnerHandoff.HandoffFailure refused(Reader reader, FocusWitness.Reason reason) {
        OwnerHandoff.HandoffFailure error = assertThrows(OwnerHandoff.HandoffFailure.class, reader::preflight);
        assertEquals(OwnerHandoff.HANDOFF_PREFLIGHT_FAILED, error.code);
        assertEquals("preflight:focus", error.phase());
        assertEquals("preflight:focus:Focus_" + reason.name() + ";moved=[] removed=[]", error.getMessage());
        assertFalse(error.sideEffectsAttempted());
        assertTrue(error.retryable());
        assertEquals(0, reader.atm.globalReads);
        assertEquals(0, reader.atm.mutations);
        return error;
    }

    @Test public void virtualGlobalFocusWithReliableMainWitnessPassesFirstHandoff() throws Exception {
        Object binder = new Object();
        Root main = root(1, binder, 1), background = root(10, new Object(), 10);
        Leaf focused = new Leaf(1, binder), inactive = new Leaf(10, new Object());
        inactive.isFocused = false;
        // The first inventory/running-task entry is deliberately NOT the foreground task.
        Reader reader = new Reader(inventory(background, main), Arrays.asList(inactive, focused));
        assertEquals(3, OwnerHandoff.number(reader.atm.globalFocus, "displayId"));
        OwnerFocusPreflight.Sample sample = reader.preflight();
        assertEquals(Arrays.asList("OK", "OK"), sample.observations);
        assertEquals(2, reader.reads);
        assertEquals(2, reader.checks);
        assertEquals(2, reader.atm.taskReads);
        assertEquals(0, reader.atm.globalReads);
        assertEquals(0, reader.atm.mutations);
        // A source-display anchor can become global top focus without invalidating display 0.
        Root anchor = root(21, new Object(), 21); anchor.displayId = 3;
        reader.atm.globalFocus = anchor;
        sample.witness.checkWitness(MainDisplayFocus.read(reader.roots(), reader.atm));
        assertEquals(0, reader.atm.globalReads);
        assertEquals(0, reader.atm.mutations);
    }

    @Test public void nestedFocusedLeafUsesExactRootMembershipAndSeparateBinder() throws Exception {
        Root main = root(1, new Object(), 2, 3);
        Object leafBinder = new Object();
        Leaf leaf = new Leaf(2, leafBinder); leaf.parentTaskId = 1;
        Reader reader = new Reader(inventory(main), Collections.singletonList(leaf));
        FocusWitness witness = reader.preflight().witness;
        Leaf fresh = new Leaf(2, leafBinder); fresh.parentTaskId = 1;
        witness.checkWitness(MainDisplayFocus.capture(inventory(main), Collections.singletonList(fresh)));
        // A non-organized parent can be omitted; the inventory must still name the leaf exactly.
        leaf.parentTaskId = -1;
        assertNotNull(MainDisplayFocus.capture(inventory(main), Collections.singletonList(leaf)));
    }

    @Test public void wrongLeafAndRootUserReject() throws Exception {
        Object binder = new Object(); Root main = root(1, binder, 1);
        Leaf leaf = new Leaf(1, binder); leaf.userId = 10;
        Reader reader = new Reader(inventory(main), Collections.singletonList(leaf));
        refused(reader, FocusWitness.Reason.WRONG_USER);
        assertEquals(1, reader.atm.taskReads);
        leaf.userId = 0; main.userId = 10;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.WRONG_USER);
    }

    @Test public void wrongDisplayCannotMasqueradeAsDisplayFilteredFocus() throws Exception {
        Object binder = new Object(); Root main = root(1, binder, 1);
        Leaf leaf = new Leaf(1, binder); leaf.displayId = 3;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.WRONG_DISPLAY);
        leaf.displayId = 0; main.displayId = 2;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.WRONG_DISPLAY);
    }

    @Test public void wrongRootLeafTokenNeverPassesPairedPreflight() throws Exception {
        Root main = root(1, new Object(), 1);
        Reader reader = new Reader(inventory(main), Collections.singletonList(new Leaf(1, new Object())));
        refused(reader, FocusWitness.Reason.BINDER_CHANGED);
        assertEquals(OwnerFocusPreflight.MAX_SAMPLES, reader.atm.taskReads);
    }

    @Test public void missingMalformedAndNullBinderTokensRejectWithoutRetry() throws Exception {
        Root main = root(1, new Object(), 1);
        for (Object token : new Object[]{null, new Object(), new Token(null)}) {
            Leaf leaf = new Leaf(1, new Object()); leaf.token = token;
            Reader reader = new Reader(inventory(main), Collections.singletonList(leaf));
            refused(reader, FocusWitness.Reason.BINDER_MISSING);
            assertEquals(1, reader.atm.taskReads);
        }
    }

    @Test public void twoFocusedLeavesAndDuplicateIdsAreAmbiguous() throws Exception {
        Object binder = new Object(); Root main = root(1, binder, 1);
        Leaf a = new Leaf(1, binder), b = new Leaf(10, new Object());
        Reader reader = new Reader(inventory(main), Arrays.asList(a, b));
        refused(reader, FocusWitness.Reason.FOCUS_AMBIGUOUS);
        assertEquals(1, reader.atm.taskReads);
        refused(new Reader(inventory(main), Arrays.asList(a, a)), FocusWitness.Reason.FOCUS_AMBIGUOUS);
    }

    @Test public void twoRootsClaimingSameFocusedLeafAreAmbiguous() throws Exception {
        Root a = root(1, new Object(), 2), b = root(10, new Object(), 2);
        Leaf leaf = new Leaf(2, new Object());
        refused(new Reader(inventory(a, b), Collections.singletonList(leaf)), FocusWitness.Reason.FOCUS_AMBIGUOUS);
    }

    @Test public void noFlaggedFocusNeverSelectsFirstOrOnlyInventoryTask() throws Exception {
        Object binder = new Object(); Root main = root(1, binder, 1);
        Leaf leaf = new Leaf(1, binder); leaf.isFocused = false;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.FOCUS_MISSING);
    }

    @Test public void missingFocusCapabilityFailsClosedWithoutGlobalFallback() throws Exception {
        Root main = root(1, new Object(), 1);
        // Root is deliberately missing isFocused. No order-based fallback is allowed.
        Reader reader = new Reader(inventory(main), Collections.singletonList(main));
        refused(reader, FocusWitness.Reason.FOCUS_UNSUPPORTED);
        assertEquals(1, reader.atm.taskReads);
        reader = new Reader(inventory(main), Collections.emptyList());
        reader.atm.failure = new NoSuchMethodException("eta-vd://session/private-detail");
        OwnerHandoff.HandoffFailure error = refused(reader, FocusWitness.Reason.FOCUS_UNSUPPORTED);
        assertFalse(error.getMessage().contains("private-detail"));
        assertEquals(1, reader.atm.taskReads);
    }

    @Test public void nullWrongTypeAndFullTaskPagesAreUnreliable() throws Exception {
        Root main = root(1, new Object(), 1);
        Object[] pages = {null, new Object(), Collections.nCopies(MainDisplayFocus.TASK_LIMIT, new Leaf(1, new Object()))};
        for (Object page : pages) {
            Reader reader = new Reader(inventory(main), page);
            refused(reader, FocusWitness.Reason.FOCUS_UNREADABLE);
            assertEquals(1, reader.atm.taskReads);
        }
    }

    @Test public void invalidIdAndUnrelatedParentReject() throws Exception {
        Root main = root(1, new Object(), 2);
        Leaf leaf = new Leaf(0, new Object());
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.INVALID_TASK_ID);
        leaf.taskId = 2; leaf.parentTaskId = 10;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.LEAF_ROOT_MISMATCH);
    }

    @Test public void leafCannotBeContainerOrReuseItsParentsBinder() throws Exception {
        Object binder = new Object(); Root main = root(1, binder, 2);
        refused(new Reader(inventory(main), Collections.singletonList(new Leaf(1, binder))),
                FocusWitness.Reason.LEAF_ROOT_MISMATCH);
        Leaf leaf = new Leaf(2, binder); leaf.parentTaskId = 1;
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.LEAF_ROOT_MISMATCH);
    }

    @Test public void unknownRootStructureStillRejects() throws Exception {
        Root main = root(1, new Object(), 2); main.childTaskNames = null;
        Leaf leaf = new Leaf(2, new Object());
        refused(new Reader(inventory(main), Collections.singletonList(leaf)), FocusWitness.Reason.CHILD_NAMES_MISSING);
    }

    @Test public void foregroundLeafChangeInsideUnchangedRootRejectsAfterAnchor() throws Exception {
        Root main = root(1, new Object(), 2, 3);
        Leaf a = new Leaf(2, new Object()), b = new Leaf(3, new Object());
        a.parentTaskId = 1; b.parentTaskId = 1;
        Reader reader = new Reader(inventory(main), Collections.singletonList(a));
        FocusWitness witness = reader.preflight().witness;
        FocusWitness changed = MainDisplayFocus.capture(inventory(main), Collections.singletonList(b));
        assertFalse(witness.matches(changed));
        FocusWitness.Rejected rejection = assertThrows(FocusWitness.Rejected.class, () -> witness.checkWitness(changed));
        assertEquals(FocusWitness.Reason.TASK_CHANGED, rejection.reason);
        OwnerHandoff.HandoffFailure error = new OwnerHandoff.HandoffFailure("focus", rejection, true, "moved=[] removed=[]");
        assertEquals(OwnerHandoff.HANDOFF_UNCERTAIN, error.code);
        assertTrue(error.sideEffectsAttempted());
        assertFalse(error.retryable());
    }

    @Test public void differentMainRootAndReusedLeafIdBinderReject() throws Exception {
        Object binder = new Object(); Root a = root(1, binder, 1);
        FocusWitness witness = MainDisplayFocus.capture(inventory(a), Collections.singletonList(new Leaf(1, binder)));
        Object other = new Object(); Root b = root(10, other, 10);
        FocusWitness changed = MainDisplayFocus.capture(inventory(b), Collections.singletonList(new Leaf(10, other)));
        assertEquals(FocusWitness.Reason.TASK_CHANGED, assertThrows(FocusWitness.Rejected.class,
                () -> witness.checkWitness(changed)).reason);
        Root container = root(1, new Object(), 2);
        FocusWitness before = MainDisplayFocus.capture(inventory(container), Collections.singletonList(new Leaf(2, new Object())));
        FocusWitness after = MainDisplayFocus.capture(inventory(container), Collections.singletonList(new Leaf(2, new Object())));
        assertEquals(FocusWitness.Reason.BINDER_CHANGED, assertThrows(FocusWitness.Rejected.class,
                () -> before.checkWitness(after)).reason);
    }

    @Test public void changingLeafCannotCountAsStablePreflightWithStableRoot() throws Exception {
        Root main = root(1, new Object(), 2, 3);
        Leaf a = new Leaf(2, new Object()), b = new Leaf(3, new Object());
        Reader reader = new Reader(inventory(main), Collections.singletonList(a));
        reader.taskFrames = new Object[]{Collections.singletonList(a), Collections.singletonList(b),
                Collections.singletonList(a), Collections.singletonList(b)};
        refused(reader, FocusWitness.Reason.FOCUS_UNSTABLE);
        assertEquals(OwnerFocusPreflight.MAX_SAMPLES, reader.atm.taskReads);
    }

    @Test public void leafDataChangeAndRootOnlyDowngradeAreRejected() throws Exception {
        Root main = root(1, new Object(), 2); Object binder = new Object();
        Leaf a = new Leaf(2, binder), b = new Leaf(2, binder);
        FocusWitness before = MainDisplayFocus.capture(inventory(main), Collections.singletonList(a));
        b.baseIntent.setData(Uri.parse("eta-vd://session/private-leaf"));
        FocusWitness after = MainDisplayFocus.capture(inventory(main), Collections.singletonList(b));
        FocusWitness.Rejected error = assertThrows(FocusWitness.Rejected.class, () -> before.checkWitness(after));
        assertEquals(FocusWitness.Reason.DATA_CHANGED, error.reason);
        assertFalse(error.getMessage().contains("private-leaf"));
        FocusWitness rootOnly = FocusWitness.capture(main, main, 0, 0);
        assertEquals(FocusWitness.Reason.STRUCTURE_CHANGED, assertThrows(FocusWitness.Rejected.class,
                () -> before.checkWitness(rootOnly)).reason);
    }
}
