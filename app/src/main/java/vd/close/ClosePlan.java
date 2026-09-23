package vd.close;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Immutable close plan. Identity fields are non-null and fixed for the session.
 * {@link #main} is the frozen main-display foreground handle.
 */
public final class ClosePlan {
    public final TaskHandle main;
    public final DisplayHandle source;
    public final List<TaskHandle> targets;

    public ClosePlan(TaskHandle main, DisplayHandle source, List<TaskHandle> targets) {
        if (main == null) {
            throw new NullPointerException("main");
        }
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (targets == null) {
            throw new NullPointerException("targets");
        }
        List<TaskHandle> copy = new ArrayList<TaskHandle>();
        IdentityHashMap<TaskHandle, Boolean> seen = new IdentityHashMap<TaskHandle, Boolean>();
        for (TaskHandle target : targets) {
            if (target == null) {
                throw new NullPointerException("target");
            }
            if (target == main) {
                throw new IllegalArgumentException("target task must not be main");
            }
            if (seen.put(target, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate target");
            }
            copy.add(target);
        }
        this.main = main;
        this.source = source;
        this.targets = Collections.unmodifiableList(copy);
    }
}
