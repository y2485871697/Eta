package vd.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 解析 {@code vd.android.ReadOnlyProbe --inventory} 的五行输出：一行 header，加四个不重复字段。
 * 重复、缺失、乱文本、非法数字、稳定性不是 true、或进程退出码非零都拒绝。
 *
 * <p>JSON 始终是只读查询结果：{@code mode=read_only}，{@code mutations_enabled=false}，
 * {@code session_authenticated=false}，{@code ready=false}。
 * {@code ok} 与 {@code query_supported} 只表示这次查询成功，不表示会话可用。
 */
public final class ProbeInventoryProtocol {
    public static final String HEADER = "PROBE_ONLY_NOT_CLOSE_AUTHORIZATION";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_OUTPUT_LIMIT = "OUTPUT_LIMIT";
    public static final String ERROR_EXIT_NONZERO = "EXIT_NONZERO";
    public static final String ERROR_PROTOCOL = "PROTOCOL";
    public static final String ERROR_CLASSPATH_UNAVAILABLE = "CLASSPATH_UNAVAILABLE";
    public static final String ERROR_CLASSPATH_INVALID = "CLASSPATH_INVALID";

    private static final String FOCUSED = "focusedTaskId";
    private static final String ROOT = "rootCount";
    private static final String ROOT_AGAIN = "rootCount2";
    private static final String STABLE = "sameIdHandleStable";
    /** toybox/GNU timeout 在时限到达时的退出码。探针自身只使用 0、1、2、3。 */
    private static final int TIMEOUT_STATUS = 124;

    private ProbeInventoryProtocol() {}

    /**
     * 固定探针命令。classpath 只作为被引用的环境值，不能改可执行文件或参数。
     * 空值、NUL 和换行直接拒绝，避免把额外 shell 语句拼进去。
     */
    public static String inventoryShellCommand(String classpath) {
        if (classpath == null || classpath.isEmpty()
                || classpath.indexOf('\0') >= 0
                || classpath.indexOf('\n') >= 0
                || classpath.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("classpath");
        }
        return "export CLASSPATH=" + quote(classpath)
                + "; exec /system/bin/timeout -k 1s 8s /system/bin/app_process /system/bin "
                + "vd.android.ReadOnlyProbe --inventory";
    }

    public static JSONObject failure(String errorCode) {
        try {
            return failed(errorCode);
        } catch (JSONException ex) {
            throw new IllegalStateException("probe inventory json", ex);
        }
    }

    public static JSONObject parse(String stdout, int exitCode, boolean timedOut, boolean truncated) {
        try {
            if (timedOut || exitCode == TIMEOUT_STATUS) return failed(ERROR_TIMEOUT);
            if (exitCode != 0) return failed(ERROR_EXIT_NONZERO);
            if (truncated) return failed(ERROR_OUTPUT_LIMIT);
            String[] lines = protocolLines(stdout);
            if (lines == null) return failed(ERROR_PROTOCOL);
            return parseLines(lines);
        } catch (JSONException ex) {
            throw new IllegalStateException("probe inventory json", ex);
        }
    }

    private static JSONObject parseLines(String[] lines) throws JSONException {
        if (!HEADER.equals(lines[0])) return failed(ERROR_PROTOCOL);
        Integer focused = null;
        Integer root = null;
        Integer rootAgain = null;
        boolean stable = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int eq = line.indexOf('=');
            if (eq <= 0 || line.indexOf('=', eq + 1) >= 0) return failed(ERROR_PROTOCOL);
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (FOCUSED.equals(key)) {
                if (focused != null) return failed(ERROR_PROTOCOL);
                focused = parseUnsignedInt(value, true);
                if (focused == null) return failed(ERROR_PROTOCOL);
            } else if (ROOT.equals(key)) {
                if (root != null) return failed(ERROR_PROTOCOL);
                root = parseUnsignedInt(value, true);
                if (root == null) return failed(ERROR_PROTOCOL);
            } else if (ROOT_AGAIN.equals(key)) {
                if (rootAgain != null) return failed(ERROR_PROTOCOL);
                rootAgain = parseUnsignedInt(value, true);
                if (rootAgain == null) return failed(ERROR_PROTOCOL);
            } else if (STABLE.equals(key)) {
                if (stable) return failed(ERROR_PROTOCOL);
                if (!"true".equals(value)) return failed(ERROR_PROTOCOL);
                stable = true;
            } else {
                return failed(ERROR_PROTOCOL);
            }
        }
        if (focused == null || root == null || rootAgain == null || !stable) {
            return failed(ERROR_PROTOCOL);
        }
        JSONObject json = base(true);
        json.put(FOCUSED, focused.intValue());
        json.put(ROOT, root.intValue());
        json.put(ROOT_AGAIN, rootAgain.intValue());
        json.put(STABLE, true);
        return json;
    }

    private static String[] protocolLines(String stdout) {
        if (stdout == null || stdout.isEmpty()) return null;
        for (int i = 0; i < stdout.length(); i++) {
            char c = stdout.charAt(i);
            if (c == '\r' || c == '\0') return null;
        }
        String text = stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout;
        if (text.isEmpty()) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length != 5) return null;
        return lines;
    }

    /** 十进制整数。禁止符号、空白、前导零和超出 int 的值；task id 还必须大于 0。 */
    private static Integer parseUnsignedInt(String value, boolean positive) {
        if (value == null || value.isEmpty() || value.length() > 10) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        if (value.length() > 1 && value.charAt(0) == '0') return null;
        long parsed = Long.parseLong(value);
        if (parsed > Integer.MAX_VALUE) return null;
        if (positive && parsed <= 0L) return null;
        return Integer.valueOf((int) parsed);
    }

    private static JSONObject failed(String errorCode) throws JSONException {
        JSONObject json = base(false);
        if (errorCode == null || errorCode.trim().isEmpty()) errorCode = ERROR_PROTOCOL;
        json.put("error", errorCode);
        return json;
    }

    private static JSONObject base(boolean querySucceeded) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("ok", querySucceeded);
        json.put("query_supported", querySucceeded);
        json.put("mode", "read_only");
        json.put("mutations_enabled", false);
        json.put("session_authenticated", false);
        json.put("ready", false);
        return json;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
