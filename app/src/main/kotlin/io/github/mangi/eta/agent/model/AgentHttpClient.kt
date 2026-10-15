package io.github.mangi.eta.agent.model

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients.
 *
 * Timeouts, connection retry and JSON Content-Type follow RikkaHub:
 * some gateways reject `application/json; charset=utf-8`, and a short
 * write timeout can fail large context uploads before the stream starts.
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val READ_TIMEOUT_MS = 600_000L
    private const val WRITE_TIMEOUT_MS = 120_000L
    const val MODEL_READ_TIMEOUT_MS = READ_TIMEOUT_MS

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .addInterceptor(ProviderRequestInterceptor)
            .addNetworkInterceptor(JsonContentTypeInterceptor)
            .addNetworkInterceptor(SseContentTypeInterceptor)
            .build()
    }

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .dispatcher(Dispatcher().apply {
                // Keep the model total budget; do not add a five-stream host bottleneck.
                maxRequestsPerHost = maxRequests
            })
            .build()
    }
}

private object ProviderRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", "Eta-Android")
        }
        val host = original.url.host
        if (host == "openrouter.ai" || host.endsWith(".openrouter.ai")) {
            if (original.header("X-Title").isNullOrBlank()) {
                builder.header("X-Title", "Eta")
            }
            if (original.header("HTTP-Referer").isNullOrBlank()) {
                builder.header("HTTP-Referer", "https://github.com/y2485871697/Eta")
            }
        }
        return chain.proceed(builder.build())
    }
}

private object JsonContentTypeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val contentType = request.header("Content-Type") ?: return chain.proceed(request)
        val rawType = contentType.substringBefore(";").trim()
        if (!rawType.equals("application/json", ignoreCase = true) || !contentType.contains(";")) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("Content-Type", "application/json")
                .build(),
        )
    }
}


private object SseContentTypeInterceptor : Interceptor {
    private val eventStream = "text/event-stream".toMediaType()

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val accept = request.header("Accept").orEmpty()
        if (!accept.contains("text/event-stream", ignoreCase = true)) return response
        if (accept.contains("application/json", ignoreCase = true)) return response
        if (!response.isSuccessful) return response
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.contains("text/event-stream", ignoreCase = true)) return response
        if (contentType.isNotBlank()) return response
        val body = response.body ?: return response
        return response.newBuilder()
            .header("Content-Type", "text/event-stream")
            .body(body.source().asResponseBody(eventStream, body.contentLength()))
            .build()
    }
}
