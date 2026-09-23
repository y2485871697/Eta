package vd.android;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 纯 Java 覆盖五行 inventory 协议。不启动 app_process，也不读取设备。 */
public class ProbeInventoryProtocolTest {
    private static final String VALID = ""
            + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
            + "focusedTaskId=7\n"
            + "rootCount=2\n"
            + "rootCount2=2\n"
            + "sameIdHandleStable=true\n";

    @Test
    public void acceptsOneHeaderAndFourUniqueFields() throws Exception {
        JSONObject json = ProbeInventoryProtocol.parse(VALID, 0, false, false);
        assertReadOnly(json, true);
        assertEquals(7, json.getInt("focusedTaskId"));
        assertEquals(2, json.getInt("rootCount"));
        assertEquals(2, json.getInt("rootCount2"));
        assertEquals(Boolean.TRUE, json.get("sameIdHandleStable"));
        assertFalse(json.has("error"));
    }

    @Test
    public void fieldOrderDoesNotMatterForPositiveRootCounts() throws Exception {
        String stdout = ""
                + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
                + "rootCount2=3\n"
                + "sameIdHandleStable=true\n"
                + "rootCount=1\n"
                + "focusedTaskId=15";
        JSONObject json = ProbeInventoryProtocol.parse(stdout, 0, false, false);
        assertReadOnly(json, true);
        assertEquals(15, json.getInt("focusedTaskId"));
        assertEquals(1, json.getInt("rootCount"));
        assertEquals(3, json.getInt("rootCount2"));
        assertEquals(Boolean.TRUE, json.get("sameIdHandleStable"));
        assertFalse(json.has("error"));
        assertProtocol(VALID.replace("rootCount=2", "rootCount=0"));
        assertProtocol(VALID.replace("rootCount2=2", "rootCount2=0"));
    }

    @Test
    public void rejectsDuplicateMissingGarbageAndBadNumbers() throws Exception {
        assertProtocol(""
                + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
                + "focusedTaskId=7\n"
                + "focusedTaskId=7\n"
                + "rootCount=2\n"
                + "rootCount2=2\n");
        assertProtocol(""
                + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
                + "focusedTaskId=7\n"
                + "rootCount=2\n"
                + "rootCount2=2\n");
        assertProtocol(VALID + "extra=1\n");
        assertProtocol("not a probe\n");
        assertProtocol(""
                + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
                + "focusedTaskId=7\n"
                + "NOT_A_FIELD\n"
                + "rootCount2=2\n"
                + "sameIdHandleStable=true\n");
        assertProtocol(""
                + "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION\n"
                + "focusedTaskId=0\n"
                + "rootCount=1\n"
                + "rootCount2=1\n"
                + "sameIdHandleStable=true\n");
        assertProtocol(VALID.replace("focusedTaskId=7", "focusedTaskId=01"));
        assertProtocol(VALID.replace("focusedTaskId=7", "focusedTaskId=+7"));
        assertProtocol(VALID.replace("focusedTaskId=7", "focusedTaskId=-1"));
        assertProtocol(VALID.replace("rootCount=2", "rootCount=2147483648"));
        assertProtocol(VALID.replace("rootCount=2", "rootCount=2 "));
        assertProtocol(VALID.replace("\n", "\r\n"));
    }

    @Test
    public void rejectsUnstableHandleAndNonZeroExit() throws Exception {
        assertProtocol(VALID.replace("sameIdHandleStable=true", "sameIdHandleStable=false"));
        assertProtocol(VALID.replace("sameIdHandleStable=true", "sameIdHandleStable=TRUE"));
        JSONObject exited = ProbeInventoryProtocol.parse(VALID, 3, false, false);
        assertReadOnly(exited, false);
        assertEquals(ProbeInventoryProtocol.ERROR_EXIT_NONZERO, exited.getString("error"));
        assertFalse(exited.has("focusedTaskId"));
    }

    @Test
    public void timeoutAndOutputLimitAreNotSuccessfulQueries() throws Exception {
        JSONObject timedOut = ProbeInventoryProtocol.parse(VALID, -2, true, false);
        assertReadOnly(timedOut, false);
        assertEquals(ProbeInventoryProtocol.ERROR_TIMEOUT, timedOut.getString("error"));

        JSONObject timeoutStatus = ProbeInventoryProtocol.parse("", 124, false, false);
        assertReadOnly(timeoutStatus, false);
        assertEquals(ProbeInventoryProtocol.ERROR_TIMEOUT, timeoutStatus.getString("error"));

        JSONObject truncated = ProbeInventoryProtocol.parse(VALID, 0, false, true);
        assertReadOnly(truncated, false);
        assertEquals(ProbeInventoryProtocol.ERROR_OUTPUT_LIMIT, truncated.getString("error"));
        assertFalse(truncated.getBoolean("query_supported"));
    }

    @Test
    public void transportFailureKeepsTheReadOnlyContract() throws Exception {
        JSONObject json = ProbeInventoryProtocol.failure("ROOT_REQUIRED");
        assertReadOnly(json, false);
        assertEquals("ROOT_REQUIRED", json.getString("error"));
        assertEquals(ProbeInventoryProtocol.ERROR_PROTOCOL,
                ProbeInventoryProtocol.failure(" ").getString("error"));
    }

    @Test
    public void shellCommandQuotesClasspathAndStaysOnTheFixedProbe() {
        assertEquals(
                "export CLASSPATH='/data/app/base.apk:/data/app/split.apk'; "
                        + "exec /system/bin/timeout -k 1s 8s /system/bin/app_process /system/bin "
                        + "vd.android.ReadOnlyProbe --inventory",
                ProbeInventoryProtocol.inventoryShellCommand("/data/app/base.apk:/data/app/split.apk"));
        assertEquals(
                "export CLASSPATH='/data/app/o'\\''brien/base.apk; $(id)'; "
                        + "exec /system/bin/timeout -k 1s 8s /system/bin/app_process /system/bin "
                        + "vd.android.ReadOnlyProbe --inventory",
                ProbeInventoryProtocol.inventoryShellCommand("/data/app/o'brien/base.apk; $(id)"));
        assertRejectedClasspath(null);
        assertRejectedClasspath("");
        assertRejectedClasspath("/data/app/base.apk\n/tmp/evil");
        assertRejectedClasspath("/data/app/base.apk\r");
    }

    private static void assertProtocol(String stdout) throws Exception {
        JSONObject json = ProbeInventoryProtocol.parse(stdout, 0, false, false);
        assertReadOnly(json, false);
        assertEquals(ProbeInventoryProtocol.ERROR_PROTOCOL, json.getString("error"));
        assertFalse(json.has("focusedTaskId"));
        assertFalse(json.has("sameIdHandleStable"));
    }

    private static void assertReadOnly(JSONObject json, boolean querySucceeded) throws Exception {
        assertEquals(querySucceeded, json.getBoolean("ok"));
        assertEquals(querySucceeded, json.getBoolean("query_supported"));
        assertEquals("read_only", json.getString("mode"));
        assertEquals(Boolean.FALSE, json.get("mutations_enabled"));
        assertEquals(Boolean.FALSE, json.get("session_authenticated"));
        assertEquals(Boolean.FALSE, json.get("ready"));
    }

    private static void assertRejectedClasspath(String classpath) {
        try {
            ProbeInventoryProtocol.inventoryShellCommand(classpath);
            fail("classpath must not become a command");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }
}
