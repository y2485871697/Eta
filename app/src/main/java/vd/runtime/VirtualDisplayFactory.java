package vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Parcel;
import android.view.Display;
import android.view.Surface;

/**
 * Creates one virtual display through reflection so this source still compiles against the public
 * SDK.
 *
 * <p>The retained owner is an {@link ImageReader}: its {@link Surface} is the display's output
 * surface and it stays alive for the whole owner session, so the display is never released while
 * frames can still arrive.
 *
 * <p>{@code DisplayManagerGlobal.createVirtualDisplay} is hidden and its signature has changed
 * across releases. Three shapes are tried explicitly (with a {@code VirtualDisplayConfig}, with a
 * {@code String uniqueId}, and the legacy listener-only form). If nothing matches, creation fails
 * with {@link OwnerProtocol#ERROR_DISPLAY_NOT_READY}); a missing signature is never reported as a
 * created display.
 *
 * <p>{@code IVirtualDisplayCallback} is an AIDL interface, so a plain {@link Proxy} is not a
 * binder. The proxy's {@code asBinder()} returns a real {@link Binder} that acknowledges every
 * transaction. That is enough for the display service to hold the callback token; lifecycle
 * callbacks are treated as best-effort notifications.
 */
final class VirtualDisplayFactory {
    /** uid 0 owns this package, which satisfies the display service's package-vs-uid check. */
    static final String OWNER_PACKAGE = "android";

    static final int DEFAULT_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private static final String DMG_CLASS = "android.hardware.display.DisplayManagerGlobal";
    private static final String CALLBACK_CLASS = "android.hardware.display.IVirtualDisplayCallback";
    private static final String CONFIG_CLASS = "android.hardware.display.VirtualDisplayConfig";
    private static final String CONFIG_BUILDER_CLASS =
            "android.hardware.display.VirtualDisplayConfig$Builder";

    static final class Created {
        final VirtualDisplay display;
        final ImageReader reader;
        final Surface surface;
        final Object callback;
        final int displayId;
        final String uniqueId;
        final String name;
        final int width;
        final int height;
        final int densityDpi;
        final int flags;

        Created(VirtualDisplay display, ImageReader reader, Surface surface, Object callback,
                int displayId, String uniqueId, String name, int width, int height, int densityDpi,
                int flags) {
            this.display = display;
            this.reader = reader;
            this.surface = surface;
            this.callback = callback;
            this.displayId = displayId;
            this.uniqueId = uniqueId;
            this.name = name;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.flags = flags;
        }
    }

    private VirtualDisplayFactory() {
    }

