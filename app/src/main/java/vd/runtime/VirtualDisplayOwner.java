package vd.runtime;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * The live owner of exactly one created virtual display.
 *
 * <p>A single owner instance binds a single display: the display id comes from the created
 * {@code VirtualDisplay}, the unique id is generated at creation, and every operation refuses to
 * act on any other display. Operations run on the owner's looper thread, which is how commands are
 * serialized; the IPC layer only ever posts to that handler.
 *
 * <p>{@code release} refuses unless the source display is currently observed empty and every
 * observed task binder is stable. {@code handoff} is named by the protocol but not implemented, and
 * reports that explicitly instead of pretending to move tasks. Nothing here kills a task or a user
 * process.
 */
public final class VirtualDisplayOwner {
    private static final int MAX_IMAGES = 2;
    private static final int MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final int MAX_COORDINATE = 100_000;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final VirtualDisplayFactory.Created created;
    private final OwnerFrameStore frames;
    private final OwnerTaskRegistry registry = new OwnerTaskRegistry();
    private final Handler handler;
    private volatile boolean released;

    private VirtualDisplayOwner(VirtualDisplayFactory.Created created, OwnerFrameStore frames,
            Handler handler) {
        this.created = created;
        this.frames = frames;
        this.handler = handler;
    }

    /** Creates the display and binds the retained ImageReader owner. Called on the owner looper. */
    public static VirtualDisplayOwner create(String name, Integer width, Integer height,
            Integer densityDpi, Handler handler) throws OwnerException {
        if (handler == null) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "handler");
        }
        if (Looper.myLooper() != handler.getLooper()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "create thread");
        }
        String displayName = name == null || name.isEmpty() ? "eta-vd" : name;
        if (!OwnerProtocol.isSafeText(displayName, 128)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "name");
        }
        DeviceDisplayInfo.Geometry geometry = DeviceDisplayInfo.defaultGeometry();
        int w = width == null ? geometry.width : width.intValue();
        int h = height == null ? geometry.height : height.intValue();
        int d = densityDpi == null ? geometry.densityDpi : densityDpi.intValue();
        if (w <= 0 || h <= 0 || d <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "geometry");
        }
        String uniqueId = newUniqueId();
        VirtualDisplayFactory.Created created = VirtualDisplayFactory.create(displayName, w, h, d,
                uniqueId, VirtualDisplayFactory.DEFAULT_FLAGS, MAX_IMAGES);
        OwnerFrameStore frames = new OwnerFrameStore();
        created.reader.setOnImageAvailableListener(frames, handler);
        return new VirtualDisplayOwner(created, frames, handler);
    }

    public boolean isReleased() {
        return released;
    }

    public int displayId() {
        return created.displayId;
    }

    public String uniqueId() {
        return created.uniqueId;
    }

    public JSONObject status() throws OwnerException {
        requireLive();
        SourceProbe source = probeSource();
        JSONObject out = new JSONObject();
        try {
            out.put("ready", true);
            out.put("displayId", created.displayId);
            out.put("uniqueId", created.uniqueId);
            out.put("name", created.name);
            out.put("width", created.width);
            out.put("height", created.height);
            out.put("densityDpi", created.densityDpi);
            out.put("flags", created.flags);
            out.put("ownerPackage", VirtualDisplayFactory.OWNER_PACKAGE);
            out.put("frameCount", frames.frameCount());
            out.put("hasFrame", frames.hasFrame());
            out.put("frameTimestampNs", frames.latestTimestampNs());
            out.put("session", released ? "released" : "active");
            out.put("sourceState", source.state);
            if (source.known()) {
                out.put("sourceEmpty", source.taskCount == 0);
                out.put("sourceTaskCount", source.taskCount);
            } else {
                out.put("sourceError", source.errorCode);
            }
            out.put("retainedTaskIds", intArray(registry.retainedTaskIds()));
            out.put("supported", stringArray(OwnerProtocol.SUPPORTED_OPS));
            out.put("missing", stringArray(OwnerProtocol.MISSING_OPS));
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "status");
        }
        return out;
    }

    public JSONObject launch(JSONObject request) throws OwnerException {
        requireLive();
        OwnerProtocol.Request parsed = wrap(request);
        int displayId = optionalDisplay(parsed);
        String packageName = parsed.optionalString("package");
        String component = parsed.optionalString("component");
        String action = parsed.optionalString("action");
        int flags = parsed.optionalInt("flags", 0);
        List<String> categories = categories(parsed);
        if (packageName != null && !OwnerProtocol.isSafeIdentifier(packageName)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "package");
        }
        if (component != null
                && (!OwnerProtocol.isSafeIdentifier(component) || component.indexOf('/') <= 0)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "component");
        }
        if (action != null && !OwnerProtocol.isSafeIdentifier(action)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "action");
        }
        if (packageName == null && component == null && action == null) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "launch target");
        }
        String[] argv = ShellCommands.amStartArgv(displayId, packageName, component, action,
                categories, flags);
        OwnerShell.Result result = OwnerShell.run(argv, OwnerShell.DEFAULT_TIMEOUT_MS,
                OwnerShell.DEFAULT_MAX_OUTPUT_BYTES);
        if (!result.success() || containsError(result.stdout) || containsError(result.stderr)) {
            throw new OwnerException(OwnerProtocol.ERROR_LAUNCH_FAILED, result.summary());
        }
        JSONObject out = new JSONObject();
        try {
            out.put("launched", true);
            out.put("displayId", displayId);
            out.put("exitCode", result.exitCode);
            out.put("output", clip(result.stdout));
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "launch");
        }
        return out;
    }

    public JSONObject input(JSONObject request) throws OwnerException {
        requireLive();
        OwnerProtocol.Request parsed = wrap(request);
        int displayId = optionalDisplay(parsed);
        String kind = parsed.requireString("kind");
        String[] argv;
        if ("tap".equals(kind)) {
            int x = coordinate(parsed, "x");
            int y = coordinate(parsed, "y");
            argv = ShellCommands.inputTapArgv(displayId, x, y);
        } else if ("swipe".equals(kind)) {
            int x1 = coordinate(parsed, "x1");
            int y1 = coordinate(parsed, "y1");
            int x2 = coordinate(parsed, "x2");
            int y2 = coordinate(parsed, "y2");
            int duration = parsed.optionalInt("durationMs", 300);
            argv = ShellCommands.inputSwipeArgv(displayId, x1, y1, x2, y2, duration);
        } else if ("key".equals(kind)) {
            int keyCode = parsed.requireInt("keyCode");
            if (keyCode < 0 || keyCode > 1000) {
                throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "keyCode");
            }
            argv = ShellCommands.inputKeyArgv(displayId, keyCode);
        } else if ("text".equals(kind)) {
            String text = parsed.requireString("text");
            argv = ShellCommands.inputTextArgv(displayId, text);
        } else {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "kind");
        }
        OwnerShell.Result result = OwnerShell.run(argv, OwnerShell.DEFAULT_TIMEOUT_MS,
                OwnerShell.DEFAULT_MAX_OUTPUT_BYTES);
        if (!result.success()) {
            throw new OwnerException(OwnerProtocol.ERROR_INPUT_FAILED, result.summary());
        }
        JSONObject out = new JSONObject();
        try {
            out.put("executed", true);
            out.put("kind", kind);
            out.put("displayId", displayId);
            out.put("exitCode", result.exitCode);
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "input");
        }
        return out;
    }

    public JSONObject snapshot(JSONObject request) throws OwnerException {
        requireLive();
        OwnerProtocol.Request parsed = wrap(request);
        boolean include = parsed.optionalBoolean("include", true);
        int maxBytes = parsed.optionalInt("maxBytes", MAX_SNAPSHOT_BYTES);
        if (maxBytes <= 0) {
            maxBytes = MAX_SNAPSHOT_BYTES;
        }
        maxBytes = Math.min(maxBytes, MAX_SNAPSHOT_BYTES);
        if (!frames.hasFrame()) {
            throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("displayId", created.displayId);
            out.put("frameCount", frames.frameCount());
            out.put("timestampNs", frames.latestTimestampNs());
            out.put("format", "png");
            if (include) {
                OwnerFrameStore.Snapshot snapshot = frames.encodePng(maxBytes);
                out.put("width", snapshot.width);
                out.put("height", snapshot.height);
                out.put("bytes", snapshot.png.length);
                out.put("data", android.util.Base64.encodeToString(snapshot.png,
                        android.util.Base64.NO_WRAP));
            } else {
                out.put("width", frames.frameWidth());
                out.put("height", frames.frameHeight());
            }
        } catch (OwnerException ex) {
            throw ex;
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "snapshot");
        }
        return out;
    }

    /** Not implemented. Reported as a failure so callers never treat it as a completed handoff. */
    public JSONObject handoff(JSONObject request) throws OwnerException {
        requireLive();
        throw new OwnerException(OwnerProtocol.ERROR_NOT_IMPLEMENTED_HANDOFF,
                "handoff is not implemented; move tasks with platform tooling before release");
    }

    public JSONObject release(JSONObject request) throws OwnerException {
        requireLive();
        SourceProbe source = probeSource();
        if (!source.known()) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, source.errorCode);
        }
        if (source.taskCount != 0) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_NOT_EMPTY,
                    "tasks on source=" + source.taskCount);
        }
        frames.clear();
        try {
            created.reader.close();
        } catch (Throwable ignored) {
            // closing an already closed reader is not a release failure
        }
        VirtualDisplayFactory.releaseQuietly(created.display);
        released = true;
        JSONObject out = new JSONObject();
        try {
            out.put("released", true);
            out.put("displayId", created.displayId);
            out.put("uniqueId", created.uniqueId);
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "release");
        }
        return out;
    }

    private void requireLive() throws OwnerException {
        if (released) {
            throw new OwnerException(OwnerProtocol.ERROR_ALREADY_RELEASED, "released");
        }
        if (handler.getLooper() != Looper.myLooper()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "owner thread");
        }
    }

    private int optionalDisplay(OwnerProtocol.Request parsed) throws OwnerException {
        int displayId = parsed.optionalInt("displayId", created.displayId);
        if (displayId != created.displayId) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_REBOUND,
                    "display id is bound to this owner");
        }
        return displayId;
    }

    private static int coordinate(OwnerProtocol.Request parsed, String key) throws OwnerException {
        int value = parsed.requireInt(key);
        if (value < 0 || value > MAX_COORDINATE) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "coordinate " + key);
        }
        return value;
    }

    private static List<String> categories(OwnerProtocol.Request parsed) throws OwnerException {
        List<String> out = new java.util.ArrayList<String>();
        Object raw = parsed.payload.opt("categories");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof JSONArray)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "categories");
        }
        JSONArray array = (JSONArray) raw;
        if (array.length() > 8) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "categories");
        }
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String) || !OwnerProtocol.isSafeIdentifier((String) value)) {
                throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "category");
            }
            out.add((String) value);
        }
        return out;
    }

    private OwnerProtocol.Request wrap(JSONObject request) throws OwnerException {
        if (request == null) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "request");
        }
        try {
            return OwnerProtocol.parseRequest(request.toString());
        } catch (OwnerProtocolException ex) {
            throw new OwnerException(ex.code, ex.getMessage());
        }
    }

    private SourceProbe probeSource() {
        try {
            List<OwnerTaskInventory.Seen> onDisplay =
                    OwnerTaskInventory.readOnDisplay(created.displayId);
            registry.retain(onDisplay);
            return SourceProbe.known(onDisplay.size());
        } catch (OwnerException ex) {
            return SourceProbe.unknown(ex.code);
        }
    }

    private static boolean containsError(String text) {
        if (text == null) {
            return false;
        }
        return text.indexOf("Error:") >= 0 || text.indexOf("Error ") >= 0
                || text.indexOf("Exception") >= 0 || text.indexOf("Permission Denial") >= 0;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() > MAX_OUTPUT_CHARS) {
            return flat.substring(0, MAX_OUTPUT_CHARS);
        }
        return flat;
    }

    private static JSONArray stringArray(String[] values) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            array.put(values[i]);
        }
        return array;
    }

    private static JSONArray intArray(List<Integer> values) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            array.put(values.get(i).intValue());
        }
        return array;
    }

    private static String newUniqueId() {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder("vd-owner-");
        sb.append(Process.myPid()).append('-');
        for (int i = 0; i < random.length; i++) {
            sb.append(Character.forDigit((random[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(random[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static final class SourceProbe {
        final String state;
        final int taskCount;
        final String errorCode;

        private SourceProbe(String state, int taskCount, String errorCode) {
            this.state = state;
            this.taskCount = taskCount;
            this.errorCode = errorCode;
        }

        static SourceProbe known(int taskCount) {
            return new SourceProbe(taskCount == 0 ? "empty" : "occupied", taskCount, null);
        }

        static SourceProbe unknown(String errorCode) {
            return new SourceProbe("unknown", -1, errorCode);
        }

        boolean known() {
            return errorCode == null;
        }
    }
}
