package vd.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only task-set mismatch. Never authorizes adoption, removal or migration. */
final class OwnerTaskInventoryMismatch extends Exception {
    static final String CODE = "HANDOFF_TASK_SET_CHANGED";
    final Set<Integer> missing;
    final Set<Integer> foreign;

    private OwnerTaskInventoryMismatch(Set<Integer> missing, Set<Integer> foreign) {
        super("task set changed");
        this.missing = java.util.Collections.unmodifiableSet(missing);
        this.foreign = java.util.Collections.unmodifiableSet(foreign);
    }

    /** Null means no task-set mismatch, NOT that task identity or lineage is proven. */
    static OwnerTaskInventoryMismatch detect(Collection<Integer> registered,
            Collection<Integer> liveSource) {
        if (registered == null || liveSource == null)
            throw new IllegalArgumentException("unknown task inventory");
        Set<Integer> old = checked(registered), live = checked(liveSource);
        Set<Integer> missing = new LinkedHashSet<Integer>(old);
        missing.removeAll(live);
        Set<Integer> foreign = new LinkedHashSet<Integer>(live);
        foreign.removeAll(old);
        return missing.isEmpty() && foreign.isEmpty() ? null
                : new OwnerTaskInventoryMismatch(missing, foreign);
    }

    private static Set<Integer> checked(Collection<Integer> values) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        for (Integer value : values)
            if (value == null || value <= 0 || !out.add(value))
                throw new IllegalArgumentException("unknown task inventory");
        return out;
    }

    /** Numeric samples only; bounded and contains no app Intent, URI or token. */
    String phase() {
        StringBuilder out = new StringBuilder("preflight:inventory");
        if (!missing.isEmpty()) out.append(":missing").append(missing.iterator().next());
        if (!foreign.isEmpty()) out.append(":unowned").append(foreign.iterator().next());
        return out.toString();
    }
}
