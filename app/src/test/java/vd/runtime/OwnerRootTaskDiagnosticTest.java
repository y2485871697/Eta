package vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 纯 Java 覆盖只读诊断的格式、上限与 fail-closed 语义。不启动 owner，也不读取设备。
 */
public class OwnerRootTaskDiagnosticTest {
    private static OwnerRootTaskDiagnostic.Root root(int id, String base, String top,
            int numActivities, boolean componentsKnown, boolean childrenKnown, int[] childIds,
            String[] childNames, boolean childNamesKnown) {
        return new OwnerRootTaskDiagnostic.Root(id, 0, 0, 1, numActivities, base, null, top, null,
                null, componentsKnown, childIds, childNames, childrenKnown, childNamesKnown);
    }

    private static JSONObject firstTask(JSONObject json) throws Exception {
        return json.getJSONArray("tasks").getJSONObject(0);
    }

    @Test
    public void emitsIdsSanitizedComponentsAndChildren() throws Exception {
        JSONObject json = OwnerRootTaskDiagnostic.toJson(Collections.singletonList(
                root(1, "com.example.home", "com.example.home", 1, true, true,
                        new int[]{2}, new String[]{"com.example.launcher"}, true)));
        assertTrue(json.getBoolean("known"));
        assertEquals(1, json.getInt("rootCount"));
        assertEquals(1, json.getInt("emitted"));
        assertFalse(json.getBoolean("truncated"));
        JSONObject task = firstTask(json);
        assertEquals(1, task.getInt("id"));
        assertEquals(0, task.getInt("displayId"));
        assertEquals(0, task.getInt("userId"));
        assertEquals(1, task.getInt("activityType"));
        assertEquals(1, task.getInt("numActivities"));
        assertTrue(task.getBoolean("componentsKnown"));
        assertEquals("com.example.home", task.getString("base"));
        assertEquals("com.example.home", task.getString("topActivity"));
        assertTrue(task.getBoolean("childrenKnown"));
        assertEquals(2, task.getJSONArray("childTaskIds").getInt(0));
        assertEquals("com.example.launcher", task.getJSONArray("childTaskNames").getString(0));
    }

    @Test
    public void unknownInventoryFailsClosed() throws Exception {
        JSONObject json = OwnerRootTaskDiagnostic.unknown(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN);
        assertFalse(json.getBoolean("known"));
        assertEquals(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, json.getString("error"));
        assertFalse(json.has("tasks"));
        assertFalse(json.has("rootCount"));
        // A null or blank code must not silently become an empty inventory.
        assertEquals(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                OwnerRootTaskDiagnostic.unknown(null).getString("error"));
        assertEquals(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                OwnerRootTaskDiagnostic.unknown("").getString("error"));
        assertEquals(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                OwnerRootTaskDiagnostic.toJson(null).getString("error"));
    }

    @Test
    public void capsRootEntriesAndFlagsTruncation() throws Exception {
        int total = OwnerRootTaskDiagnostic.MAX_ROOTS + 3;
        List<OwnerRootTaskDiagnostic.Root> roots = new ArrayList<OwnerRootTaskDiagnostic.Root>();
        for (int i = 0; i < total; i++) {
            roots.add(root(i + 1, "p", null, 1, true, true, new int[0], new String[0], true));
        }
        JSONObject json = OwnerRootTaskDiagnostic.toJson(roots);
        assertTrue(json.getBoolean("known"));
        assertTrue(json.getBoolean("truncated"));
        assertEquals(total, json.getInt("rootCount"));
        assertEquals(OwnerRootTaskDiagnostic.MAX_ROOTS, json.getInt("emitted"));
        assertEquals(OwnerRootTaskDiagnostic.MAX_ROOTS, json.getJSONArray("tasks").length());
    }

    @Test
    public void capsChildIdsAndNames() throws Exception {
        int[] ids = new int[OwnerRootTaskDiagnostic.MAX_CHILD_IDS + 2];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i + 1;
        }
        String[] names = new String[OwnerRootTaskDiagnostic.MAX_CHILD_NAMES + 2];
        for (int i = 0; i < names.length; i++) {
            names[i] = "p" + i;
        }
        JSONObject json = OwnerRootTaskDiagnostic.toJson(Collections.singletonList(
                root(1, "p", null, 1, true, true, ids, names, true)));
        assertTrue(json.getBoolean("truncated"));
        JSONObject task = firstTask(json);
        assertEquals(OwnerRootTaskDiagnostic.MAX_CHILD_IDS,
                task.getJSONArray("childTaskIds").length());
        assertEquals(OwnerRootTaskDiagnostic.MAX_CHILD_NAMES,
                task.getJSONArray("childTaskNames").length());
    }

    @Test
    public void keepsUnresolvedAndUnnamedFieldsExplicit() throws Exception {
        JSONObject json = OwnerRootTaskDiagnostic.toJson(Collections.singletonList(
                root(9, null, null, -1, false, false, new int[0],
                        new String[]{null}, false)));
        JSONObject task = firstTask(json);
        assertFalse(task.getBoolean("componentsKnown"));
        assertFalse(task.has("base"));
        assertFalse(task.has("topActivity"));
        assertFalse(task.getBoolean("childrenKnown"));
        assertFalse(task.getBoolean("childNamesKnown"));
        assertEquals(-1, task.getInt("numActivities"));
        JSONArray names = task.getJSONArray("childTaskNames");
        assertEquals(1, names.length());
        assertTrue(names.isNull(0));
    }

    @Test
    public void sanitizerDropsBlankUnsafeAndOverlongIdentifiers() {
        assertNull(OwnerRootTaskDiagnostic.safeIdentifier(null));
        assertNull(OwnerRootTaskDiagnostic.safeIdentifier(""));
        assertNull(OwnerRootTaskDiagnostic.safeIdentifier("bad value"));
        assertNull(OwnerRootTaskDiagnostic.safeIdentifier("com.example\n.Main"));
        assertNull(OwnerRootTaskDiagnostic.safeIdentifier(
                "a".repeat(OwnerRootTaskDiagnostic.MAX_NAME_CHARS + 1)));
        assertEquals("com.example/.Main", OwnerRootTaskDiagnostic.safeIdentifier("com.example/.Main"));
        assertEquals("com.example.app",
                OwnerRootTaskDiagnostic.safeIdentifier("com.example.app"));
    }
}
