package vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ComponentName;
import android.content.Intent;

/**
 * Bounded, sanitized, read-only diagnostic of the platform root task inventory.
 *
 * <p>It exists to explain a read-only refusal such as {@link LaunchTargetOccupancy#UNKNOWN}: it
 * lists every root task the platform reports together with the fields that decide whether a task is
 * a real, identified task or an unreadable one. The owner attaches it to a {@code status} reply and
 * to an unknown-inventory launch failure under {@link #FIELD}.
 *
 * <p>Only {@code ActivityTaskManager.getAllRootTaskInfos()} (and, best effort,
 * {@code getTaskInfo(int, boolean)} to name children) are ever called. There is no migrate, remove,
 * focus, hide, release or any other mutation here, so collecting the diagnostic cannot disturb a
 * foreground task and cannot weaken a launch / handoff / release gate.
 *
 * <p>No user-visible text is copied out: an {@code Intent}'s data, extras, action, categories and a
 * task-description label are never read. Only package identifiers that already pass
 * {@link OwnerProtocol#isSafeIdentifier} are emitted, every list is capped, and a reflection or
 * service failure is reported as {@code known=false} with a stable code. A failed read is never
 * reported as an empty inventory.
 */
final class OwnerRootTaskDiagnostic {
    /** Response field this diagnostic is nested under. */
    static final String FIELD = "rootTaskInventory";
    /** Max root entries emitted; additional roots only set {@code truncated}. */
    static final int MAX_ROOTS = 16;
    /** Max child ids emitted per root. */
    static final int MAX_CHILD_IDS = 16;
    /** Max child names emitted per root. */
    static final int MAX_CHILD_NAMES = 8;
    /** Max characters of a single emitted identifier. */
    static final int MAX_NAME_CHARS = 96;

    private static final String ERROR_UNKNOWN = OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN;

    private OwnerRootTaskDiagnostic() {
    }

    /** Sanitized, immutable description of one root task. Every component value is a package. */
    static final class Root {
        final int id;
        final int displayId;
        final int userId;
        final int activityType;
        final int numActivities;
        final String base;
        final String baseActivity;
        final String topActivity;
        final String realActivity;
        final String origActivity;
        final boolean componentsKnown;
        final int[] childTaskIds;
        final String[] childTaskNames;
        final boolean childrenKnown;
        final boolean childNamesKnown;

        Root(int id, int displayId, int userId, int activityType, int numActivities,
                String base, String baseActivity, String topActivity, String realActivity,
                String origActivity, boolean componentsKnown, int[] childTaskIds,
                String[] childTaskNames, boolean childrenKnown, boolean childNamesKnown) {
            this.id = id;
            this.displayId = displayId;
            this.userId = userId;
            this.activityType = activityType;
            this.numActivities = numActivities;
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
            this.childTaskIds = childTaskIds == null ? new int[0] : childTaskIds;
            this.childTaskNames = childTaskNames == null ? new String[0] : childTaskNames;
            this.childrenKnown = childrenKnown;
            this.childNamesKnown = childNamesKnown;
        }
    }

    /** A read-only snapshot, or a fail-closed {@code known=false} marker. Never throws. */
    static JSONObject collect() {
        try {
            return toJson(readRoots());
        } catch (OwnerException ex) {
            return unknown(ex.code);
        } catch (Throwable ex) {
            return unknown(ERROR_UNKNOWN);
        }
    }

    /** Fail-closed marker for an unreadable inventory. */
    static JSONObject unknown(String errorCode) {
        JSONObject out = new JSONObject();
        try {
            out.put("known", false);
            out.put("error", errorCode == null || errorCode.isEmpty() ? ERROR_UNKNOWN : errorCode);
        } catch (JSONException ex) {
            throw new IllegalStateException("root task diagnostic failure", ex);
        }
        return out;
    }

    /** Renders sanitized roots with every list capped. Pure; no device access. */
    static JSONObject toJson(List<Root> roots) {
        if (roots == null) {
            return unknown(ERROR_UNKNOWN);
        }
        try {
            int total = roots.size();
            int emitted = Math.min(total, MAX_ROOTS);
            boolean truncated = total > MAX_ROOTS;
            JSONArray tasks = new JSONArray();
            for (int i = 0; i < emitted; i++) {
                Root root = roots.get(i);
                if (root == null) {
                    return unknown(ERROR_UNKNOWN);
                }
                if (root.childTaskIds.length > MAX_CHILD_IDS) {
                    truncated = true;
                }
                if (root.childTaskNames.length > MAX_CHILD_NAMES) {
                    truncated = true;
                }
                tasks.put(entry(root));
            }
            JSONObject out = new JSONObject();
            out.put("known", true);
            out.put("rootCount", total);
            out.put("emitted", emitted);
            out.put("truncated", truncated);
            out.put("tasks", tasks);
            return out;
        } catch (JSONException ex) {
            return unknown(ERROR_UNKNOWN);
        }
    }

