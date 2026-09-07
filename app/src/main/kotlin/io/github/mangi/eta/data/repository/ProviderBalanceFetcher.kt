package io.github.mangi.eta.data.repository
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
internal object ProviderBalanceFetcher {
    private val json = Json { ignoreUnknownKeys = true }
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
        val value = resolvePath(root, resultPath)
        return when (value) {
            is JsonNull -> "null"
            is JsonElement -> value.jsonPrimitive.contentOrNull
                ?: value.jsonPrimitive.booleanOrNull?.toString()
                ?: value.jsonPrimitive.doubleOrNull?.toString()
                ?: value.toString()
            else -> error("Unable to parse balance from JSON path $resultPath 解析余额")
        }
    }
    private fun resolvePath(root: JsonElement, path: String): JsonElement {
        val keys = path.split(".").map { it.trim() }
        var current: JsonElement = root
        for (key in keys) {
            if (key.isEmpty()) continue
            current = if (key.matches(Regex("\\d+"))) {
                val index = key.toInt()
                current.jsonArray.getOrNull(index)
                    ?: error("Array index out of bounds: $key")
            } else {
                current.jsonObject[key]
                    ?: error("JSON path not found: $key")
            }
        }
        return current
    }
}
