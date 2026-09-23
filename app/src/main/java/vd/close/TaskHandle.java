package vd.close;

/**
 * Task identity as seen by the close state machine.
 *
 * <p>{@link #equals(Object)} is the logical id. Liveness, focus, and "same handle"
 * checks must use reference identity ({@code ==}). A replacement handle can share
 * an id and still be a different task instance.
 */
public final class TaskHandle {
    public final String id;

    public TaskHandle(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id");
        }
        this.id = id;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TaskHandle)) {
            return false;
        }
        return id.equals(((TaskHandle) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "TaskHandle(" + id + ")@" + System.identityHashCode(this);
    }
}
