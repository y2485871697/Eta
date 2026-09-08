package io.github.mangi.eta.agent.mcp

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import org.json.JSONObject

/** 将现代 MCP schema 中可直接定位的 x-mcp-header 参数映射到请求头。 */
internal class McpToolHeaders private constructor(
    private val bindings: List<Binding>,
) {
    fun extract(arguments: JSONObject): Map<String, String> = buildMap {
        bindings.forEach { binding ->
            val value = arguments.valueAt(binding.path) ?: return@forEach
            val rendered = when (value) {
                is String -> value.takeIf { binding.type == TYPE_STRING }
                is Boolean -> value.toString().takeIf { binding.type == TYPE_BOOLEAN }
                is Number -> value.renderIntegerOrNull().takeIf { binding.type == TYPE_INTEGER }
                else -> null
            } ?: return@forEach
            put("$HEADER_PREFIX${binding.headerName}", encodeMcpHeaderValue(rendered))
        }
    }

    private data class Binding(
        val path: List<String>,
        val headerName: String,
        val type: String,
    )

    companion object {
        fun fromSchema(schema: JSONObject): McpToolHeaders {
            val bindings = mutableListOf<Binding>()

            fun visit(node: JSONObject, path: List<String>) {
                val properties = node.optJSONObject("properties") ?: return
                properties.keys().forEach { propertyName ->
                    val propertySchema = properties.optJSONObject(propertyName) ?: return@forEach
                    val propertyPath = path + propertyName
                    val headerName = (propertySchema.opt(EXTENSION) as? String)
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { it.matches(HEADER_TOKEN) }
                    val type = propertySchema.opt("type") as? String
                    if (headerName != null && type in SUPPORTED_TYPES) {
                        bindings += Binding(propertyPath, headerName, requireNotNull(type))
                    }
                    visit(propertySchema, propertyPath)
                }
            }

            visit(schema, emptyList())
            return McpToolHeaders(bindings)
        }

        private const val EXTENSION = "x-mcp-header"
        private const val HEADER_PREFIX = "Mcp-Param-"
        private const val TYPE_STRING = "string"
        private const val TYPE_INTEGER = "integer"
        private const val TYPE_BOOLEAN = "boolean"
        private val SUPPORTED_TYPES = setOf(TYPE_STRING, TYPE_INTEGER, TYPE_BOOLEAN)
        private val HEADER_TOKEN = Regex("""[!#${'$'}%&'*+.^_`|~0-9A-Za-z-]+""")
    }
}

internal fun encodeMcpHeaderValue(value: String): String {
    val plainAscii = value.all { character ->
        character == '\t' || character.code in 0x20..0x7e
    } && !value.startsWith(' ') && !value.endsWith(' ') &&
        !value.startsWith('\t') && !value.endsWith('\t') &&
        !(value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX))
    if (plainAscii) return value

    val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    return "$BASE64_PREFIX$encoded$BASE64_SUFFIX"
}

private fun JSONObject.valueAt(path: List<String>): Any? {
    var current: Any? = this
    path.forEach { key ->
        val container = current as? JSONObject ?: return null
        if (!container.has(key) || container.isNull(key)) return null
        current = container.opt(key)
    }
    return current
}

private fun Number.renderIntegerOrNull(): String? {
    val integer = runCatching { BigDecimal(toString()).toBigIntegerExact() }.getOrNull() ?: return null
    if (integer !in MIN_SAFE_INTEGER..MAX_SAFE_INTEGER) return null
    return integer.toString()
}

private const val BASE64_PREFIX = "=?base64?"
private const val BASE64_SUFFIX = "?="
private val MAX_SAFE_INTEGER = BigInteger("9007199254740991")
private val MIN_SAFE_INTEGER = MAX_SAFE_INTEGER.negate()
