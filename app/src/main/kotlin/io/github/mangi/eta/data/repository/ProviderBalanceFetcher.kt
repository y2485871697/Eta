package io.github.mangi.eta.data.repository

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

internal object ProviderBalanceFetcher {
    private val json = Json { ignoreUnknownKeys = true }
    private val binaryExpr = Regex("""^(.+?)\s+([+\-*/])\s+(.+)$""")

    suspend fun fetch(provider: ProviderSetting): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val option = provider.balanceOption
            require(option.enabled) { "Balance query not enabled" }
            require(option.apiPath.isNotBlank()) { "Balance API path not set" }
            require(option.resultPath.isNotBlank()) { "Result JSON path not set" }
            val url = resolveBalanceUrl(provider.baseUrl, option.apiPath)
            val request = Request.Builder()
                .url(url)
                .headers(
                    okhttp3.Headers.Builder()
                        .add("Accept", "application/json")
                        .apply {
                            if (provider.apiKey.isNotBlank()) {
                                add("Authorization", "Bearer ${provider.apiKey}")
                            }
                            CustomHeaderFilter.mergeInto(this, provider.customHeaders)
                        }
                        .build()
                )
                .get()
                .build()
            val body = AgentHttpClient.client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    error("Query failed HTTP ${response.code}: ${text.take(200)}")
                }
                text
            }
            extractValue(body, option.resultPath)
        }
    }

    internal fun resolveBalanceUrl(baseUrl: String, apiPath: String): String {
        val normalizedBase = baseUrl.trim().removeSuffix("/")
        val normalizedPath = apiPath.trim().removePrefix("/")
        return "$normalizedBase/$normalizedPath"
    }

    internal fun extractValue(body: String, resultPath: String): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: error("Invalid JSON response")
        return evaluateExpression(root, resultPath.trim())
    }

    internal fun evaluateExpression(root: JsonElement, expression: String): String {
        val binary = binaryExpr.matchEntire(expression)
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
