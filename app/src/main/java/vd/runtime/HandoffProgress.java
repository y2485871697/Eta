package vd.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Historical observations, NOT authorization to retry, remove a task, or release a display. */
final class HandoffProgress {
    static final String FIELD = "handoffProgress";
    static final int MAX_TASKS = 256;
    private final Set<Integer> attempted = new LinkedHashSet<Integer>();
    private final Set<Integer> relocated = new LinkedHashSet<Integer>();
    private final Set<Integer> completed = new LinkedHashSet<Integer>();

    void attempted(int id) { add(attempted, id); }
    void relocated(int id) {
        if (!attempted.contains(id)) throw new IllegalStateException("relocation without attempt");
        add(relocated, id);
    }
    void completed(int id) {
        if (!relocated.contains(id)) throw new IllegalStateException("completion without relocation");
        add(completed, id);
    }
    Snapshot snapshot() { return new Snapshot(attempted, relocated, completed); }

    private static void add(Set<Integer> ids, int id) {
        if (id <= 0 || (!ids.contains(id) && ids.size() >= MAX_TASKS)) {
            throw new IllegalStateException("invalid progress id");
        }
        ids.add(id);
    }

    static final class Snapshot {
        final List<Integer> mutationAttemptedTaskIds;
        final List<Integer> relocatedTaskIds;
        final List<Integer> completedTaskIds;

        Snapshot(Iterable<?> attempted, Iterable<?> relocated, Iterable<?> completed) {
            mutationAttemptedTaskIds = safeIds(attempted);
            relocatedTaskIds = safeIds(relocated);
            completedTaskIds = safeIds(completed);
        }
        static Snapshot empty() { return new Snapshot(null, null, null); }
        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("mutationAttemptedTaskIds", new JSONArray(mutationAttemptedTaskIds))
                    .put("relocatedTaskIds", new JSONArray(relocatedTaskIds))
                    .put("completedTaskIds", new JSONArray(completedTaskIds));
        }
        private static List<Integer> safeIds(Iterable<?> values) {
            Set<Integer> safe = new LinkedHashSet<Integer>();
            if (values != null) {
                // Bound the inspected input as well as the emitted array. Never coerce strings.
                int inspected = 0;
                for (Object value : values) {
                    if (++inspected > MAX_TASKS) break;
                    if (value instanceof Integer && ((Integer) value) > 0) safe.add((Integer) value);
                }
            }
            return Collections.unmodifiableList(new ArrayList<Integer>(safe));
        }
    }
}
