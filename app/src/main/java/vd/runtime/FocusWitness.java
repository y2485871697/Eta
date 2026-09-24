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
        if (inventory == null) throw new IllegalStateException("focus inventory missing");
        FocusWitness witness = read(focused, expectedDisplay, expectedUser);
        witness.check(inventory);
        return witness;
    }

    private static FocusWitness read(Object task, int expectedDisplay, int expectedUser)
            throws Exception {
        if (task == null) throw new IllegalStateException("focus witness missing");
        int id = OwnerHandoff.number(task, "taskId");
        if (id <= 0) throw new IllegalStateException("focus witness id");
        int userId = OwnerHandoff.number(task, "userId");
        if (userId != expectedUser) throw new IllegalStateException("focus witness user");
        int displayId = OwnerHandoff.number(task, "displayId");
        if (displayId != expectedDisplay) throw new IllegalStateException("focus witness display");
        // A focused witness must be a root task, never a nested child.
        if (OwnerHandoff.number(task, "parentTaskId") != -1) {
            throw new IllegalStateException("focus witness not a root");
        }
        Object binder = OwnerHandoff.binder(task);
        if (binder == null) throw new IllegalStateException("focus binder missing");
        String base = OwnerHandoff.base(task);
        String data = dataString(task);
        int[] children = childIds(task);
        String[] names = rawChildNames(task);
        if (!focusSubstructureKnown(id, children, names)) {
            throw new IllegalStateException("focus witness substructure");
        }
        return new FocusWitness(id, binder, base, data, userId, displayId, children, names);
    }

    /** Re-verifies the witness against a freshly read focused root task. Pure comparison. */
    void check(Object focused) throws Exception {
        if (focused == null) throw new IllegalStateException("focus lost");
        if (OwnerHandoff.number(focused, "taskId") != taskId) {
            throw new IllegalStateException("focus task changed");
        }
        if (OwnerHandoff.number(focused, "userId") != userId) {
            throw new IllegalStateException("focus user changed");
        }
        if (OwnerHandoff.number(focused, "displayId") != displayId) {
            throw new IllegalStateException("focus display changed");
        }
        if (OwnerHandoff.number(focused, "parentTaskId") != -1) {
            throw new IllegalStateException("focus no longer a root");
        }
        if (!binder.equals(OwnerHandoff.binder(focused))) {
            throw new IllegalStateException("focus binder changed");
        }
        if (!Objects.equals(base, OwnerHandoff.base(focused))) {
            throw new IllegalStateException("focus base changed");
        }
        if (!Objects.equals(data, dataString(focused))) {
            throw new IllegalStateException("focus data changed");
        }
        int[] nextChildren = childIds(focused);
        String[] nextNames = rawChildNames(focused);
        if (!focusSubstructureKnown(taskId, nextChildren, nextNames)
                || !Arrays.equals(children, nextChildren) || !Arrays.equals(childNames, nextNames)) {
            throw new IllegalStateException("focus substructure changed");
        }
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
        if (selfId <= 0 || childIds == null) return false;
        if (rawNames != null && rawNames.length != childIds.length) return false;
        Set<Integer> seen = new HashSet<Integer>();
        boolean foreign = false;
        for (int id : childIds) {
            if (!seen.add(id)) return false;
            if (id == selfId || id == -1) continue;
            if (id <= 0) return false;
            foreign = true;
        }
        if (!foreign) return true;
        if (rawNames == null || rawNames.length != childIds.length) return false;
        for (int i = 0; i < childIds.length; i++) {
            int id = childIds[i];
            if (id == selfId || id == -1) continue;
            if (OwnerHandoff.childNamePackage(rawNames[i]) == null) return false;
        }
        return true;
    }
}
