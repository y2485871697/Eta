package vd.runtime;

import android.content.Intent;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only witness of the main-display focused task and its root.
 *
 * <p>The root and (when supplied by the display-local reader) focused leaf identities are captured
 * together. Checking only the root would miss a foreground switch between two of its children.
 * Nothing here hides, moves, removes or re-focuses the protected foreground task.
 *
 * <p>The desktop root can be a real container, unlike the strict app tasks that are migrated by
 * {@link OwnerHandoff.Task#check}. Its structure must be readable and stable, and every foreign
 * child must be named by the platform. A focused leaf must belong to exactly this root; a leaf
 * that is itself a root must have the same Binder and identity in both snapshots.
 */
final class FocusWitness {
    /** Fixed tokens only: never expose Intent data, package names or binder values. */
    enum Reason {
        FOCUS_MISSING, INVENTORY_MISSING, INVALID_TASK_ID, WRONG_USER, WRONG_DISPLAY,
        NOT_ROOT, BINDER_MISSING, BASE_MISSING, CHILD_IDS_UNREADABLE, CHILD_NAMES_LENGTH_MISMATCH,
        CHILD_IDS_DUPLICATE, CHILD_ID_INVALID, CHILD_NAMES_MISSING, CHILD_NAME_UNKNOWN, TASK_CHANGED,
        USER_CHANGED, DISPLAY_CHANGED, ROOT_CHANGED, BINDER_CHANGED, BASE_CHANGED,
        DATA_CHANGED, STRUCTURE_CHANGED, FOCUS_UNSTABLE, FOCUS_UNSUPPORTED, FOCUS_UNREADABLE,
        FOCUS_AMBIGUOUS, LEAF_ROOT_MISMATCH
    }

    static final class Rejected extends IllegalStateException {
        final Reason reason;
        Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        String diagnosticCode() { return "Focus_" + reason.name(); }
    }

    private final int taskId;
    private final Object binder;
    private final String base;
    private final String data;
    private final int userId;
    private final int displayId;
    private final int[] children;
    private final String[] childNames;
    private final Leaf leaf;

    private FocusWitness(int taskId, Object binder, String base, String data, int userId,
            int displayId, int[] children, String[] childNames) {
        this(taskId, binder, base, data, userId, displayId, children, childNames, null);
    }

    private FocusWitness(int taskId, Object binder, String base, String data, int userId,
            int displayId, int[] children, String[] childNames, Leaf leaf) {
        this.taskId = taskId;
        this.binder = binder;
        this.base = base;
        this.data = data;
        this.userId = userId;
        this.displayId = displayId;
        this.children = children.clone();
        this.childNames = childNames == null ? null : childNames.clone();
        this.leaf = leaf;
    }

    /** Validates a focused root against the same task in a separate root inventory. */
    static FocusWitness capture(Object focused, Object inventory, int expectedDisplay, int expectedUser)
            throws Exception {
        if (focused == null) throw new Rejected(Reason.FOCUS_MISSING);
        if (inventory == null) throw new Rejected(Reason.INVENTORY_MISSING);
        FocusWitness witness = read(focused, expectedDisplay, expectedUser);
        witness.check(inventory);
        return witness;
    }

    /**
     * Joins explicit display-local focused-leaf evidence to its uniquely identified inventory root.
     * getTasks returns leaves, whereas RootTaskInfo.childTaskIds names leaves below a container.
     * TaskInfo.parentTaskId can be -1 for a non-organized parent; exact inventory membership is
     * still required in that case. A reported direct parent other than this root is unsupported
     * (e.g. a multi-level organizer hierarchy), never guessed.
     */
    static FocusWitness captureFocusedTask(Object focused, Object root, int expectedDisplay,
            int expectedUser) throws Exception {
        Leaf leaf = new Leaf(focused, expectedDisplay, expectedUser);
        FocusWitness witness = read(root, expectedDisplay, expectedUser);
        if (leaf.id == witness.taskId) {
            if (leaf.parent != -1) throw new Rejected(Reason.NOT_ROOT);
            if (!OwnerHandoff.validRootChildMarkers(witness.taskId, witness.children)) {
                throw new Rejected(Reason.LEAF_ROOT_MISMATCH);
            }
            if (!witness.binder.equals(leaf.binder)) throw new Rejected(Reason.BINDER_CHANGED);
            if (!Objects.equals(witness.base, leaf.base)) throw new Rejected(Reason.BASE_CHANGED);
            if (!Objects.equals(witness.data, leaf.data)) throw new Rejected(Reason.DATA_CHANGED);
        } else {
            if (leaf.parent != -1 && leaf.parent != witness.taskId) {
                throw new Rejected(Reason.LEAF_ROOT_MISMATCH);
            }
            if (!OwnerHandoff.completeChildIds(witness.children, witness.taskId)) {
                throw new Rejected(Reason.LEAF_ROOT_MISMATCH);
            }
            boolean found = false;
            for (int id : witness.children) if (id == leaf.id) found = true;
            if (!found || witness.binder.equals(leaf.binder)) {
                throw new Rejected(Reason.LEAF_ROOT_MISMATCH);
            }
        }
        return new FocusWitness(witness.taskId, witness.binder, witness.base, witness.data,
                witness.userId, witness.displayId, witness.children, witness.childNames, leaf);
    }

    private static FocusWitness read(Object task, int expectedDisplay, int expectedUser)
            throws Exception {
        if (task == null) throw new Rejected(Reason.FOCUS_MISSING);
        int id = OwnerHandoff.number(task, "taskId");
        if (id <= 0) throw new Rejected(Reason.INVALID_TASK_ID);
        int userId = OwnerHandoff.number(task, "userId");
        if (userId != expectedUser) throw new Rejected(Reason.WRONG_USER);
        int displayId = OwnerHandoff.number(task, "displayId");
        if (displayId != expectedDisplay) throw new Rejected(Reason.WRONG_DISPLAY);
        if (OwnerHandoff.number(task, "parentTaskId") != -1) {
            throw new Rejected(Reason.NOT_ROOT);
        }
        Object binder = binderOf(task);
        String base = baseOf(task);
        String data = dataString(task);
        int[] children = childIds(task);
        String[] names = rawChildNames(task);
        Reason shape = substructureFailure(id, children, names);
        if (shape != null) throw new Rejected(shape);
        return new FocusWitness(id, binder, base, data, userId, displayId, children, names);
    }

    /** Re-verifies root identity against a fresh root task. Does not assert leaf focus by itself. */
    void check(Object focused) throws Exception {
        if (focused == null) throw new Rejected(Reason.FOCUS_MISSING);
        if (OwnerHandoff.number(focused, "taskId") != taskId) {
            throw new Rejected(Reason.TASK_CHANGED);
        }
        if (OwnerHandoff.number(focused, "userId") != userId) {
            throw new Rejected(Reason.USER_CHANGED);
        }
        if (OwnerHandoff.number(focused, "displayId") != displayId) {
            throw new Rejected(Reason.DISPLAY_CHANGED);
        }
        if (OwnerHandoff.number(focused, "parentTaskId") != -1) {
            throw new Rejected(Reason.ROOT_CHANGED);
        }
        if (!binder.equals(binderOf(focused))) {
            throw new Rejected(Reason.BINDER_CHANGED);
        }
        if (!Objects.equals(base, baseOf(focused))) {
            throw new Rejected(Reason.BASE_CHANGED);
        }
        if (!Objects.equals(data, dataString(focused))) {
            throw new Rejected(Reason.DATA_CHANGED);
        }
        int[] nextChildren = childIds(focused);
        String[] nextNames = rawChildNames(focused);
        Reason shape = substructureFailure(taskId, nextChildren, nextNames);
        if (shape != null) throw new Rejected(shape);
        if (!Arrays.equals(children, nextChildren) || !Arrays.equals(childNames, nextNames)) {
            throw new Rejected(Reason.STRUCTURE_CHANGED);
        }
    }

    /** Post-anchor validation must compare fresh display-local evidence, including the leaf. */
    void checkWitness(FocusWitness next) {
        if (next == null) throw new Rejected(Reason.FOCUS_MISSING);
        if (taskId != next.taskId) throw new Rejected(Reason.TASK_CHANGED);
        if (userId != next.userId) throw new Rejected(Reason.USER_CHANGED);
        if (displayId != next.displayId) throw new Rejected(Reason.DISPLAY_CHANGED);
        if (!binder.equals(next.binder)) throw new Rejected(Reason.BINDER_CHANGED);
        if (!Objects.equals(base, next.base)) throw new Rejected(Reason.BASE_CHANGED);
        if (!Objects.equals(data, next.data)) throw new Rejected(Reason.DATA_CHANGED);
        if (!Arrays.equals(children, next.children) || !Arrays.equals(childNames, next.childNames)) {
            throw new Rejected(Reason.STRUCTURE_CHANGED);
        }
        if (leaf == null && next.leaf == null) return;
        if (leaf == null || next.leaf == null) throw new Rejected(Reason.STRUCTURE_CHANGED);
        leaf.check(next.leaf);
    }

    /** Full identity comparison between two independently validated snapshots. */
    boolean matches(FocusWitness other) {
        try { checkWitness(other); return true; }
        catch (Rejected ex) { return false; }
    }

    /** Immutable leaf identity: token wrappers may differ, their underlying Binder must not. */
    private static final class Leaf {
        final int id, user, display, parent;
        final Object binder;
        final String base, data;
        Leaf(Object task, int expectedDisplay, int expectedUser) throws Exception {
            if (task == null) throw new Rejected(Reason.FOCUS_MISSING);
            id = OwnerHandoff.number(task, "taskId");
            if (id <= 0) throw new Rejected(Reason.INVALID_TASK_ID);
            user = OwnerHandoff.number(task, "userId");
            if (user != expectedUser) throw new Rejected(Reason.WRONG_USER);
            display = OwnerHandoff.number(task, "displayId");
            if (display != expectedDisplay) throw new Rejected(Reason.WRONG_DISPLAY);
            parent = OwnerHandoff.number(task, "parentTaskId");
            if (parent != -1 && (parent <= 0 || parent == id)) {
                throw new Rejected(Reason.LEAF_ROOT_MISMATCH);
            }
            binder = binderOf(task);
            base = baseOf(task);
            data = dataString(task);
        }
        void check(Leaf next) {
            if (id != next.id) throw new Rejected(Reason.TASK_CHANGED);
            if (user != next.user) throw new Rejected(Reason.USER_CHANGED);
            if (display != next.display) throw new Rejected(Reason.DISPLAY_CHANGED);
            if (parent != next.parent) throw new Rejected(Reason.ROOT_CHANGED);
            if (!binder.equals(next.binder)) throw new Rejected(Reason.BINDER_CHANGED);
            if (!Objects.equals(base, next.base)) throw new Rejected(Reason.BASE_CHANGED);
            if (!Objects.equals(data, next.data)) throw new Rejected(Reason.DATA_CHANGED);
        }
    }

    private static Object binderOf(Object task) {
        try {
            Object binder = OwnerHandoff.binder(task);
            if (binder != null) return binder;
        } catch (Exception ignored) { }
        throw new Rejected(Reason.BINDER_MISSING);
    }

    private static String baseOf(Object task) throws Exception {
        Object value = OwnerHandoff.field(task, "baseIntent");
        if (!(value instanceof Intent) || ((Intent) value).getComponent() == null) {
            throw new Rejected(Reason.BASE_MISSING);
        }
        return ((Intent) value).getComponent().flattenToString();
    }

    private static String dataString(Object task) throws Exception {
        Object rawIntent = OwnerHandoff.field(task, "baseIntent");
        return rawIntent instanceof Intent ? ((Intent) rawIntent).getDataString() : null;
    }

    private static int[] childIds(Object task) {
        try {
            Object raw = OwnerHandoff.field(task, "childTaskIds");
            return raw instanceof int[] ? (int[]) raw : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String[] rawChildNames(Object task) {
        try {
            Object raw = OwnerHandoff.field(task, "childTaskNames");
            return raw instanceof String[] ? (String[]) raw : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * True when a root's child structure is fully readable: no duplicate ids, and every foreign child
     * (an id that is neither the root's own id nor the {@code -1} non-task marker) is named by the
     * platform. A missing or non-parallel name array, a malformed or placeholder name, a duplicated
     * id and any other non-positive id are unknown and refused. Pure; no device access.
     */
    static boolean focusSubstructureKnown(int selfId, int[] childIds, String[] rawNames) {
        return substructureFailure(selfId, childIds, rawNames) == null;
    }

    /** The same fail-closed predicate, with a fixed diagnostic for every refusal branch. */
    static Reason substructureFailure(int selfId, int[] childIds, String[] rawNames) {
        if (selfId <= 0) return Reason.INVALID_TASK_ID;
        if (childIds == null) return Reason.CHILD_IDS_UNREADABLE;
        if (rawNames != null && rawNames.length != childIds.length) return Reason.CHILD_NAMES_LENGTH_MISMATCH;
        Set<Integer> seen = new HashSet<Integer>();
        boolean foreign = false;
        for (int id : childIds) {
            if (!seen.add(id)) return Reason.CHILD_IDS_DUPLICATE;
            if (id == selfId || id == -1) continue;
            if (id <= 0) return Reason.CHILD_ID_INVALID;
            foreign = true;
        }
        if (!foreign) return null;
        if (rawNames == null) return Reason.CHILD_NAMES_MISSING;
        for (int i = 0; i < childIds.length; i++) {
            int id = childIds[i];
            if (id == selfId || id == -1) continue;
            if (OwnerHandoff.childNamePackage(rawNames[i]) == null) return Reason.CHILD_NAME_UNKNOWN;
        }
        return null;
    }
}
