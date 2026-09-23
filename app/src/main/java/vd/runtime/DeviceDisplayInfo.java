package vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads the current device geometry so the owner never hardcodes a width, height or density and
 * never invents a display id. Values come from the framework's {@code DisplayInfo} for the default
 * display; if they cannot be read, creation fails instead of falling back to a magic number.
 */
final class DeviceDisplayInfo {
    static final class Geometry {
        final int width;
        final int height;
        final int densityDpi;

        Geometry(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }

    private DeviceDisplayInfo() {
    }

    static Geometry defaultGeometry() throws OwnerException {
        Object displayInfo = defaultDisplayInfo();
        Integer width = readInt(displayInfo, "logicalWidth", "getLogicalWidth");
        Integer height = readInt(displayInfo, "logicalHeight", "getLogicalHeight");
        Integer density = readInt(displayInfo, "logicalDensityDpi", "getLogicalDensityDpi");
        if (density == null) {
            density = readInt(displayInfo, "densityDpi", "getDensityDpi");
        }
        if (width == null || height == null || density == null
                || width.intValue() <= 0 || height.intValue() <= 0 || density.intValue() <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display info");
        }
        return new Geometry(width.intValue(), height.intValue(), density.intValue());
    }

    private static Object defaultDisplayInfo() throws OwnerException {
        try {
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = dmgClass.getMethod("getInstance");
            access(getInstance);
            Object global = getInstance.invoke(null);
            if (global == null) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display manager");
            }
            Method getInfo = dmgClass.getMethod("getDisplayInfo", int.class);
            access(getInfo);
            Object info = getInfo.invoke(global, Integer.valueOf(0));
            if (info == null) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display info null");
            }
            return info;
        } catch (OwnerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "display info:" + ex.getClass().getSimpleName());
        }
    }

    private static Integer readInt(Object target, String fieldName, String methodName) {
        Object fromField = readField(target, fieldName);
        if (fromField instanceof Integer) {
            return (Integer) fromField;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            access(method);
            Object value = method.invoke(target);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                access(field);
                return field.get(target);
            } catch (Throwable ignored) {
                // parent
            }
        }
        return null;
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // public members stay callable
        }
    }
}
