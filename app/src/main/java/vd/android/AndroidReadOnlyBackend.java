package vd.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import vd.close.ActionResult;
import vd.close.CloseSurface;
import vd.close.TaskHandle;

/**
 * Stage-1 query adapter. Not a session, capture, or authorization backend.
 * A production instance keeps an AndroidBridge reader, and that reader retains
 * the ActivityTaskManager service plus its real IBinder. Handles are stable for
 * one binder in this JVM. A new binder for the same task id drops the old
 * binding and fails that read. This does not remove observation races.
 */
public final class AndroidReadOnlyBackend implements CloseSurface {
    /** Failure throws. Never turn a failed read into an empty task list. */
    public interface Reader {
        Snapshot read();

        boolean displayExists(int displayId);
    }

    public static final class Seen {
        public final int taskId;
        public final int displayId;
        public final Object binder;

        public Seen(int taskId, int displayId, Object binder) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.binder = binder;
        }
    }

    public static final class Snapshot {
        public final List<Seen> roots;
        public final Seen focused;

        public Snapshot(List<Seen> roots, Seen focused) {
            this.roots = roots;
            this.focused = focused;
        }
    }

    public static final class Inventory {
        public final int focusedTaskId;
        public final int rootCount;
        public final TaskHandle focused;
        public final List<TaskHandle> handles;

        Inventory(int focusedTaskId, int rootCount, TaskHandle focused, List<TaskHandle> handles) {
            this.focusedTaskId = focusedTaskId;
            this.rootCount = rootCount;
            this.focused = focused;
            this.handles = handles;
        }
    }

    private static final ActionResult REFUSED = ActionResult.failed("UNSUPPORTED_STAGE1_READ_ONLY");

    private final Reader reader;
    private final Integer sourceDisplay;
    private final TaskRegistry registry = new TaskRegistry();

    public AndroidReadOnlyBackend(Reader reader, Integer sourceDisplay) {
        if (reader == null) throw new NullPointerException("reader");
        if (sourceDisplay != null && sourceDisplay.intValue() == 0) {
            throw new IllegalArgumentException("SOURCE_IS_MAIN");
        }
        if (sourceDisplay != null && sourceDisplay.intValue() < 0) {
            throw new IllegalArgumentException("BAD_DISPLAY");
        }
        this.reader = reader;
        this.sourceDisplay = sourceDisplay;
    }

    public static AndroidReadOnlyBackend openInventory() {
        return new AndroidReadOnlyBackend(AndroidBridge.open(), null);
    }

    public static AndroidReadOnlyBackend openSource(int displayId) {
        if (displayId == 0) throw new IllegalArgumentException("SOURCE_IS_MAIN");
        if (displayId < 0) throw new IllegalArgumentException("BAD_DISPLAY");
        return new AndroidReadOnlyBackend(AndroidBridge.open(), Integer.valueOf(displayId));
    }

    public TaskHandle mainForeground() {
        return observe().focused;
    }

    public List<TaskHandle> tasksOnMain() {
        return onDisplay(0);
    }

    public List<TaskHandle> tasksOnSource() {
        if (sourceDisplay == null) throw new IllegalStateException("NO_SOURCE");
        if (!reader.displayExists(sourceDisplay.intValue())) {
            throw new IllegalStateException("SOURCE_MISSING");
        }
        return onDisplay(sourceDisplay.intValue());
    }

    public Inventory inventory() {
        Observed observed = observe();
        return new Inventory(Integer.parseInt(observed.focused.id), observed.roots.size(),
                observed.focused, Collections.unmodifiableList(new ArrayList<TaskHandle>(observed.handles)));
    }

    public ActionResult migrateToMain(TaskHandle task) { return REFUSED; }
    public ActionResult restore(TaskHandle task) { return REFUSED; }
    public ActionResult release() { return REFUSED; }

    private List<TaskHandle> onDisplay(int displayId) {
        Observed observed = observe();
        ArrayList<TaskHandle> out = new ArrayList<TaskHandle>();
        for (int i = 0; i < observed.roots.size(); i++) {
            if (observed.roots.get(i).displayId == displayId) out.add(observed.handles.get(i));
        }
        return Collections.unmodifiableList(out);
    }

    private Observed observe() {
        Snapshot snap = reader.read();
        if (snap == null || snap.roots == null) throw new IllegalStateException("READ_FAILED");
        ArrayList<Seen> roots = new ArrayList<Seen>(snap.roots);
        Seen focused = snap.focused;
        if (focused == null) throw new IllegalStateException("FOCUSED_MISSING");
        if (focused.binder == null) throw new IllegalStateException("EMPTY_BINDER");
        if (focused.taskId <= 0) throw new IllegalStateException("TASK_ID");
        HashSet<Integer> ids = new HashSet<Integer>();
        Seen match = null;
        for (int i = 0; i < roots.size(); i++) {
            Seen seen = roots.get(i);
            if (seen == null) throw new IllegalStateException("TASK_NULL");
            if (seen.binder == null) throw new IllegalStateException("EMPTY_BINDER");
            if (seen.taskId <= 0) throw new IllegalStateException("TASK_ID");
            if (!ids.add(Integer.valueOf(seen.taskId))) throw new IllegalStateException("DUPLICATE_TASK");
            if (seen.taskId == focused.taskId) match = seen;
        }
        if (match == null) throw new IllegalStateException("FOCUSED_NOT_IN_ROOTS");
        if (focused.displayId != 0 || match.displayId != 0) {
            throw new IllegalStateException("FOCUSED_NOT_MAIN");
        }
        if (!match.binder.equals(focused.binder)) throw new IllegalStateException("FOCUSED_MISMATCH");
        for (int i = 0; i < roots.size(); i++) registry.prepare(roots.get(i).taskId, roots.get(i).binder);
        ArrayList<TaskHandle> handles = new ArrayList<TaskHandle>(roots.size());
        TaskHandle focusedHandle = null;
        for (int i = 0; i < roots.size(); i++) {
            TaskHandle handle = registry.intern(roots.get(i).taskId, roots.get(i).binder);
            handles.add(handle);
            if (roots.get(i) == match) focusedHandle = handle;
        }
        if (focusedHandle == null) throw new IllegalStateException("FOCUSED_NOT_IN_ROOTS");
        return new Observed(roots, handles, focusedHandle);
    }

    private static final class Observed {
        final List<Seen> roots;
        final List<TaskHandle> handles;
        final TaskHandle focused;

        Observed(List<Seen> roots, List<TaskHandle> handles, TaskHandle focused) {
            this.roots = roots;
            this.handles = handles;
            this.focused = focused;
        }
    }

    /** Process-local binder map. Not written to disk and not used as log text. */
    static final class TaskRegistry {
        private final HashMap<Object, TaskHandle> byBinder = new HashMap<Object, TaskHandle>();
        private final HashMap<String, Object> binderById = new HashMap<String, Object>();

        void prepare(int taskId, Object binder) {
            String id = Integer.toString(taskId);
            Object previous = binderById.get(id);
            if (previous != null && !previous.equals(binder)) {
                byBinder.remove(previous);
                binderById.remove(id);
                throw new IllegalStateException("TASK_BINDER_REPLACED");
            }
            TaskHandle bound = byBinder.get(binder);
            if (bound != null && !bound.id.equals(id)) {
                throw new IllegalStateException("BINDER_TASK_MISMATCH");
            }
        }

        TaskHandle intern(int taskId, Object binder) {
            prepare(taskId, binder);
            TaskHandle existing = byBinder.get(binder);
            if (existing != null) return existing;
            TaskHandle created = new TaskHandle(Integer.toString(taskId));
            byBinder.put(binder, created);
            binderById.put(created.id, binder);
            return created;
        }
    }
}
