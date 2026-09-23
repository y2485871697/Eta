package vd.close;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-session close state machine.
 *
 * <p>The main foreground handle is frozen from {@code plan.main} and compared with
 * {@code ==} for the whole run. Focus is not expected to move onto the secondary
 * task and is not expected to become HOME. No app is killed and nothing is deployed.
 *
 * <p>After a target leaves the source it is in transit until restore is confirmed.
 * Every step re-checks every migrated task, including that in-transit task: it must
 * still be on main under the same handle. Confirmed restores are tracked separately
 * and release is issued at most once, only when that set covers the whole plan and
 * the source snapshot is empty.
 *
 * <p>{@link #run()} is claimed once with an {@link AtomicBoolean} compare-and-set
 * while the session lock is held. {@link #release()} uses that same lock, so it
 * cannot issue a port call while a run is executing. The terminal receipt is
 * published on the lock (and via a volatile field) before {@link #run()} returns.
 *
 * <p>At every source boundary the live source set must be exactly the planned
 * targets minus confirmed migrants, compared by reference. Extras, duplicates, and
 * same-id replacement handles are rejected before the next mutation. Release is
 * not issued unless that set is empty.
 *
 * <p>A null result or exception from migrate, restore, or release is uncertain:
 * the attempt stops immediately, with no retry and no rollback. The in-flight
 * handle is kept on {@link CloseReceipt#attemptedTask} and is not added to the
 * confirmed migrated set. Any other surface exception is also stored as a
 * non-success receipt instead of escaping.
 */
public final class SingleSessionCloseController {
    private final ClosePlan plan;
    private final CloseSurface surface;
    /** Frozen at construction. Never refreshed from the live display. */
    private final TaskHandle frozenMain;

    private final List<TaskHandle> migrated = new ArrayList<TaskHandle>();
    private final List<TaskHandle> restoredOrder = new ArrayList<TaskHandle>();
    private final IdentityHashMap<TaskHandle, Boolean> restored =
            new IdentityHashMap<TaskHandle, Boolean>();
    private final List<String> actions = new ArrayList<String>();

    private final Object session = new Object();
    private final AtomicBoolean runClaimed = new AtomicBoolean(false);
    private final AtomicBoolean releaseClaimed = new AtomicBoolean(false);
    private volatile CloseReceipt lastReceipt;
    private boolean executing;
    private TaskHandle failingTask;
    /** Task whose migrate/restore port call has not been confirmed. */
    private TaskHandle attemptedTask;

    public SingleSessionCloseController(ClosePlan plan, CloseSurface surface) {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        if (surface == null) {
            throw new NullPointerException("surface");
        }
        if (plan.main == null || plan.source == null || plan.targets == null) {
            throw new IllegalArgumentException("plan identity fields must be non-null");
        }
        this.plan = plan;
        this.surface = surface;
        this.frozenMain = plan.main;
    }

    /** Receipt stored by the first {@link #run()}, retained across later guard calls. */
    public CloseReceipt receipt() {
        synchronized (session) {
            return lastReceipt;
        }
    }

    public CloseReceipt run() {
        synchronized (session) {
            if (!runClaimed.compareAndSet(false, true)) {
                return CloseReceipt.alreadyConsumed("RUN_ALREADY_CONSUMED", lastReceipt);
            }
            executing = true;
            try {
                CloseReceipt result = runBody();
                lastReceipt = result;
                return result;
            } finally {
                executing = false;
            }
        }
    }

    /**
     * Single-shot view of the release slot. {@link #run()} releases when every
     * restore was confirmed and the source is empty. Calling this again does not
     * release a second time. If a run is still executing, or restore was not
     * confirmed, the port is not called.
     */
    public CloseReceipt release() {
        synchronized (session) {
            try {
                if (releaseClaimed.get()) {
                    return CloseReceipt.alreadyConsumed("RELEASE_ALREADY_CONSUMED", lastReceipt);
                }
                if (executing
                        || !runClaimed.get()
                        || lastReceipt == null
                        || lastReceipt.status != CloseReceipt.Status.SUCCESS
                        || !allRestored()) {
                    return CloseReceipt.alreadyConsumed("RESTORE_UNCONFIRMED", lastReceipt);
                }
                CloseReceipt released = performRelease();
                lastReceipt = released;
                return released;
            } catch (SurfaceException ex) {
                CloseReceipt failed = fail(ex.status, ex.code, ex.cause);
                lastReceipt = failed;
                return failed;
            } catch (RuntimeException ex) {
                CloseReceipt failed = uncertain("SURFACE_UNCERTAIN", ex);
                lastReceipt = failed;
                return failed;
            }
        }
    }

    private CloseReceipt runBody() {
        try {
            CloseReceipt migration = migrateAndRestore();
            if (migration.status != CloseReceipt.Status.SUCCESS) {
                return migration;
            }
            return performRelease();
        } catch (SurfaceException ex) {
            return fail(ex.status, ex.code, ex.cause);
        } catch (RuntimeException ex) {
            return uncertain("SURFACE_UNCERTAIN", ex);
        }
    }

    private CloseReceipt migrateAndRestore() {
        String violation = verifyMigrated();
        if (violation != null) {
            return fail(CloseReceipt.Status.REJECTED, violation, null);
        }
        CloseReceipt source = rejectSource(null);
        if (source != null) {
            return source;
        }
        for (int i = 0; i < plan.targets.size(); i++) {
            TaskHandle target = plan.targets.get(i);
            violation = verifyMigrated();
            if (violation != null) {
                return fail(CloseReceipt.Status.REJECTED, violation, null);
            }
            source = rejectSource(target);
            if (source != null) {
                return source;
            }

            beginAttempt(target);
            actions.add("migrate");
            ActionResult move;
            try {
                move = surface.migrateToMain(target);
            } catch (RuntimeException ex) {
                return uncertain("MIGRATE_UNCERTAIN", ex);
            }
            if (move == null) {
                return uncertain("MIGRATE_UNCERTAIN", "null receipt");
            }
            if (move.kind != ActionResult.Kind.SUCCESS) {
                if (move.kind == ActionResult.Kind.UNCERTAIN) {
                    return fail(CloseReceipt.Status.UNCERTAIN, "MIGRATE_UNCERTAIN", move);
                }
                return fail(CloseReceipt.Status.FAILED, "MIGRATE_FAILED", move);
            }

            List<TaskHandle> sourceAfter = readSource();
            List<TaskHandle> mainAfter = readMain();
            if (sourceAfter == null || mainAfter == null) {
                migrated.add(target);
                return fail(CloseReceipt.Status.REJECTED, "INVALID_SNAPSHOT", null);
            }
            if (containsRef(sourceAfter, target)) {
                failingTask = target;
                return fail(CloseReceipt.Status.FAILED, "MIGRATE_FAILED", move);
            }

            // In transit from the moment it leaves the source until restore is confirmed.
            migrated.add(target);
            clearAttempt();
            String drifted = matchSource(sourceAfter);
            if (drifted != null) {
                return fail(CloseReceipt.Status.REJECTED, drifted, null);
            }
            violation = verifyMigrated();
            if (violation != null) {
                return fail(CloseReceipt.Status.REJECTED, violation, null);
            }

            beginAttempt(target);
            actions.add("restore");
            ActionResult restoredResult;
            try {
                restoredResult = surface.restore(target);
            } catch (RuntimeException ex) {
                return uncertain("RESTORE_UNCONFIRMED", ex);
            }
            if (restoredResult == null) {
                return uncertain("RESTORE_UNCONFIRMED", "null receipt");
            }
            if (restoredResult.kind != ActionResult.Kind.SUCCESS) {
                CloseReceipt.Status status = restoredResult.kind == ActionResult.Kind.UNCERTAIN
                        ? CloseReceipt.Status.UNCERTAIN
                        : CloseReceipt.Status.FAILED;
                return fail(status, "RESTORE_UNCONFIRMED", restoredResult);
            }
            violation = verifyMigrated();
            if (violation != null) {
                return fail(CloseReceipt.Status.REJECTED, violation, null);
            }
            source = rejectSource(null);
            if (source != null) {
                return source;
            }
            markRestored(target);
            clearAttempt();
        }
        if (!allRestored()) {
            return fail(CloseReceipt.Status.REJECTED, "RESTORE_UNCONFIRMED", null);
        }
        clearAttempt();
        return CloseReceipt.of(
                CloseReceipt.Status.SUCCESS,
                null,
                "",
                null,
                actions,
                migrated,
                restoredOrder,
                null);
    }

    private CloseReceipt performRelease() {
        if (releaseClaimed.get()) {
            return CloseReceipt.alreadyConsumed("RELEASE_ALREADY_CONSUMED", lastReceipt);
        }
        if (!allRestored()) {
            return fail(CloseReceipt.Status.REJECTED, "RESTORE_UNCONFIRMED", null);
        }
        String violation = verifyMigrated();
        if (violation != null) {
            return fail(CloseReceipt.Status.REJECTED, violation, null);
        }
        CloseReceipt source = rejectSource(null);
        if (source != null) {
            return source;
        }
        if (!releaseClaimed.compareAndSet(false, true)) {
            return CloseReceipt.alreadyConsumed("RELEASE_ALREADY_CONSUMED", lastReceipt);
        }
        actions.add("release");
        clearAttempt();
        ActionResult result;
        try {
            result = surface.release();
        } catch (RuntimeException ex) {
            return uncertain("RELEASE_UNCERTAIN", ex);
        }
        if (result == null) {
            return uncertain("RELEASE_UNCERTAIN", "null receipt");
        }
        if (result.kind == ActionResult.Kind.UNCERTAIN) {
            return fail(CloseReceipt.Status.UNCERTAIN, "RELEASE_UNCERTAIN", result);
        }
        if (result.kind != ActionResult.Kind.SUCCESS) {
            return fail(CloseReceipt.Status.FAILED, "RELEASE_FAILED", result);
        }
        return CloseReceipt.of(
                CloseReceipt.Status.SUCCESS,
                null,
                "",
                null,
                actions,
                migrated,
                restoredOrder,
                null);
    }

    /**
     * Focus must still be the frozen main handle. Every migrated task, including
     * the one currently in transit (migrated but not restored), must still be on
     * main under the same reference.
     */
    private String verifyMigrated() {
        TaskHandle foreground = readForeground();
        if (foreground != frozenMain) {
            failingTask = null;
            return "MAIN_FOCUS_CHANGED";
        }
        if (migrated.isEmpty()) {
            return null;
        }
        List<TaskHandle> onMain = readMain();
        if (onMain == null) {
            return "INVALID_SNAPSHOT";
        }
        for (int i = 0; i < migrated.size(); i++) {
            TaskHandle task = migrated.get(i);
            if (!containsRef(onMain, task)) {
                failingTask = task;
                if (replacementOnMain(onMain, task)) {
                    return "TASK_REPLACED";
                }
                return "TASK_LOST";
            }
        }
        return null;
    }

    /**
     * @param required target that must still be on the source, or null when the
     *     check is only the exact set (entry, after restore, and before release)
     */
    private CloseReceipt rejectSource(TaskHandle required) {
        List<TaskHandle> onSource = readSource();
        if (onSource == null) {
            return fail(CloseReceipt.Status.REJECTED, "INVALID_SNAPSHOT", null);
        }
        if (required != null && !containsRef(onSource, required)) {
            failingTask = required;
            return fail(CloseReceipt.Status.REJECTED, "TARGET_NOT_ON_SOURCE", null);
        }
        String mismatch = matchSource(onSource);
        if (mismatch != null) {
            return fail(CloseReceipt.Status.REJECTED, mismatch, null);
        }
        return null;
    }

    /**
     * Source must equal planned targets minus confirmed migrants, by reference.
     * Same-id substitutes, duplicates, and extras all fail this check.
     */
    private String matchSource(List<TaskHandle> onSource) {
        IdentityHashMap<TaskHandle, Boolean> expected = new IdentityHashMap<TaskHandle, Boolean>();
        for (int i = 0; i < plan.targets.size(); i++) {
            TaskHandle target = plan.targets.get(i);
            if (!containsRef(migrated, target)) {
                expected.put(target, Boolean.TRUE);
            }
        }
        if (onSource.size() != expected.size()) {
            failingTask = firstUnexpected(onSource, expected);
            return "SOURCE_NOT_EXACT";
        }
        IdentityHashMap<TaskHandle, Boolean> seen = new IdentityHashMap<TaskHandle, Boolean>();
        for (int i = 0; i < onSource.size(); i++) {
            TaskHandle task = onSource.get(i);
            if (task == null || !expected.containsKey(task) || seen.put(task, Boolean.TRUE) != null) {
                failingTask = task;
                return "SOURCE_NOT_EXACT";
            }
        }
        return null;
    }

    private static TaskHandle firstUnexpected(
            List<TaskHandle> onSource, IdentityHashMap<TaskHandle, Boolean> expected) {
        IdentityHashMap<TaskHandle, Boolean> seen = new IdentityHashMap<TaskHandle, Boolean>();
        for (int i = 0; i < onSource.size(); i++) {
            TaskHandle task = onSource.get(i);
            if (task == null || !expected.containsKey(task) || seen.put(task, Boolean.TRUE) != null) {
                return task;
            }
        }
        return null;
    }

    private boolean replacementOnMain(List<TaskHandle> onMain, TaskHandle task) {
        for (int i = 0; i < onMain.size(); i++) {
            TaskHandle other = onMain.get(i);
            if (other == null || other == task) {
                continue;
            }
            if (!other.id.equals(task.id)) {
                continue;
            }
            if (!containsRef(migrated, other)) {
                return true;
            }
        }
        return false;
    }

    private void beginAttempt(TaskHandle task) {
        attemptedTask = task;
    }

    private void clearAttempt() {
        attemptedTask = null;
    }

    private void markRestored(TaskHandle target) {
        if (restored.put(target, Boolean.TRUE) == null) {
            restoredOrder.add(target);
        }
    }

    private boolean allRestored() {
        if (restoredOrder.size() != plan.targets.size()) {
            return false;
        }
        for (int i = 0; i < plan.targets.size(); i++) {
            if (!restored.containsKey(plan.targets.get(i))) {
                return false;
            }
        }
        return true;
    }

    private CloseReceipt fail(CloseReceipt.Status status, String code, ActionResult cause) {
        StringBuilder detail = new StringBuilder(code);
        if (failingTask != null) {
            detail.append(": ").append(failingTask.id);
            failingTask = null;
        }
        if (cause != null && cause.detail != null && !cause.detail.isEmpty()) {
            detail.append(": ").append(cause.detail);
        }
        return CloseReceipt.of(
                status, code, detail.toString(), cause, actions, migrated, restoredOrder, attemptedTask);
    }

    private CloseReceipt uncertain(String code, RuntimeException ex) {
        return uncertain(code, describe(ex));
    }

    private CloseReceipt uncertain(String code, String detail) {
        if (failingTask == null) {
            failingTask = attemptedTask;
        }
        return fail(CloseReceipt.Status.UNCERTAIN, code, ActionResult.uncertain(detail));
    }

    private TaskHandle readForeground() {
        try {
            return surface.mainForeground();
        } catch (RuntimeException ex) {
            throw readFailure(ex);
        }
    }

    private List<TaskHandle> readSource() {
        try {
            return snapshot(surface.tasksOnSource());
        } catch (RuntimeException ex) {
            throw readFailure(ex);
        }
    }

    private List<TaskHandle> readMain() {
        try {
            return snapshot(surface.tasksOnMain());
        } catch (RuntimeException ex) {
            throw readFailure(ex);
        }
    }

    private static List<TaskHandle> snapshot(List<TaskHandle> raw) {
        if (raw == null) {
            return null;
        }
        return new ArrayList<TaskHandle>(raw);
    }

    private SurfaceException readFailure(RuntimeException ex) {
        boolean uncertain = !actions.isEmpty();
        return new SurfaceException(
                uncertain ? CloseReceipt.Status.UNCERTAIN : CloseReceipt.Status.REJECTED,
                uncertain ? "SURFACE_UNCERTAIN" : "SURFACE_READ_FAILED",
                ActionResult.uncertain(describe(ex)),
                ex);
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }

    private static boolean containsRef(List<TaskHandle> tasks, TaskHandle handle) {
        if (tasks == null || handle == null) {
            return false;
        }
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i) == handle) {
                return true;
            }
        }
        return false;
    }

    /** Read-side surface failure. Caught at the session boundary and stored. */
    private static final class SurfaceException extends RuntimeException {
        final CloseReceipt.Status status;
        final String code;
        final ActionResult cause;

        SurfaceException(CloseReceipt.Status status, String code, ActionResult cause, Throwable raw) {
            super(raw);
            this.status = status;
            this.code = code;
            this.cause = cause;
        }
    }
}
