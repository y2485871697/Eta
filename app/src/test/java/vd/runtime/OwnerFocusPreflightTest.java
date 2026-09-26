package vd.runtime;

import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import vd.runtime.FocusWitnessSnapshotTest.Root;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class OwnerFocusPreflightTest {
    private final Root delivery;
    private final Map<Integer, OwnerHandoff.Task> owned = new LinkedHashMap<>();
    private final Set<Integer> selected = Collections.singleton(20);
    public OwnerFocusPreflightTest() throws Exception {
        delivery = new Root(new Object());
        delivery.taskId = 20; delivery.displayId = 3;
        delivery.childTaskIds = new int[]{20};
        owned.put(20, new OwnerHandoff.Task(delivery));
    }

    private final class Frame {
        final Object focus;
        final Map<Integer, Object> inventory = new LinkedHashMap<>();
        Frame(Root focus, Root listed) {
            this.focus = focus;
            inventory.put(20, delivery);
            if (listed != null) inventory.put(listed.taskId, listed);
        }
    }
    private static final class Reader implements OwnerFocusPreflight.Source {
        final List<Frame> frames;
        int reads, checks, focusReads;
        int displayFailureAt = -1;
        boolean unreadableFocus, interruptedFocus;
        int interruptAt = -1;
        long clock, advanceOnFocus;
        Reader(Frame... frames) { this.frames = Arrays.asList(frames); }
        public void verifyDisplay() {
            if (++checks == displayFailureAt) throw new IllegalStateException("display replaced");
        }
        public Map<Integer, Object> roots() {
            return frames.get(Math.min(reads++, frames.size() - 1)).inventory;
        }
        public FocusWitness focusedWitness(Map<Integer, Object> inventory) throws Exception {
            focusReads++;
            if (interruptedFocus) throw new InterruptedException();
            if (focusReads == interruptAt) Thread.currentThread().interrupt();
            if (unreadableFocus) throw new IllegalAccessException("private reflection detail");
            clock += advanceOnFocus;
            Object focused = frames.get(Math.min(reads - 1, frames.size() - 1)).focus;
            Object listed = focused == null ? null : inventory.get(OwnerHandoff.number(focused, "taskId"));
            return FocusWitness.capture(focused, listed, 0, 0);
        }
        public long nanoTime() { return clock; }
    }
    private OwnerFocusPreflight.Sample capture(Reader reader) throws Exception {
        return OwnerFocusPreflight.capture(reader, 3, owned, selected);
    }
    private OwnerHandoff.HandoffFailure refused(Reader reader, String phase) {
        OwnerHandoff.HandoffFailure failure = assertThrows(OwnerHandoff.HandoffFailure.class, () -> capture(reader));
        assertEquals(phase, failure.phase());
        assertEquals(OwnerHandoff.HANDOFF_PREFLIGHT_FAILED, failure.code);
        assertFalse(failure.sideEffectsAttempted());
        return failure;
    }

    @Test public void stableFocusRequiresTwoFreshInventories() throws Exception {
        Object token = new Object();
        Root a = new Root(token), b = new Root(token);
        Frame first = new Frame(a, a), second = new Frame(b, b);
        Reader reader = new Reader(first, second);
        OwnerFocusPreflight.Sample sample = capture(reader);
        assertSame(second.inventory, sample.inventory);
        assertEquals(Arrays.asList("OK", "OK"), sample.observations);
        assertEquals(2, reader.checks); assertEquals(2, reader.reads);
        sample.witness.check(b);
    }

    @Test public void staleInventoryThenStableCanPassWithinOnePhysicalHandoff() throws Exception {
        Root current = new Root(new Object()), stale = new Root(new Object());
        Reader reader = new Reader(new Frame(current, stale), new Frame(current, current), new Frame(current, current));
        OwnerFocusPreflight.Sample sample = capture(reader);
        assertEquals(Arrays.asList("Focus_BINDER_CHANGED", "OK", "OK"), sample.observations);
        assertEquals(3, reader.reads);
    }

    @Test public void missingInventoryIsResampledWithFreshRoots() throws Exception {
        Root root = new Root(new Object());
        Reader reader = new Reader(new Frame(root, null), new Frame(root, root), new Frame(root, root));
        assertEquals(Arrays.asList("Focus_INVENTORY_MISSING", "OK", "OK"), capture(reader).observations);
    }

    @Test public void missingFocusBreaksConsecutiveAgreement() throws Exception {
        Root root = new Root(new Object());
        Reader reader = new Reader(new Frame(root, root), new Frame(null, root),
                new Frame(root, root), new Frame(root, root));
        assertEquals(Arrays.asList("OK", "Focus_FOCUS_MISSING", "OK", "OK"), capture(reader).observations);
        assertEquals(4, reader.reads);
    }

    @Test public void continuouslyChangingIdentityNeverPasses() {
        Root a = new Root(new Object()), b = new Root(new Object());
        Reader reader = new Reader(new Frame(a, a), new Frame(b, b), new Frame(a, a), new Frame(b, b));
        OwnerHandoff.HandoffFailure failure = refused(reader, "preflight:focus");
        assertTrue(failure.getMessage().contains("Focus_FOCUS_UNSTABLE"));
        assertEquals(OwnerFocusPreflight.MAX_SAMPLES, reader.reads);
    }

    @Test public void dataChangesCannotCountAsStableIdentity() {
        Object token = new Object(); Root a = new Root(token), b = new Root(token);
        b.baseIntent.setData(Uri.parse("eta-vd://session/do-not-log"));
        Reader reader = new Reader(new Frame(a, a), new Frame(b, b), new Frame(a, a), new Frame(b, b));
        OwnerHandoff.HandoffFailure failure = refused(reader, "preflight:focus");
        assertFalse(failure.getMessage().contains("do-not-log"));
        assertFalse(failure.focusSamples().toString().contains("eta-vd"));
    }

    @Test public void unknownChildStructureIsNotResampled() {
        Root bad = new Root(new Object()); bad.childTaskNames = null;
        Reader reader = new Reader(new Frame(bad, bad));
        assertTrue(refused(reader, "preflight:focus").getMessage().contains("Focus_CHILD_NAMES_MISSING"));
        assertEquals(1, reader.reads);
    }

    @Test public void sourceFocusIsNotMistakenForMainDisplay() {
        Reader reader = new Reader(new Frame(delivery, delivery));
        assertTrue(refused(reader, "preflight:focus").getMessage().contains("Focus_WRONG_DISPLAY"));
        assertEquals(1, reader.reads);
    }

    @Test public void foreignSourceTaskOnSecondSampleKeepsInventoryClassification() {
        Root root = new Root(new Object());
        Frame first = new Frame(root, root), second = new Frame(root, root);
        Root foreign = new Root(new Object()); foreign.taskId = 21; foreign.displayId = 3;
        second.inventory.put(21, foreign);
        Reader reader = new Reader(first, second);
        refused(reader, "preflight:inventory");
        assertEquals(2, reader.reads); assertEquals(1, reader.focusReads);
    }

    @Test public void disappearingSelectionOnSecondSampleKeepsInventoryClassification() {
        Root root = new Root(new Object());
        Frame first = new Frame(root, root), second = new Frame(root, root);
        second.inventory.remove(20);
        Reader reader = new Reader(first, second);
        refused(reader, "preflight:inventory");
        assertEquals(1, reader.focusReads);
    }

    @Test public void displayReplacementIsNotFocusJitter() {
        Root root = new Root(new Object()); Reader reader = new Reader(new Frame(root, root));
        reader.displayFailureAt = 2;
        refused(reader, "preflight:display");
        assertEquals(1, reader.reads);
    }

    @Test public void reflectionFailureIsNotRetriedInsideSnapshotSampling() {
        Root root = new Root(new Object()); Reader reader = new Reader(new Frame(root, root));
        reader.unreadableFocus = true;
        OwnerHandoff.HandoffFailure failure = refused(reader, "preflight:focus");
        assertTrue(failure.getMessage().contains("IllegalAccessException"));
        assertFalse(failure.getMessage().contains("private reflection detail"));
        assertEquals(1, reader.reads);
    }

    @Test public void expiredBudgetNeverReturnsAnOtherwiseValidSample() {
        Root root = new Root(new Object()); Reader reader = new Reader(new Frame(root, root));
        reader.advanceOnFocus = OwnerFocusPreflight.BUDGET_NS;
        refused(reader, "preflight:focus");
        assertEquals(1, reader.reads);
    }

    @Test public void interruptedReadKeepsCancellationClassificationAndFlag() {
        Root root = new Root(new Object()); Reader reader = new Reader(new Frame(root, root));
        reader.interruptedFocus = true;
        try {
            refused(reader, "preflight:cancelled");
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, reader.focusReads);
        } finally { Thread.interrupted(); }
    }

    @Test public void cancellationOnLastRejectedSampleCannotBecomeRetryableFocusJitter() {
        Root root = new Root(new Object());
        Reader reader = new Reader(new Frame(null, root));
        reader.interruptAt = OwnerFocusPreflight.MAX_SAMPLES;
        try {
            refused(reader, "preflight:cancelled");
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(OwnerFocusPreflight.MAX_SAMPLES, reader.focusReads);
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptionStopsBeforeAnyReadAndRemainsSet() {
        Root root = new Root(new Object()); Reader reader = new Reader(new Frame(root, root));
        Thread.currentThread().interrupt();
        try {
            refused(reader, "preflight:cancelled");
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, reader.checks);
        } finally { Thread.interrupted(); }
    }
}
