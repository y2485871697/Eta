package vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only task inventory for the owner process.
 *
 * <p>Only {@code getAllRootTaskInfos} is called. There is no migrate, restore, kill or focus
 * mutation here, so probing the source display cannot disturb the user's foreground task. Any
 * reflection failure is reported as {@link OwnerProtocol#ERROR_SOURCE_STATE_UNKNOWN}; a failed
 * read is never reported as an empty source.
 */
final class OwnerTaskInventory {
    private static final String TOKEN_CLASS = "android.window.WindowContainerToken";

    static final class Seen {
        final int taskId;
        final int displayId;
        final Object binder;

        Seen(int taskId, int displayId, Object binder) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.binder = binder;
        }
    }

    private OwnerTaskInventory() {
    }

    /** Every root task as observed through ActivityTaskManager this JVM. */
    static List<Seen> readAll() throws OwnerException {
        try {
            Object service = activityTaskManager();
            Method getAll = method(service.getClass(), "getAllRootTaskInfos");
            Object result = getAll.invoke(service);
            return parseAll(result);
        } catch (OwnerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, describe(ex));
        }
    }

    /** Root tasks currently on one secondary display. */
    static List<Seen> readOnDisplay(int displayId) throws OwnerException {
        if (displayId <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, "displayId");
        }
        List<Seen> all = readAll();
        List<Seen> onDisplay = new ArrayList<Seen>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).displayId == displayId) {
                onDisplay.add(all.get(i));
            }
        }
        return Collections.unmodifiableList(onDisplay);
    }

    private static Object activityTaskManager() throws OwnerException {
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Method getService = atm.getMethod("getService");
            access(getService);
            Object service = getService.invoke(null);
            if (service == null) {
                throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, "ATM_NULL");
            }
            return service;
        } catch (OwnerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, describe(ex));
        }
    }

    private static List<Seen> parseAll(Object result) throws OwnerException {
        if (result == null) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, "TASKS_NULL");
        }
        boolean isList = result instanceof List;
        if (!isList && !result.getClass().isArray()) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, "TASKS_TYPE");
        }
        int count = isList ? ((List<?>) result).size() : Array.getLength(result);
        List<Seen> out = new ArrayList<Seen>(count);
        try {
            for (int i = 0; i < count; i++) {
                Object info = isList ? ((List<?>) result).get(i) : Array.get(result, i);
                out.add(parseOne(info));
            }
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, describe(ex));
        }
        return Collections.unmodifiableList(out);
    }

    private static Seen parseOne(Object info) throws Exception {
        if (info == null) {
            throw new IllegalStateException("TASK_NULL");
        }
        int taskId = asInt(field(info, "taskId"));
        int displayId = asInt(field(info, "displayId"));
        Object binder = tokenBinder(field(info, "token"));
        if (taskId <= 0) {
            throw new IllegalStateException("TASK_ID");
        }
        if (binder == null) {
            throw new IllegalStateException("EMPTY_BINDER");
        }
        return new Seen(taskId, displayId, binder);
    }

    private static Object tokenBinder(Object token) throws Exception {
        if (token == null || !TOKEN_CLASS.equals(token.getClass().getName())) {
            return null;
        }
        Object raw = token.getClass().getMethod("asBinder").invoke(token);
        return raw;
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Class<?>[] faces = current.getInterfaces();
            for (int i = 0; i < faces.length; i++) {
                Method found = publicMethod(faces[i], name);
                if (found != null) {
                    return found;
                }
            }
            Method found = publicMethod(current, name);
            if (found != null) {
                return found;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Method publicMethod(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        Class<?>[] faces = type.getInterfaces();
        for (int i = 0; i < faces.length; i++) {
            Method found = publicMethod(faces[i], name);
            if (found != null) {
                return found;
            }
        }
        try {
            if (type.isInterface() || Modifier.isPublic(type.getModifiers())) {
                Method candidate = type.getDeclaredMethod(name);
                if (Modifier.isPublic(candidate.getModifiers())) {
                    access(candidate);
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // walk up
        }
        return publicMethod(type.getSuperclass(), name);
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) {
            throw new NullPointerException(name);
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field found = type.getDeclaredField(name);
                if (!Modifier.isPublic(found.getModifiers())) {
                    access(found);
                }
                return found.get(target);
            } catch (NoSuchFieldException ignored) {
                // parent
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int asInt(Object value) {
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        throw new IllegalStateException("BAD_INT");
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // a public method can still be invoked
        }
    }

    private static String describe(Exception ex) {
        Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null
                ? ex.getCause() : ex;
        return cause.getClass().getSimpleName();
    }
}
