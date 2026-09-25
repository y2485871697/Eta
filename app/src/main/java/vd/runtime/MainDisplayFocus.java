package vd.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, display-local focus evidence. ATM's no-argument getFocusedRootTaskInfo reports the
 * globally top-focused display, not necessarily the main display. Never use list order, visibility
 * or a lone inventory entry as a substitute for an explicit isFocused task on display 0.
 *
 * <p>Requires the platform's four-argument getTasks(int, boolean, boolean, int) and readable
 * RunningTaskInfo.isFocused/token/parentTaskId fields. The display filter must be honored and the
 * focus flag must describe that display's focused leaf. Missing/ambiguous evidence fails closed;
 * there is deliberately no older-signature, global-focus or task-order fallback. These snapshots
 * are not an atomic system_server fence. OEM semantics and permissions require device validation.
 */
final class MainDisplayFocus {
    static final int MAIN_DISPLAY = 0;
    static final int PRIMARY_USER = 0;
    static final int TASK_LIMIT = 256;

    interface Atm {
        Object call(String method, Class<?>[] types, Object... args) throws Exception;
    }

    private MainDisplayFocus() { }

    static FocusWitness capture(Map<Integer, Object> inventory) throws Exception {
        return read(inventory, new Atm() {
            public Object call(String method, Class<?>[] types, Object... args) throws Exception {
                return OwnerHandoff.invokeAtm(method, types, args);
            }
        });
    }

    /** Test seam around the same read-only reflected call used on device. */
    static FocusWitness read(Map<Integer, Object> inventory, Atm atm) throws Exception {
        Object tasks;
        try {
            tasks = atm.call("getTasks",
                    new Class<?>[]{int.class, boolean.class, boolean.class, int.class},
                    TASK_LIMIT, false, true, MAIN_DISPLAY);
        } catch (ReflectiveOperationException ex) {
            // Includes unavailable signatures and Binder invocation/permission failures. No retry
            // using a weaker API, and no platform exception text in the authenticated protocol.
            throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSUPPORTED);
        } catch (SecurityException ex) {
            throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSUPPORTED);
        }
        return capture(inventory, tasks);
    }

    /** Pure snapshot validation, also used by the portable focus/handoff tests. */
    static FocusWitness capture(Map<Integer, Object> inventory, Object rawTasks) throws Exception {
        if (!(rawTasks instanceof List)) {
            throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNREADABLE);
        }
        List<?> tasks = (List<?>) rawTasks;
        if (tasks.size() >= TASK_LIMIT) {
            throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNREADABLE);
        }
        Object focused = null;
        Set<Integer> ids = new HashSet<Integer>();
        for (Object task : tasks) {
            if (task == null) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNREADABLE);
            int id = OwnerHandoff.number(task, "taskId");
            if (id <= 0) throw new FocusWitness.Rejected(FocusWitness.Reason.INVALID_TASK_ID);
            if (!ids.add(id)) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_AMBIGUOUS);
            if (OwnerHandoff.number(task, "displayId") != MAIN_DISPLAY) {
                throw new FocusWitness.Rejected(FocusWitness.Reason.WRONG_DISPLAY);
            }
            Object flag;
            try { flag = OwnerHandoff.field(task, "isFocused"); }
            catch (ReflectiveOperationException ex) {
                throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSUPPORTED);
            }
            if (!(flag instanceof Boolean)) {
                throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNREADABLE);
            }
            if (!((Boolean) flag).booleanValue()) continue;
            if (focused != null) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_AMBIGUOUS);
            focused = task;
        }
        if (focused == null) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_MISSING);
        if (inventory == null) throw new FocusWitness.Rejected(FocusWitness.Reason.INVENTORY_MISSING);
        int leafId = OwnerHandoff.number(focused, "taskId");
        Object root = null;
        for (Map.Entry<Integer, Object> entry : inventory.entrySet()) {
            Object candidate = entry.getValue();
            if (candidate == null) throw new FocusWitness.Rejected(FocusWitness.Reason.INVENTORY_MISSING);
            int id = OwnerHandoff.number(candidate, "taskId");
            if (entry.getKey() == null || entry.getKey().intValue() != id) {
                throw new FocusWitness.Rejected(FocusWitness.Reason.INVALID_TASK_ID);
            }
            // Exact leaf/root membership, not selection of an arbitrary display-0 inventory task.
            Object rawIds = OwnerHandoff.field(candidate, "childTaskIds");
            if (!(rawIds instanceof int[])) {
                throw new FocusWitness.Rejected(FocusWitness.Reason.CHILD_IDS_UNREADABLE);
            }
            boolean contains = id == leafId;
            for (int child : (int[]) rawIds) if (child == leafId) contains = true;
            if (!contains) continue;
            if (root != null) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_AMBIGUOUS);
            root = candidate;
        }
        if (root == null) throw new FocusWitness.Rejected(FocusWitness.Reason.INVENTORY_MISSING);
        return FocusWitness.captureFocusedTask(focused, root, MAIN_DISPLAY, PRIMARY_USER);
    }
}
