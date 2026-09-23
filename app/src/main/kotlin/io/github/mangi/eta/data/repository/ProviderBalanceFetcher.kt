package io.github.mangi.eta.data.repository

import java.io.IOException
import java.io.InterruptedIOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 余额查询失败。消息只包含稳定、安全的信息（HTTP 状态码、超时、网络错误等），
 * 绝不携带服务端响应正文，可直接展示给用户。
 */
internal class BalanceQueryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal object ProviderBalanceFetcher {
    /**
     * 单次余额请求的总超时（连接 + 写入 + 读取 + 重定向）。
     * 余额查询复用了为流式模型准备的 600s 读超时客户端，慢/挂死的网关会拖住整轮刷新，
     * 因此这里用独立的短总超时兜底。
     */
    const val TOTAL_TIMEOUT_MS = 15_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val binaryExpr = Regex("""^(.+?)\s+([+\-*/])\s+(.+)$""")

    private val balanceClient: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .callTimeout(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun fetch(
        provider: ProviderSetting,
        option: BalanceOption = provider.balanceOption,
    ): Result<String> = fetch(provider, option, balanceClient)

    /**
     * 可注入 OkHttp 客户端的重载，供测试构造短超时/取消场景。
     * 协程取消会真正 cancel 掉底层 OkHttp call，且不会把 [CancellationException] 吞成失败结果。
     */
    internal suspend fun fetch(
        provider: ProviderSetting,
        option: BalanceOption,
        client: OkHttpClient,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(query(provider, option, client))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun query(
        provider: ProviderSetting,
        option: BalanceOption,
        client: OkHttpClient,
    ): String {
        val resolved = option.resolved()
        require(resolved.enabled) { "Balance query not enabled" }
        require(resolved.apiPath.isNotBlank()) { "Balance API path not set" }
        require(resolved.resultPath.isNotBlank()) { "Result JSON path not set" }
        val url = resolveBalanceUrl(provider.baseUrl, resolved.apiPath, resolved.preset)
        val token = resolved.accessToken.ifBlank { provider.apiKey }
        val request = Request.Builder()
            .url(url)
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .apply {
                        if (token.isNotBlank()) {
                            add("Authorization", "Bearer $token")
                        }
                        CustomHeaderFilter.mergeInto(this, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        val body = try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    // 只保留状态码，绝不回传响应正文。
                    throw BalanceQueryException("Balance request failed (HTTP ${response.code})")
                }
                response.body.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (queryFailure: BalanceQueryException) {
            throw queryFailure
        } catch (timeout: InterruptedIOException) {
            throw BalanceQueryException("Balance request timed out", timeout)
        } catch (network: IOException) {
            throw BalanceQueryException("Balance request failed", network)
        }
        return extractValue(body, resolved.resultPath)
    }

    /** 以可取消的方式执行 OkHttp call：协程取消时同步 cancel 底层请求。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    internal fun resolveBalanceUrl(
        baseUrl: String,
        apiPath: String,
        preset: String = BalanceOption.PRESET_CUSTOM,
    ): String {
        val path = apiPath.trim()
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            return path
        }
        val normalizedPath = path.removePrefix("/")
        val normalizedBase = if (
            preset == BalanceOption.PRESET_NEW_API || isOriginRelativeUserApi(normalizedPath)
        ) {
            originFromOpenAiBaseUrl(baseUrl)
        } else {
            baseUrl.trim().removeSuffix("/")
        }
        return "$normalizedBase/$normalizedPath"
    }

    internal fun originFromOpenAiBaseUrl(baseUrl: String): String {
        val url = baseUrl.trim().removeSuffix("/")
        val suffixes = listOf("/compatible-mode/v1", "/openai/v1", "/v1beta", "/v1")
        for (suffix in suffixes) {
            if (url.endsWith(suffix, ignoreCase = true)) {
                return url.dropLast(suffix.length)
            }
        }
        return url
    }

    private fun isOriginRelativeUserApi(path: String): Boolean {
        val normalized = path.lowercase()
        return normalized.startsWith("api/user") || normalized.startsWith("api/token")
    }

    internal fun extractValue(body: String, resultPath: String): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: error("Invalid JSON response")
        return evaluateExpression(root, resultPath.trim())
    }

    internal fun evaluateExpression(root: JsonElement, expression: String): String {
        val trimmed = expression.trim()
        trimmed.toDoubleOrNull()?.let { return formatNumber(it) }
        val binary = binaryExpr.matchEntire(trimmed)
        if (binary != null) {
            val left = evaluateExpression(root, binary.groupValues[1]).toDoubleOrNull()
                ?: error("Unable to parse balance from JSON path ${binary.groupValues[1]}")
            val right = evaluateExpression(root, binary.groupValues[3]).toDoubleOrNull()
                ?: error("Unable to parse balance from JSON path ${binary.groupValues[3]}")
            val value = when (binary.groupValues[2]) {
                "+" -> left + right
                "-" -> left - right
                "*" -> left * right
                "/" -> if (right == 0.0) error("Division by zero") else left / right
                else -> error("Unsupported operator ${binary.groupValues[2]}")
            }
            return formatNumber(value)
        }
        return primitiveContent(resolvePath(root, expression))
    }

    internal fun resolvePath(root: JsonElement, path: String): JsonElement {
        var current = root
        for (segment in tokenizeJsonPath(path)) {
            current = when (segment) {
                is JsonPathSegment.Field -> {
                    val obj = current as? JsonObject
                        ?: error("JSON path not found: ${segment.name}")
                    obj[segment.name] ?: error("JSON path not found: ${segment.name}")
                }
                is JsonPathSegment.Index -> {
                    val array = current as? JsonArray
                        ?: error("JSON path not found: ${segment.index}")
                    array.getOrNull(segment.index)
                        ?: error("Array index out of bounds: ${segment.index}")
                }
            }
        }
        return current
    }

    internal fun tokenizeJsonPath(path: String): List<JsonPathSegment> {
        val segments = mutableListOf<JsonPathSegment>()
        val source = path.trim()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index] == '.') index++
            if (index >= source.length) break
            if (source[index] == '[') {
                val close = source.indexOf(']', startIndex = index)
                if (close <= index + 1) error("Invalid JSON path index")
                val raw = source.substring(index + 1, close).trim()
                val arrayIndex = raw.toIntOrNull() ?: error("Invalid JSON path index: $raw")
                segments += JsonPathSegment.Index(arrayIndex)
                index = close + 1
                continue
            }
            val start = index
            while (index < source.length && source[index] != '.' && source[index] != '[') index++
            val name = source.substring(start, index).trim()
            if (name.isEmpty()) continue
            segments += name.toIntOrNull()?.let(JsonPathSegment::Index) ?: JsonPathSegment.Field(name)
        }
        if (segments.isEmpty()) error("JSON path not found: $path")
        return segments
    }

    private fun primitiveContent(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> value.contentOrNull ?: value.content
        else -> error("Unable to parse balance from JSON path")
    }

    private fun formatNumber(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }
}

internal sealed interface JsonPathSegment {
    data class Field(val name: String) : JsonPathSegment
    data class Index(val index: Int) : JsonPathSegment
}

internal fun formatBalanceDisplay(raw: String): String {
    val trimmed = raw.trim()
    val number = trimmed.toDoubleOrNull()
    return if (number != null) {
        DecimalFormat(
            "#,##0.00",
            DecimalFormatSymbols.getInstance(Locale.getDefault()),
        ).format(number)
    } else {
        trimmed
    }
}
