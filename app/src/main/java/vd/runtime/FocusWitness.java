package vd.runtime;

import android.content.Intent;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only witness of the main-display focused root task.
 *
 * <p>After every migration step the owner re-asserts the main-display focus by comparing the task the
 * platform currently reports as focused against a witness captured during the read-only preflight.
 * The witness is only ever read and compared: it is never hidden, moved, removed or re-focused.
 *
 * <p>The desktop root is a real container whose child tasks (the launcher and its activities) are
 * legitimate, so this check is deliberately independent of {@link OwnerHandoff.Task#check}, which
 * stays strict for the app tasks that are actually migrated. A witness is accepted only when its
 * identity is fully conservative: a stable binder, the primary user, the main display, a stable root
 * task id with no parent, and a known base component, plus a substructure that is either free of
 * foreign child tasks or whose foreign children are all named by the platform. An unreadable,
 * duplicated or unnamed structure is refused (fail closed), never guessed.
 */
final class FocusWitness {
    /** Fixed tokens only: never expose Intent data, package names or binder values. */
    enum Reason {
        FOCUS_MISSING, INVENTORY_MISSING, INVALID_TASK_ID, WRONG_USER, WRONG_DISPLAY,
        NOT_ROOT, BINDER_MISSING, BASE_MISSING, CHILD_IDS_UNREADABLE, CHILD_NAMES_LENGTH_MISMATCH,
        CHILD_IDS_DUPLICATE, CHILD_ID_INVALID, CHILD_NAMES_MISSING, CHILD_NAME_UNKNOWN, TASK_CHANGED,
        USER_CHANGED, DISPLAY_CHANGED, ROOT_CHANGED, BINDER_CHANGED, BASE_CHANGED,
        DATA_CHANGED, STRUCTURE_CHANGED, FOCUS_UNSTABLE
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

    private FocusWitness(int taskId, Object binder, String base, String data, int userId,
            int displayId, int[] children, String[] childNames) {
        this.taskId = taskId;
        this.binder = binder;
        this.base = base;
        this.data = data;
        this.userId = userId;
        this.displayId = displayId;
        this.children = children.clone();
        this.childNames = childNames == null ? null : childNames.clone();
    }

    /**
     * Captures and validates a witness from the currently focused root task, cross-checking the same
     * task in the root inventory when it is present. Pure validation, no mutation.
     */
    static FocusWitness capture(Object focused, Object inventory, int expectedDisplay, int expectedUser)
            throws Exception {
        if (focused == null) throw new Rejected(Reason.FOCUS_MISSING);
        if (inventory == null) throw new Rejected(Reason.INVENTORY_MISSING);
        FocusWitness witness = read(focused, expectedDisplay, expectedUser);
        witness.check(inventory);
        return witness;
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
        // A focused witness must be a root task, never a nested child.
        if (OwnerHandoff.number(task, "parentTaskId") != -1) {
            throw new Rejected(Reason.NOT_ROOT);
        }
        Object binder = OwnerHandoff.binder(task);
        if (binder == null) throw new Rejected(Reason.BINDER_MISSING);
        String base = baseOf(task);
        String data = dataString(task);
        int[] children = childIds(task);
        String[] names = rawChildNames(task);
        Reason shape = substructureFailure(id, children, names);
        if (shape != null) throw new Rejected(shape);
        return new FocusWitness(id, binder, base, data, userId, displayId, children, names);
    }

    /** Re-verifies the witness against a freshly read focused root task. Pure comparison. */
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
        if (!binder.equals(OwnerHandoff.binder(focused))) {
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

    /** Full identity comparison between two independently validated snapshots. */
    boolean matches(FocusWitness other) {
        return other != null && taskId == other.taskId && userId == other.userId
                && displayId == other.displayId && binder.equals(other.binder)
                && Objects.equals(base, other.base) && Objects.equals(data, other.data)
                && Arrays.equals(children, other.children) && Arrays.equals(childNames, other.childNames);
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
