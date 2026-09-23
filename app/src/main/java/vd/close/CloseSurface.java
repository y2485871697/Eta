package vd.close;

import java.util.List;

/**
 * Host-side port for one close session.
 *
 * <p>There is no HOME, kill, or deploy operation. Focus is only observed.
 * Handles returned in snapshots are compared by reference identity by the controller.
 */
public interface CloseSurface {
    /** Foreground task on the main display. May be a different instance than {@code plan.main}. */
    TaskHandle mainForeground();

    /** Tasks currently on the source display. */
    List<TaskHandle> tasksOnSource();

    /** Tasks currently on the main display. */
    List<TaskHandle> tasksOnMain();

    /** Move {@code task} off the source toward main. Non-success must not be treated as migrated. */
    ActionResult migrateToMain(TaskHandle task);

    /** Confirm the task is restored on main. Anything other than SUCCESS is unconfirmed. */
    ActionResult restore(TaskHandle task);

    /** Release the source display/session. UNCERTAIN is not a successful close. */
    ActionResult release();
}
