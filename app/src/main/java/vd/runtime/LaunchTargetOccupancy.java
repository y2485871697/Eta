package vd.runtime;

import java.util.List;

/** Conservative, pure-Java preflight policy. Unknown task identity is never a clean inventory. */
final class LaunchTargetOccupancy {
    static final String ACTIVE = "TARGET_TASK_ACTIVE";
    static final String RECENT = "TARGET_TASK_RECENT";
    static final String UNKNOWN = "TARGET_INVENTORY_UNKNOWN";

    /**
     * Sanitized snapshot of one root task, extended with the identity of its child tasks.
     *
     * <p>A root task can be a container whose activities live in nested child tasks, so proving a
     * root is not the target also requires proving none of its children is the target. The raw
     * {@code childTaskIds} array and the parallel {@code childTaskNames} package per id are carried
     * here (a {@code null} element means the platform did not name that child) so the decision is a
     * pure function that can be unit tested without a device.
     */
    static final class Root {
        final int taskId;
        final String base, baseActivity, topActivity, realActivity, origActivity;
        final boolean componentsKnown;
        final int numActivities; // -1 means unreadable
        final boolean childIdsKnown;
        final int[] childTaskIds; // raw platform ids; may contain the root's own id or -1 marker
        final boolean childNamesKnown;
        final String[] childTaskNames; // package per raw child id; a null element is unreadable

        Root(int taskId, String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown, int numActivities,
                boolean childIdsKnown, int[] childTaskIds, boolean childNamesKnown,
                String[] childTaskNames) {
            this.taskId = taskId;
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
            this.numActivities = numActivities;
            this.childIdsKnown = childIdsKnown;
            this.childTaskIds = childTaskIds == null ? new int[0] : childTaskIds;
            this.childNamesKnown = childNamesKnown;
            this.childTaskNames = childTaskNames == null ? new String[0] : childTaskNames;
        }
    }

    static final class Recent {
        final String base, baseActivity, topActivity, realActivity, origActivity;
        final boolean componentsKnown;

        Recent(String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown) {
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
        }
    }

    static final class Decision {
        final String code;
        final String detail;
        Decision(String code, String detail) { this.code = code; this.detail = detail; }
        boolean rejects() { return code != null; }
    }

    private LaunchTargetOccupancy() { }

    private static boolean contains(String target, String... packages) {
        for (String candidate : packages) if (target.equals(candidate)) return true;
        return false;
    }

    /** -1 is the platform's non-task child marker, never another task id. */
    static boolean hasForeignChild(int selfId, int[] ids) {
        if (selfId < 0 || ids == null) return true; // unreadable identity is never an empty shell
        for (int child : ids) if (child >= 0 && child != selfId) return true;
        return false;
    }

    /** A child id that denotes a different, real task; the root's own id and -1 are markers. */
    private static boolean isForeignChild(int selfId, int id) {
        return id >= 0 && id != selfId;
    }

    private static boolean hasParallelChildNames(Root root) {
        return root.childIdsKnown && root.childNamesKnown
                && root.childTaskNames.length == root.childTaskIds.length;
    }

    /** True when a readable, identified foreign child is the target package. */
    private static boolean targetChild(Root root, String target) {
        if (!hasParallelChildNames(root)) return false;
        int[] ids = root.childTaskIds;
        String[] names = root.childTaskNames;
        for (int i = 0; i < ids.length; i++) {
            if (isForeignChild(root.taskId, ids[i]) && target.equals(names[i])) return true;
        }
        return false;
    }

    /**
     * True only when the platform evidence proves this root and every child task is not the target.
     *
     * <p>A root with an unreadable self identity, unreadable child ids, missing or non-parallel
     * child names, or a foreign child whose identity is unknown is never clearable: the caller must
     * fail closed rather than assume the unnamed child is harmless. A self-absent root is only
     * clearable as an empty container: it must report zero activities on top of the readable,
     * identified non-target children.
     */
    private static boolean provenNonTarget(Root root) {
        if (!root.componentsKnown) return false;
        if (!hasParallelChildNames(root)) return false;
        int[] ids = root.childTaskIds;
        String[] names = root.childTaskNames;
        for (int i = 0; i < ids.length; i++) {
            if (isForeignChild(root.taskId, ids[i]) && names[i] == null) return false;
        }
        boolean selfAbsent = root.base == null && root.baseActivity == null
                && root.topActivity == null && root.realActivity == null && root.origActivity == null;
        if (!selfAbsent) return true; // a known, non-target self identity plus identified children
        return root.numActivities == 0; // an empty container with only identified non-target children
    }

    static Decision decide(String target, List<Root> roots, List<Recent> recents) {
        if (target == null || target.isEmpty())
            return new Decision(UNKNOWN, "target package missing");
        String unknown = null;
        if (roots == null || roots.isEmpty()) unknown = "root inventory empty";
        if (recents == null) unknown = "recent inventory missing";
        for (Root root : roots == null ? java.util.Collections.<Root>emptyList() : roots) {
            if (root == null) { if (unknown == null) unknown = "null root"; continue; }
            if (contains(target, root.base, root.baseActivity, root.topActivity,
                    root.realActivity, root.origActivity))
                return new Decision(ACTIVE, "active task id=" + root.taskId);
            if (targetChild(root, target))
                return new Decision(ACTIVE, "active child task of id=" + root.taskId);
            if (!provenNonTarget(root) && unknown == null)
                unknown = "root task id=" + root.taskId + " identity unknown";
        }
        for (Recent recent : recents == null ? java.util.Collections.<Recent>emptyList() : recents) {
            if (recent == null) { if (unknown == null) unknown = "null recent"; continue; }
            if (contains(target, recent.base, recent.baseActivity,
                    recent.topActivity, recent.realActivity, recent.origActivity))
                return new Decision(RECENT, "existing recent task");
            if ((!recent.componentsKnown || (recent.base == null && recent.baseActivity == null
                    && recent.topActivity == null && recent.realActivity == null
                    && recent.origActivity == null)) && unknown == null)
                unknown = "recent identity unknown";
        }
        return unknown == null ? new Decision(null, "") : new Decision(UNKNOWN, unknown);
    }
}
