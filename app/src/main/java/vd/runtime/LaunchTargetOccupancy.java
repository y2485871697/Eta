package vd.runtime;

import java.util.List;

/** Conservative, pure-Java preflight policy. Unknown task identity is never a clean inventory. */
final class LaunchTargetOccupancy {
    static final String ACTIVE = "TARGET_TASK_ACTIVE";
    static final String RECENT = "TARGET_TASK_RECENT";
    static final String UNKNOWN = "TARGET_INVENTORY_UNKNOWN";

    static final class Root {
        final int taskId;
        final String base, baseActivity, topActivity, realActivity, origActivity;
        final boolean componentsKnown;
        final int numActivities; // -1 means unreadable
        final boolean childrenKnown, hasForeignChild;

        Root(int taskId, String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown, int numActivities,
                boolean childrenKnown, boolean hasForeignChild) {
            this.taskId = taskId;
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
            this.numActivities = numActivities;
            this.childrenKnown = childrenKnown;
            this.hasForeignChild = hasForeignChild;
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

    static Decision decide(String target, List<Root> roots, List<Recent> recents) {
        if (target == null || target.isEmpty())
            return new Decision(UNKNOWN, "target package missing");
        String unknown = null;
        if (roots == null || roots.isEmpty()) unknown = "root inventory empty";
        if (recents == null) unknown = "recent inventory missing";
        for (Root root : roots == null ? java.util.Collections.<Root>emptyList() : roots) {
            if (root == null) { unknown = "null root"; continue; }
            if (contains(target, root.base, root.baseActivity, root.topActivity, root.realActivity, root.origActivity))
                return new Decision(ACTIVE, "active task id=" + root.taskId);
            // childTaskIds may include the root's own id; only OTHER ids are unverified children.
            boolean emptyShell = root.componentsKnown && root.base == null
                    && root.baseActivity == null && root.topActivity == null
                    && root.realActivity == null && root.origActivity == null && root.numActivities == 0
                    && root.childrenKnown && !root.hasForeignChild;
            boolean identifiedOther = root.componentsKnown
                    && (root.base != null || root.baseActivity != null || root.topActivity != null
                    || root.realActivity != null || root.origActivity != null)
                    && root.childrenKnown && !root.hasForeignChild;
            if (!emptyShell && !identifiedOther && unknown == null)
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
