package vd.close;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of a close attempt. Non-success always carries an error code. */
public final class CloseReceipt {
    public enum Status {
        SUCCESS,
        REJECTED,
        FAILED,
        UNCERTAIN
    }

    public final Status status;
    /** Stable error code, or null only when {@link #status} is SUCCESS. */
    public final String error;
    public final String detail;
    /**
     * Port receipt that caused the failure, when the failure came from a port call.
     * Not copied; callers can rely on reference identity.
     */
    public final ActionResult cause;
    public final List<String> actions;
    /** Tasks that left the source, including the current in-transit task. */
    public final List<TaskHandle> migrated;
    /** Tasks whose restore was confirmed and whose post-checks passed. */
    public final List<TaskHandle> restored;
    /**
     * Handle of the port call that was in flight when the attempt stopped.
     * Set for a migrate/restore whose result was null or threw, and for any
     * other non-success that still has a current task. Never a substitute for
     * {@link #migrated}: an unconfirmed move stays out of that list.
     * Null on success and when no task was in flight (for example release).
     */
    public final TaskHandle attemptedTask;

    private CloseReceipt(
            Status status,
            String error,
            String detail,
            ActionResult cause,
            List<String> actions,
            List<TaskHandle> migrated,
            List<TaskHandle> restored,
            TaskHandle attemptedTask) {
        if (status == null) {
            throw new NullPointerException("status");
        }
        if (status != Status.SUCCESS && (error == null || error.isEmpty())) {
            throw new IllegalArgumentException("error receipt required");
        }
        boolean success = status == Status.SUCCESS;
        this.status = status;
        this.error = success ? null : error;
        this.detail = success ? "" : (detail == null ? "" : detail);
        this.cause = success ? null : cause;
        this.actions = actions;
        this.migrated = migrated;
        this.restored = restored;
        this.attemptedTask = success ? null : attemptedTask;
    }

    static CloseReceipt of(
            Status status,
            String error,
            String detail,
            ActionResult cause,
            List<String> actions,
            List<TaskHandle> migrated,
            List<TaskHandle> restored,
            TaskHandle attemptedTask) {
        return new CloseReceipt(
                status,
                status == Status.SUCCESS ? null : error,
                status == Status.SUCCESS ? "" : detail,
                status == Status.SUCCESS ? null : cause,
                Collections.unmodifiableList(new ArrayList<String>(actions)),
                Collections.unmodifiableList(new ArrayList<TaskHandle>(migrated)),
                Collections.unmodifiableList(new ArrayList<TaskHandle>(restored)),
                status == Status.SUCCESS ? null : attemptedTask);
    }

    /** A guard result that still points at the previous port receipt, if any. */
    public static CloseReceipt alreadyConsumed(String code, CloseReceipt prior) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code");
        }
        ActionResult cause = prior == null ? null : prior.cause;
        String detail = prior == null || prior.error == null ? code : code + ": previous=" + prior.error;
        List<String> noActions = Collections.emptyList();
        List<TaskHandle> migrated = prior == null
                ? Collections.<TaskHandle>emptyList() : prior.migrated;
        List<TaskHandle> restored = prior == null
                ? Collections.<TaskHandle>emptyList() : prior.restored;
        TaskHandle attemptedTask = prior == null ? null : prior.attemptedTask;
        return new CloseReceipt(
                Status.REJECTED,
                code,
                detail,
                cause,
                noActions,
                migrated,
                restored,
                attemptedTask);
    }

    @Override
    public String toString() {
        return status + (error == null ? "" : " " + error) + (detail.isEmpty() ? "" : " (" + detail + ")");
    }
}
