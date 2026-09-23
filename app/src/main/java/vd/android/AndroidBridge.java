package vd.android;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Read-only ATM and display reflection. No migrate, restore, release, file, or display create. */
final class AndroidBridge implements AndroidReadOnlyBackend.Reader {
    private static final String TOKEN = "android.window.WindowContainerToken";

    final Object service;
    final Object serviceBinder;
    private final Method alive;
    private final Method getAll;
    private final Method getFocused;
    private final Class<?> iBinder;
    private Object displays;
    private Method displayInfo;

    static AndroidBridge open() {
        try {
            return new AndroidBridge();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("ATM_OPEN_FAILED", ex);
        }
    }

    private AndroidBridge() throws Exception {
        Object uid = Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        if (!(uid instanceof Integer) || ((Integer) uid).intValue() != 0) {
            throw new IllegalStateException("UID");
        }
        Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
        Method getService = atmClass.getMethod("getService");
        access(getService);
        service = getService.invoke(null);
        if (service == null) throw new IllegalStateException("ATM_NULL");
        iBinder = Class.forName("android.os.IBinder");
        serviceBinder = must(Class.forName("android.os.IInterface"), service.getClass(), "asBinder").invoke(service);
        if (!iBinder.isInstance(serviceBinder)) throw new IllegalStateException("ATM_BINDER");
        alive = iBinder.getMethod("isBinderAlive");
        if (!aliveNow()) throw new IllegalStateException("SERVICE_DEAD");
        Class<?> iface = Class.forName("android.app.IActivityTaskManager");
        getAll = must(iface, service.getClass(), "getAllRootTaskInfos");
        getFocused = must(iface, service.getClass(), "getFocusedRootTaskInfo");
    }

    public AndroidReadOnlyBackend.Snapshot read() {
        try {
            if (!aliveNow()) throw new IllegalStateException("SERVICE_DEAD");
            Object all = getAll.invoke(service);
            Object focused = getFocused.invoke(service);
            if (focused == null) throw new IllegalStateException("FOCUSED_MISSING");
            return new AndroidReadOnlyBackend.Snapshot(parseAll(all), parseOne(focused));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw wrap("READ_FAILED", ex);
        }
    }

    public boolean displayExists(int displayId) {
        try {
            if (displayInfo == null) {
                Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
                Method getInstance = global.getMethod("getInstance");
                access(getInstance);
                Object found = getInstance.invoke(null);
                if (found == null) throw new IllegalStateException("DISPLAY_SERVICE");
                Method info = must(global, found.getClass(), "getDisplayInfo", int.class);
                displays = found;
                displayInfo = info;
            }
            return displayInfo.invoke(displays, Integer.valueOf(displayId)) != null;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw wrap("SOURCE_QUERY_FAILED", ex);
        }
    }

    private boolean aliveNow() throws Exception {
        return Boolean.TRUE.equals(alive.invoke(serviceBinder));
    }

    private List<AndroidReadOnlyBackend.Seen> parseAll(Object result) throws Exception {
        boolean list = result instanceof List;
        if (!list && (result == null || !result.getClass().isArray())) {
            throw new IllegalStateException("TASKS_MISSING");
        }
        int count = list ? ((List<?>) result).size() : Array.getLength(result);
        ArrayList<AndroidReadOnlyBackend.Seen> out = new ArrayList<AndroidReadOnlyBackend.Seen>();
        for (int i = 0; i < count; i++) {
            out.add(parseOne(list ? ((List<?>) result).get(i) : Array.get(result, i)));
        }
        return out;
    }

    private AndroidReadOnlyBackend.Seen parseOne(Object info) throws Exception {
        if (info == null) throw new IllegalStateException("TASK_NULL");
        Object binder = tokenBinder(field(info, "token"));
        if (binder == null) throw new IllegalStateException("EMPTY_BINDER");
        return new AndroidReadOnlyBackend.Seen(
                asInt(field(info, "taskId")), asInt(field(info, "displayId")), binder);
    }

    private Object tokenBinder(Object token) throws Exception {
        if (token == null || !TOKEN.equals(token.getClass().getName())) return null;
        Object raw = token.getClass().getMethod("asBinder").invoke(token);
        return iBinder.isInstance(raw) ? raw : null;
    }

    private static Method must(Class<?> primary, Class<?> fallback, String name, Class<?>... params)
            throws Exception {
        Method method = publicMethod(primary, name, params);
        if (method == null) method = publicMethod(fallback, name, params);
        if (method == null) throw new NoSuchMethodException(name);
        access(method);
        return method;
    }

    /** Public method on a public type or interface, never from a binder-proxy declaration. */
    private static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null) return null;
        Class<?>[] faces = type.getInterfaces();
        for (int i = 0; i < faces.length; i++) {
            Method found = publicMethod(faces[i], name, params);
            if (found != null) return found;
        }
        try {
            if (type.isInterface() || Modifier.isPublic(type.getModifiers())) {
                Method method = type.getDeclaredMethod(name, params);
                if (Modifier.isPublic(method.getModifiers())) return method;
            }
        } catch (Throwable ignored) {
            // try the superclass
        }
        return publicMethod(type.getSuperclass(), name, params);
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) throw new NullPointerException(name);
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field found = type.getDeclaredField(name);
                if (!Modifier.isPublic(found.getModifiers())) access(found);
                return found.get(target);
            } catch (NoSuchFieldException ignored) {
                // parent
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int asInt(Object value) {
        if (value instanceof Integer) return ((Integer) value).intValue();
        throw new IllegalStateException("BAD_INT");
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // a public method can still be invoked
        }
    }

    private static IllegalStateException wrap(String code, Exception ex) {
        Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null ? ex.getCause() : ex;
        return new IllegalStateException(code + ":" + cause.getClass().getSimpleName(), cause);
    }
}
