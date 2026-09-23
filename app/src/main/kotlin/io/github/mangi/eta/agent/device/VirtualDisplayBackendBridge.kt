package io.github.mangi.eta.agent.device

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONObject
import vd.android.ProbeInventoryProtocol

/**
 * 在 root 的 app_process 里读取虚拟屏 inventory。
 * 应用 UID 不调用 vd.android 的查询实现。
 * ok / query_supported 只表示这次只读查询成功，不表示会话已认证或允许变更。
 */
internal object VirtualDisplayBackendBridge {
    fun inspect(context: Context): JSONObject {
        val resolved = resolveClasspath(context)
        val classpath = resolved.path
        if (classpath == null) {
            return ProbeInventoryProtocol.failure(
                resolved.error ?: ProbeInventoryProtocol.ERROR_CLASSPATH_UNAVAILABLE,
            )
        }
        // 命令由探针协议固定拼出；这里不捕获执行异常，避免把失败收成成功。
        val command = ProbeInventoryProtocol.inventoryShellCommand(classpath)
        val result = BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
            executor.execute(
                command = command,
                timeoutMillis = EXECUTOR_TIMEOUT_MS,
                maxOutputBytes = MAX_OUTPUT_BYTES,
            )
        }
        // Result.ok 在输出被截断时仍可能为 true，不能拿来判断探针成功。
        if (result.errorCode.isNotEmpty()) {
            return ProbeInventoryProtocol.failure(result.errorCode)
        }
        return ProbeInventoryProtocol.parse(
            result.stdout,
            result.exitCode,
            result.timedOut,
            result.truncated,
        )
    }

    private fun resolveClasspath(context: Context): ClasspathResolution {
        val info = context.applicationInfo
            ?: return ClasspathResolution(error = ProbeInventoryProtocol.ERROR_CLASSPATH_UNAVAILABLE)
        val parts = ArrayList<String>()
        val base = info.sourceDir
        if (base.isNullOrEmpty()) {
            return ClasspathResolution(error = ProbeInventoryProtocol.ERROR_CLASSPATH_UNAVAILABLE)
        }
        if (!isSafeClasspathEntry(base)) {
            return ClasspathResolution(error = ProbeInventoryProtocol.ERROR_CLASSPATH_INVALID)
        }
        parts.add(base)
        // 普通 APK 只有 sourceDir。存在分包时再带上 splitSourceDirs，避免漏掉 split dex。
        val splits = info.splitSourceDirs
        if (splits != null) {
            for (split in splits) {
                val entry = split?.takeIf { it.isNotEmpty() }
                    ?: return ClasspathResolution(error = ProbeInventoryProtocol.ERROR_CLASSPATH_INVALID)
                if (!isSafeClasspathEntry(entry)) {
                    return ClasspathResolution(error = ProbeInventoryProtocol.ERROR_CLASSPATH_INVALID)
                }
                parts.add(entry)
            }
        }
        return ClasspathResolution(path = parts.joinToString(separator = ":"))
    }

    /** 冒号是 CLASSPATH 分隔符；控制字符会打断 shell 引用。 */
    private fun isSafeClasspathEntry(path: String): Boolean {
        if (path.indexOf(':') >= 0 || path.indexOf('\u0000') >= 0) return false
        for (element in path) {
            if (element.code <= 0x1F || element.code == 0x7F) return false
        }
        return true
    }

    private class ClasspathResolution(val path: String? = null, val error: String? = null)

    // timeout -k 1s 8s 只向自己的探针发 SIGTERM，1s 后再 SIGKILL。
    // 执行器上限留出启动和回收余量；若 timeout 本身挂起，仍由执行器终止 su。
    private const val EXECUTOR_TIMEOUT_MS = 15_000L
    private const val MAX_OUTPUT_BYTES = 32 * 1024
}
