package vd.runtime;

import android.os.Binder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android 15 bottom-reparent adapter; no organizer registration, TDA takeover, or ATMS move. */
final class AndroidBackgroundHandoff implements SafeBackgroundHandoff.Backend {
    private final int source;
    private final String unique;
    private final Map<Integer, OwnerHandoff.Task> owned;
    private final Set<Integer> selected;
    private final FocusWitness witness;
    private BackgroundTaskApi api;
    private Object cookie;

    AndroidBackgroundHandoff(int source, String unique, Map<Integer, OwnerHandoff.Task> owned,
            Set<Integer> selected, FocusWitness witness) {
        this.source = source;
        this.unique = unique;
        this.owned = owned;
        this.selected = selected;
        this.witness = witness;
    }

    @Override public void preflight() throws Exception {
        // Even removal-only handoffs resolve the cleanup API before launching the anchor.
        Class.forName("android.app.IActivityTaskManager").getMethod("removeTask", int.class);
        if (selected.isEmpty()) return;
        api = BackgroundTaskApi.resolve();
        cookie = new Binder();
        Map<Integer, Object> inventory = OwnerHandoff.roots();
        for (Object root : inventory.values()) view(root); // Required inventory fields/methods.
        for (int id : selected) {
            Object task = inventory.get(id);
            owned.get(id).check(task, source);
            if (mode(task, "getWindowingMode") != 1 || mode(task, "getActivityType") != 1) {
                throw new IllegalStateException("unsupported handoff windowing mode");
            }
            Object token = OwnerHandoff.field(task, "token");
            // Only construct a local transaction; no apply, no IPC mutation, no guessed token.
            api.movement(token, token);
        }
        OwnerHandoff.verifyDisplay(source, unique);
        checkFocus();
    }

    @Override public Object createStaging() throws Exception {
        Map<Integer, Object> before = new LinkedHashMap<Integer, Object>();
        for (Map.Entry<Integer, Object> entry : OwnerHandoff.roots().entrySet()) {
            Object task = entry.getValue();
            Object cookies = OwnerHandoff.field(task, "launchCookies");
            if (!(cookies instanceof List) || ((List<?>) cookies).contains(cookie)) {
                throw new IllegalStateException("staging cookie inventory");
            }
            before.put(entry.getKey(), OwnerHandoff.binder(task));
        }
        checkFocus();
        api.create(cookie);
        // A single post-create proof, not polling away a focus mismatch or a missing root.
        checkFocus();
        List<StagingRootGuard.View> after = new ArrayList<StagingRootGuard.View>();
        for (Object root : OwnerHandoff.roots().values()) after.add(view(root));
        StagingRootGuard staging = StagingRootGuard.identify(before, after, cookie);
        staging.check(access);
        return staging;
    }

    @Override public void checkStaging(Object staging) throws Exception {
        ((StagingRootGuard) staging).check(access);
    }
    @Override public void deleteStaging(Object staging) throws Exception {
        ((StagingRootGuard) staging).delete(access);
    }
    @Override public void checkSource(int id) throws Exception {
        sourceTask(id);
    }
    private Object sourceTask(int id) throws Exception {
        OwnerHandoff.verifyDisplay(source, unique);
        if (!selected.contains(id)) throw new IllegalStateException("unselected task");
        Object task = OwnerHandoff.roots().get(id);
        owned.get(id).check(task, source);
        return task;
    }
    @Override public void checkDestination(int id) throws Exception {
        owned.get(id).check(OwnerHandoff.roots().get(id), 0);
    }
    @Override public void checkFocus() throws Exception { OwnerHandoff.focus(witness); }
    @Override public void hide(int id) throws Exception {
        api.hide(OwnerHandoff.field(sourceTask(id), "token"));
    }
    @Override public void relocateAndRestore(int id, Object staging) throws Exception {
        Object task = sourceTask(id);
        StagingRootGuard guard = (StagingRootGuard) staging;
        guard.check(access);
        checkFocus();
        api.relocateAndRestore(OwnerHandoff.field(task, "token"), guard.token);
    }

    private final StagingRootGuard.Access access = new StagingRootGuard.Access() {
        @Override public StagingRootGuard.View read(int id) throws Exception {
            Object task = OwnerHandoff.roots().get(id);
            return task == null ? null : view(task);
        }
        @Override public List<?> children(Object token) throws Exception { return api.children(token); }
        @Override public void delete(Object token) throws Exception { api.delete(token); }
        @Override public boolean absent(int id, Object binder) throws Exception {
            Map<Integer, Object> current = OwnerHandoff.roots();
            if (current.containsKey(id)) return false;
            for (Object root : current.values()) {
                if (binder.equals(OwnerHandoff.binder(root))) return false;
            }
            return true;
        }
    };

    private static int mode(Object task, String method) throws Exception {
        return (Integer) task.getClass().getMethod(method).invoke(task);
    }
    private static StagingRootGuard.View view(Object task) throws Exception {
        Object cookies = OwnerHandoff.field(task, "launchCookies");
        if (!(cookies instanceof List)) throw new IllegalStateException("staging cookies unknown");
        return new StagingRootGuard.View(OwnerHandoff.number(task, "taskId"),
                OwnerHandoff.number(task, "displayId"), OwnerHandoff.number(task, "userId"),
                OwnerHandoff.number(task, "parentTaskId"), OwnerHandoff.number(task, "numActivities"),
                mode(task, "getActivityType"), mode(task, "getWindowingMode"),
                OwnerHandoff.field(task, "token"), OwnerHandoff.binder(task), (List<?>) cookies,
                (int[]) OwnerHandoff.field(task, "childTaskIds"), OwnerHandoff.identityFree(task));
    }
}