    static Created create(String name, int width, int height, int densityDpi, String uniqueId,
            int flags, int maxImages) throws OwnerException {
        if (name == null || name.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "name");
        }
        if (uniqueId == null || uniqueId.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "uniqueId");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "geometry");
        }

        Class<?> dmgClass = load(DMG_CLASS);
        Class<?> callbackClass = load(CALLBACK_CLASS);

        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                Math.max(1, maxImages));
        Surface surface = reader.getSurface();
        if (surface == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no surface");
        }

        Object callback = newCallback(callbackClass);

        Class<?> configClass = optionalClass(CONFIG_CLASS);
        Object config = buildConfig(configClass, name, width, height, densityDpi, flags, surface,
                uniqueId);

        Object global;
        try {
            Method getInstance = dmgClass.getMethod("getInstance");
            access(getInstance);
            global = getInstance.invoke(null);
        } catch (Exception ex) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display manager");
        }
        if (global == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display manager null");
        }

        Method method = findCreateMethod(dmgClass, configClass);
        if (method == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "create signature");
        }

        Object[] args = buildArgs(method, callback, config, name, width, height, densityDpi,
                surface, flags, uniqueId, configClass);

        Object result;
        try {
            access(method);
            result = method.invoke(global, args);
        } catch (InvocationTargetException ex) {
            reader.close();
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "create:" + cause.getClass().getSimpleName());
        } catch (Exception ex) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "create:" + ex.getClass().getSimpleName());
        }

        if (!(result instanceof VirtualDisplay)) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "create result");
        }
        VirtualDisplay display = (VirtualDisplay) result;
        Display bound = display.getDisplay();
        if (bound == null) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no display");
        }
        int displayId = bound.getDisplayId();
        if (displayId <= 0) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "main display id");
        }
        return new Created(display, reader, surface, callback, displayId, uniqueId, name, width,
                height, densityDpi, flags);
    }

    static void releaseQuietly(VirtualDisplay display) {
        if (display == null) {
            return;
        }
        try {
            display.release();
        } catch (Throwable ignored) {
            // best effort; the process is exiting anyway
        }
    }

    private static Object newCallback(Class<?> callbackClass) throws OwnerException {
        try {
            return Proxy.newProxyInstance(VirtualDisplayFactory.class.getClassLoader(),
                    new Class<?>[] {callbackClass}, new CallbackHandler());
        } catch (Throwable ex) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "callback:" + ex.getClass().getSimpleName());
        }
    }

    private static Object[] buildArgs(Method method, Object callback, Object config, String name,
            int width, int height, int densityDpi, Surface surface, int flags, String uniqueId,
            Class<?> configClass) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 11 && configClass != null && params[9] == configClass) {
            return new Object[] {callback, null, OWNER_PACKAGE, name, Integer.valueOf(width),
                    Integer.valueOf(height), Integer.valueOf(densityDpi), surface,
                    Integer.valueOf(flags), config, null};
        }
        if (params.length == 11 && params[9] == String.class) {
            return new Object[] {callback, null, OWNER_PACKAGE, name, Integer.valueOf(width),
                    Integer.valueOf(height), Integer.valueOf(densityDpi), surface,
                    Integer.valueOf(flags), uniqueId, null};
        }
        // legacy listener-only form
        return new Object[] {callback, null, OWNER_PACKAGE, name, Integer.valueOf(width),
                Integer.valueOf(height), Integer.valueOf(densityDpi), surface,
                Integer.valueOf(flags), null};
    }

    private static Method findCreateMethod(Class<?> dmgClass, Class<?> configClass) {
        Method legacy = null;
        for (Method candidate : dmgClass.getDeclaredMethods()) {
            if (!"createVirtualDisplay".equals(candidate.getName())) {
                continue;
            }
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 11 && configClass != null && params[9] == configClass) {
                return candidate;
            }
            if (params.length == 11 && params[9] == String.class) {
                return candidate;
            }
            if (params.length == 10 && legacy == null) {
                legacy = candidate;
            }
        }
        return legacy;
    }

    private static Object buildConfig(Class<?> configClass, String name, int width, int height,
            int densityDpi, int flags, Surface surface, String uniqueId) {
        if (configClass == null) {
            return null;
        }
        Class<?> builderClass = optionalClass(CONFIG_BUILDER_CLASS);
        if (builderClass == null) {
            return null;
        }
        try {
            Constructor<?> ctor = builderClass.getConstructor(String.class, int.class, int.class,
                    int.class);
            Object builder = ctor.newInstance(name, Integer.valueOf(width), Integer.valueOf(height),
                    Integer.valueOf(densityDpi));
            invokeIfPresent(builder, "setFlags", int.class, Integer.valueOf(flags));
            invokeIfPresent(builder, "setSurface", Surface.class, surface);
            invokeIfPresent(builder, "setUniqueId", String.class, uniqueId);
            Method build = builderClass.getMethod("build");
            access(build);
            return build.invoke(builder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeIfPresent(Object target, String name, Class<?> param, Object value) {
        try {
            Method method = target.getClass().getMethod(name, param);
            access(method);
            method.invoke(target, value);
        } catch (Throwable ignored) {
            // older builders simply lack this setter
        }
    }

    private static Class<?> load(String name) throws OwnerException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, name);
        }
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // public members stay callable
        }
    }

    /** Local stand-in for the hidden callback. Only {@code asBinder} carries a real binder. */
    private static final class CallbackHandler implements InvocationHandler {
        private final Binder binder = new AckBinder();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("asBinder".equals(name)) {
                return binder;
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == (args == null ? null : args[0]));
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(name)) {
                return "VdOwnerCallback";
            }
            // onPaused / onResumed / onStopped / onFirstFrame / setSurface etc.
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class) {
                return Integer.valueOf(0);
            }
            if (returnType == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    private static final class AckBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            // The hidden IVirtualDisplayCallback.Stub cannot be compiled against the public SDK.
            // Acknowledging keeps the display service from treating the token as dead; no reply
            // body is required because the protocol only notifies.
            return true;
        }
    }
}
