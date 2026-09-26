package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界分类失败；模型请求重试预算由 AgentModelRetry 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val diagnostic: String = "",
    // Only the Responses provider's per-request delivery guard may authorize correction.
    val envelopeCorrectionAllowed: Boolean = false,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        private fun toolEnvelopeRejected() = AgentModelFailure(
            code = ResponsesToolEnvelopeRecovery.CODE,
            retryable = false,
            envelopeCorrectionAllowed = true,
            message = "模型生成的工具封装未通过代理 JSON 校验（missing-separator）。",
            // Never retain the rejected code, request body, headers, or provider text.
            diagnostic = "responses_tool_envelope_rejected; json_failure=missing-separator",
        )

        fun http(
            status: Int,
            body: String,
            headers: okhttp3.Headers? = null,
            secrets: List<String> = emptyList(),
        ): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (ResponsesToolEnvelopeRecovery.matches(error, status)) {
                val guard = ResponsesToolEnvelopeRecovery.DeliveryGuard()
                guard.inspectEnvelope(JSONObject(body))
                return guard.protect(toolEnvelopeRejected()) as AgentModelFailure
            }
            val diagnostic = AgentHttpFailureDiagnostics.collect(status, body, headers, secrets)
            if (status in setOf(400, 413) && isContextOverflow(error)) {
                return AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "提供方确认上下文超限，需缩减上下文后重试。", diagnostic = diagnostic)
            }
            val permanent = isPermanent(error, body)
            val providerMessage = error?.optString("message")
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                .orEmpty()
            val safeProviderMessage = AgentHttpFailureDiagnostics.safe(providerMessage, secrets, 400)
            val retryAfter = headers?.get("Retry-After")?.let { AgentHttpFailureDiagnostics.safe(it, secrets, 100) }
            val validationUrl = extractGoogleValidationUrl(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。" +
                    safeProviderMessage.takeIf { it.isNotBlank() }?.let { " 服务端：$it" }.orEmpty()
                else when (status) {
                    400 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        if (detail != null) "模型请求参数无效（HTTP 400）：$detail"
                        else "模型请求参数无效（HTTP 400），请检查模型配置。"
                    }
                    401 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                        if (detail != null && detail.contains("verify", true))
                            "Google 要求验证这个账号（HTTP 401）：$detail"
                        else "模型接口认证失败（HTTP 401）。OAuth 请重新登录，API Key 请检查密钥。"
                    }
                    403 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        val verify = validationUrl != null || (
                            detail != null && detail.contains("verify your account", ignoreCase = true)
                        )
                        when {
                            validationUrl != null ->
                                "Google 要求验证这个账号（HTTP 403）。请用无痕浏览器打开：$validationUrl 登录被标记的账号完成验证，然后再发消息。官网首页通常不会弹验证。"
                            verify -> "Google 要求先验证这个账号（HTTP 403）。请用无痕浏览器打开接口返回的 validation_url 完成账号验证。"
                            detail != null -> "模型接口拒绝访问（HTTP 403）：$detail"
                            else -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                        }
                    }
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。" +
                        safeProviderMessage.takeIf { it.isNotBlank() }?.let { " 服务端：$it" }.orEmpty() +
                        retryAfter?.let { " Retry-After：$it" }.orEmpty()
                    else -> {
                        val title = htmlTitle(body)
                        if (title != null || body.contains("<html", ignoreCase = true) ||
                            body.contains("<!DOCTYPE", ignoreCase = true)
                        ) {
                            "模型接口返回了错误网页（HTTP $status${title?.let { "：$it" } ?: ""}）"
                        } else {
                            "模型接口返回 HTTP $status"
                        }
                    }
                },
                diagnostic = diagnostic,
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (ResponsesToolEnvelopeRecovery.matches(error)) return toolEnvelopeRejected()
            // A stream may already have emitted visible text or invoked hosted tools.
            // Do not turn its late error into a replay of possible side effects.
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> unexpectedMediaType(failure)
        }

        fun unexpectedResponse(
            status: Int?,
            contentType: String?,
            body: String,
            cause: Throwable? = null,
        ): AgentModelFailure {
            val trimmed = body.trim()
            val type = contentType.orEmpty()
            if (trimmed.startsWith("{")) {
                return http(status ?: 200, trimmed)
            }
            if (trimmed.contains("event: response.") || trimmed.contains("response.created")) {
                return AgentModelFailure(
                    "HTTP_${status ?: 200}",
                    false,
                    "接口返回了 Responses API 事件流。请把该提供商的 Endpoint 模式改为 Responses API。",
                    cause,
                )
            }
            val html = type.contains("html", ignoreCase = true) ||
                trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
            val title = htmlTitle(trimmed)
            val statusLabel = status?.let { "HTTP $it" } ?: type.ifBlank { "未知类型" }
            val detail = title
                ?: trimmed.replace('\n', ' ').replace('\r', ' ').trim().take(120).ifBlank { null }
            return AgentModelFailure(
                code = "HTTP_${status ?: 200}",
                retryable = status == null || status in transientStatus,
                message = if (html) {
                    "模型接口返回了网页而不是数据流（$statusLabel${detail?.let { "：$it" } ?: ""}）。Chat Completions 可能被反代拦截，可改用 Responses API 或检查隧道/上游。"
                } else {
                    "模型接口返回了无法解析的响应（$statusLabel）${detail?.let { "：$it" } ?: ""}"
                },
                cause = cause,
            )
        }

        private fun unexpectedMediaType(failure: Exception): AgentModelFailure? {
            val message = failure.message.orEmpty()
            if (!message.startsWith("Invalid content-type")) return null
            val contentType = message.substringAfter("Invalid content-type:", "").trim().ifBlank { null }
            return unexpectedResponse(status = null, contentType = contentType, body = "", cause = failure)
        }

        private fun htmlTitle(body: String): String? {
            val start = body.indexOf("<title", ignoreCase = true).takeIf { it >= 0 } ?: return null
            val openEnd = body.indexOf('>', start).takeIf { it >= 0 } ?: return null
            val close = body.indexOf("</title>", openEnd + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
            val title = body.substring(openEnd + 1, close)
            val collapsed = buildString(title.length) {
                var gap = false
                title.forEach { ch ->
                    if (ch.isWhitespace()) {
                        if (!gap) {
                            append(' ')
                            gap = true
                        }
                    } else {
                        append(ch)
                        gap = false
                    }
                }
            }.trim()
            return collapsed.take(80).ifBlank { null }
        }

        internal fun extractGoogleValidationUrl(error: JSONObject?, body: String): String? {
            val details = error?.optJSONArray("details")
            if (details != null) {
                for (index in 0 until details.length()) {
                    val item = details.optJSONObject(index) ?: continue
                    val url = item.optJSONObject("metadata")?.optString("validation_url")
                        ?.takeIf { it.isNotBlank() }
                    if (url != null && (
                            item.optString("reason").equals("VALIDATION_REQUIRED", true) ||
                                url.startsWith("https://accounts.google.com/")
                            )
                    ) {
                        return url
                    }
                }
            }
            val match = Regex("""https://accounts\.google\.com/signin/continue[^"\\\s]+""").find(body)
            return match?.value
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            val code = error.optString("code").lowercase()
            val type = error.optString("type").lowercase()
            if (code in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long") ||
                type in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long")) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("context window exceeded")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