    private static JSONObject entry(Root root) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("id", root.id);
        entry.put("displayId", root.displayId);
        entry.put("userId", root.userId);
        entry.put("activityType", root.activityType);
        entry.put("numActivities", root.numActivities);
        entry.put("componentsKnown", root.componentsKnown);
        putIdentifier(entry, "base", root.base);
        putIdentifier(entry, "baseActivity", root.baseActivity);
        putIdentifier(entry, "topActivity", root.topActivity);
        putIdentifier(entry, "realActivity", root.realActivity);
        putIdentifier(entry, "origActivity", root.origActivity);
        entry.put("childrenKnown", root.childrenKnown);
        entry.put("childTaskIds", boundedInts(root.childTaskIds));
        entry.put("childNamesKnown", root.childNamesKnown);
        entry.put("childTaskNames", boundedNames(root.childTaskNames));
        return entry;
    }

    private static void putIdentifier(JSONObject entry, String key, String value) throws JSONException {
        if (value != null) {
            entry.put(key, value);
        }
    }

    private static JSONArray boundedInts(int[] values) {
        JSONArray array = new JSONArray();
        int count = Math.min(values.length, MAX_CHILD_IDS);
        for (int i = 0; i < count; i++) {
            array.put(values[i]);
        }
        return array;
    }

    private static JSONArray boundedNames(String[] values) {
        JSONArray array = new JSONArray();
        int count = Math.min(values.length, MAX_CHILD_NAMES);
        for (int i = 0; i < count; i++) {
            array.put(values[i] == null ? JSONObject.NULL : values[i]);
        }
        return array;
    }

    /**
     * Package/component identifier that is safe to emit, or {@code null} when absent, blank, too
     * long or outside the identifier whitelist.
     */
    static String safeIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_NAME_CHARS) {
            return null;
        }
        return OwnerProtocol.isSafeIdentifier(value) ? value : null;
    }

    /** Package of a {@link ComponentName}, or {@code null} when absent or unsafe. */
    static String componentPackage(Object value) {
        if (!(value instanceof ComponentName)) {
            return null;
        }
        return safeIdentifier(((ComponentName) value).getPackageName());
    }

    /** Package of a task base intent; never reads data, extras, action or categories. */
    static String intentPackage(Object value) {
        if (!(value instanceof Intent)) {
            return null;
        }
        Intent intent = (Intent) value;
        String component = componentPackage(intent.getComponent());
        return component != null ? component : safeIdentifier(intent.getPackage());
    }

    private static List<Root> readRoots() throws OwnerException {
        Object service;
        Class<?> taskManager;
        Method getAll;
        try {
            service = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            if (service == null) {
                throw new OwnerException(ERROR_UNKNOWN, "ATM_NULL");
            }
            taskManager = Class.forName("android.app.IActivityTaskManager");
            getAll = declaredOrNull(taskManager, "getAllRootTaskInfos");
            if (getAll == null) {
                throw new NoSuchMethodException("getAllRootTaskInfos");
            }
        } catch (OwnerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OwnerException(ERROR_UNKNOWN, describe(ex));
        }
        Object result;
        try {
            result = getAll.invoke(service);
        } catch (Exception ex) {
            throw new OwnerException(ERROR_UNKNOWN, describe(ex));
        }
        boolean isList = result instanceof List;
        if (!isList && (result == null || !result.getClass().isArray())) {
            throw new OwnerException(ERROR_UNKNOWN, "TASK_LIST");
        }
        int count = isList ? ((List<?>) result).size() : Array.getLength(result);
        List<Root> roots = new ArrayList<Root>(count);
        try {
            for (int i = 0; i < count; i++) {
                Object info = isList ? ((List<?>) result).get(i) : Array.get(result, i);
                roots.add(readRoot(info, service, taskManager));
            }
        } catch (Exception ex) {
            throw new OwnerException(ERROR_UNKNOWN, describe(ex));
        }
        return roots;
    }

    private static Root readRoot(Object info, Object service, Class<?> taskManager) throws Exception {
        if (info == null) {
            throw new IllegalStateException("TASK_NULL");
        }
        int id = requiredInt(info, "taskId");
        int displayId = firstInt(info, -1, "displayId");
        if (displayId < 0) {
            displayId = callInt(info, "getDisplayId", -1);
        }
        int userId = firstInt(info, -1, "userId");
        int activityType = callInt(info, "getActivityType", -1);
        if (activityType < 0) {
            activityType = firstInt(info, -1, "activityType", "mActivityType");
        }
        int numActivities = firstInt(info, -1, "numActivities");

        boolean componentsKnown = true;
        String base = null;
        String baseActivity = null;
        String topActivity = null;
        String realActivity = null;
        String origActivity = null;
        try {
            base = intentPackage(field(info, "baseIntent"));
        } catch (Exception ex) {
            componentsKnown = false;
        }
        try {
            baseActivity = componentPackage(field(info, "baseActivity"));
        } catch (Exception ex) {
            componentsKnown = false;
        }
        try {
            topActivity = componentPackage(field(info, "topActivity"));
        } catch (Exception ex) {
            componentsKnown = false;
        }
        try {
            realActivity = componentPackage(field(info, "realActivity"));
        } catch (Exception ex) {
            componentsKnown = false;
        }
        try {
            origActivity = componentPackage(field(info, "origActivity"));
        } catch (Exception ex) {
            componentsKnown = false;
        }

        int[] childTaskIds = new int[0];
        boolean childrenKnown;
        try {
            Object raw = field(info, "childTaskIds");
            if (raw == null) {
                childrenKnown = true;
            } else if (raw instanceof int[]) {
                childTaskIds = (int[]) raw;
                childrenKnown = true;
            } else {
                throw new IllegalStateException("childTaskIds type");
            }
        } catch (Exception ex) {
            childrenKnown = false;
        }

        String[] childTaskNames = new String[0];
        boolean childNamesKnown = false;
        if (childrenKnown && childTaskIds.length == 0) {
            // No children to name, so the (empty) name list is complete.
            childNamesKnown = true;
        } else if (childrenKnown) {
            String[] resolved = resolveChildNames(service, taskManager, childTaskIds);
            if (resolved != null && hasName(resolved)) {
                childTaskNames = resolved;
                childNamesKnown = true;
            }
        }
        return new Root(id, displayId, userId, activityType, numActivities, base, baseActivity,
                topActivity, realActivity, origActivity, componentsKnown, childTaskIds,
                childTaskNames, childrenKnown, childNamesKnown);
    }

    /**
     * Best-effort child task names for up to {@link #MAX_CHILD_IDS} children; {@code null} means the
     * child lookup itself is unavailable. Names are resolved here without the render-time cap so
     * {@link #toJson} can still flag when {@link #MAX_CHILD_NAMES} dropped some.
     */
    private static String[] resolveChildNames(Object service, Class<?> taskManager, int[] childTaskIds) {
        Method twoArg = declaredOrNull(taskManager, "getTaskInfo", int.class, boolean.class);
        Method oneArg = twoArg == null ? declaredOrNull(taskManager, "getTaskInfo", int.class) : null;
        Method lookup = twoArg != null ? twoArg : oneArg;
        if (lookup == null) {
            return null;
        }
        int count = Math.min(childTaskIds.length, MAX_CHILD_IDS);
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            Object child = null;
            try {
                child = twoArg != null
                        ? twoArg.invoke(service, Integer.valueOf(childTaskIds[i]), Boolean.FALSE)
                        : oneArg.invoke(service, Integer.valueOf(childTaskIds[i]));
            } catch (Exception ignored) {
                // An unnamed child is reported as null, never as a guess.
            }
            names[i] = childPackage(child);
        }
        return names;
    }

    private static boolean hasName(String[] names) {
        for (int i = 0; i < names.length; i++) {
            if (names[i] != null) {
                return true;
            }
        }
        return false;
    }

    private static String childPackage(Object child) {
        if (child == null) {
            return null;
        }
        String base = intentPackage(optionalField(child, "baseIntent"));
        return base != null ? base : componentPackage(optionalField(child, "topActivity"));
    }

    private static int requiredInt(Object target, String name) throws Exception {
        Object value = field(target, name);
        if (!(value instanceof Integer)) {
            throw new IllegalStateException("BAD_INT");
        }
        return ((Integer) value).intValue();
    }

    private static int firstInt(Object target, int fallback, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Object value = field(target, names[i]);
                if (value instanceof Integer) {
                    return ((Integer) value).intValue();
                }
            } catch (Exception ignored) {
                // try the next alias
            }
        }
        return fallback;
    }

    private static int callInt(Object target, String name, int fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            Method method = target.getClass().getMethod(name);
            access(method);
            Object value = method.invoke(target);
            if (value instanceof Integer) {
                return ((Integer) value).intValue();
            }
        } catch (Throwable ignored) {
            // an optional derived field
        }
        return fallback;
    }

    private static Object optionalField(Object target, String name) {
        try {
            return field(target, name);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) {
            throw new IllegalStateException("null field " + name);
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field found = type.getDeclaredField(name);
                if (!Modifier.isPublic(found.getModifiers())) {
                    access(found);
                }
                return found.get(target);
            } catch (NoSuchFieldException ignored) {
                // parent class
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method declaredOrNull(Class<?> type, String name, Class<?>... params) {
        try {
            Method method = type.getDeclaredMethod(name, params);
            access(method);
            return method;
        } catch (Throwable ex) {
            return null;
        }
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // a public member can still be invoked
        }
    }

    private static String describe(Exception ex) {
        Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null
                ? ex.getCause() : ex;
        return cause.getClass().getSimpleName();
    }
}
