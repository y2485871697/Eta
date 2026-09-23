package vd.android;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vd.close.TaskHandle;

/** Same-process read-only probe. Not a close authorization. */
public final class ReadOnlyProbe {
    private ReadOnlyProbe() {}

    public static void main(String[] args) {
        System.out.println("PROBE_ONLY_NOT_CLOSE_AUTHORIZATION");
        int code;
        try {
            code = run(args);
        } catch (RuntimeException ex) {
            System.out.println("ERROR " + safe(ex));
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    private static int run(String[] args) {
        if (args != null && args.length == 1 && "--inventory".equals(args[0])) {
            return inventory();
        }
        if (args != null && args.length == 2 && "--source".equals(args[0])) {
            return source(args[1]);
        }
        System.out.println("ERROR USAGE");
        return 2;
    }

    private static int inventory() {
        AndroidReadOnlyBackend backend = AndroidReadOnlyBackend.openInventory();
        AndroidReadOnlyBackend.Inventory first = backend.inventory();
        AndroidReadOnlyBackend.Inventory second = backend.inventory();
        if (first.focused != second.focused) {
            System.out.println("ERROR FOCUS_UNSTABLE");
            return 3;
        }
        Map<String, TaskHandle> byId = new HashMap<String, TaskHandle>();
        for (int i = 0; i < first.handles.size(); i++) {
            byId.put(first.handles.get(i).id, first.handles.get(i));
        }
        for (int i = 0; i < second.handles.size(); i++) {
            TaskHandle handle = second.handles.get(i);
            TaskHandle previous = byId.get(handle.id);
            if (previous != null && previous != handle) {
                System.out.println("ERROR HANDLE_UNSTABLE");
                return 3;
            }
        }
        System.out.println("focusedTaskId=" + first.focusedTaskId);
        System.out.println("rootCount=" + first.rootCount);
        System.out.println("rootCount2=" + second.rootCount);
        System.out.println("sameIdHandleStable=true");
        return 0;
    }

    private static int source(String text) {
        int displayId;
        try {
            displayId = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            System.out.println("ERROR BAD_DISPLAY");
            return 2;
        }
        if (displayId < 0) {
            System.out.println("ERROR BAD_DISPLAY");
            return 2;
        }
        if (displayId == 0) {
            System.out.println("ERROR SOURCE_IS_MAIN");
            return 2;
        }
        List<TaskHandle> tasks = AndroidReadOnlyBackend.openSource(displayId).tasksOnSource();
        System.out.println("sourceDisplay=" + displayId);
        System.out.println("sourcePresent=true");
        System.out.println("sourceTaskCount=" + tasks.size());
        return 0;
    }

    private static String safe(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        if (message.length() > 80) {
            message = message.substring(0, 80);
        }
        return error.getClass().getSimpleName() + " " + message;
    }
}
