package io.github.mangi.eta.agent.device

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** A separate, revocable viewing capability. Never exposes the owner IPC credential. */
internal class VirtualDisplayPreviewHttpServer(private val capture: () -> Frame) {
    data class Frame(val png: ByteArray? = null, val displayId: Int = -1,
        val phase: String = "unknown", val error: String = "")
    data class Ticket(val port: Int, val token: String) {
        val viewerUri: String get() = "http://127.0.0.1:3070/#eta-preview=$port.$token"
        override fun toString() = "PreviewTicket(port=$port, credential=redacted)"
    }
    data class Request(val method: String, val path: String, val headers: Map<String, String>)
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val captureLock = ReentrantLock()
    private var lastCaptureNs = 0L
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2), { task ->
            Thread(task, "eta-vd-preview-reader").apply { isDaemon = true }
        })
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var running = false

    @Synchronized fun start(): Ticket {
        check(listener == null)
        // No wildcard-address fallback, even when binding fails.
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        listener = server
        running = true
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients.add(socket)
                try { workers.execute { serve(socket, server.localPort) } }
                catch (_: Exception) { clients.remove(socket); runCatching { socket.close() } }
            }
        }, "eta-vd-preview-listener").apply { isDaemon = true }.start()
        return Ticket(server.localPort, token)
    }

    @Synchronized fun stop() {
        running = false
        runCatching { listener?.close() }
        clients.forEach { runCatching { it.close() } }
        // Do not interrupt a capture that is sharing the authenticated owner transport.
        workers.shutdown()
    }

    private fun serve(socket: Socket, port: Int) {
        try {
            socket.soTimeout = 2_000
            val request = readRequest(socket.getInputStream())
            val decision = authorize(request, port, token)
            val origin = request.headers["origin"]?.takeIf { it in ALLOWED_ORIGINS }
            if (decision != 200) {
                write(socket, decision, "application/json", errorJson(protocolError(decision)), origin)
                return
            }
            if (!captureLock.tryLock()) {
                write(socket, 429, "application/json", errorJson("PREVIEW_BUSY"), origin); return
            }
            try {
                val now = System.nanoTime()
                if (lastCaptureNs != 0L && now - lastCaptureNs < 500_000_000L) {
                    write(socket, 429, "application/json", errorJson("PREVIEW_BUSY"), origin); return
                }
                lastCaptureNs = now
                val frame = try { capture() } catch (_: Exception) { Frame(error = "PREVIEW_UNAVAILABLE") }
                if (!running) return
                val png = frame.png
                if (png == null || frame.displayId <= 0 || !validPng(png)) {
                    val code = frame.error.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "PREVIEW_UNAVAILABLE"
                    write(socket, if (code == "NO_VIRTUAL_SESSION") 404 else 503,
                        "application/json", errorJson(code), origin)
                } else {
                    val phase = frame.phase.takeIf { it.matches(Regex("[a-z_]{1,32}")) } ?: "unknown"
                    write(socket, 200, "image/png", png, origin,
                        "X-Eta-Display-Id: ${frame.displayId}\r\nX-Eta-Phase: $phase\r\n")
                }
            } finally { captureLock.unlock() }
        } catch (_: Exception) {
            // No URLs, headers, credentials, screenshots or exception payloads are logged.
        } finally { clients.remove(socket); runCatching { socket.close() } }
    }

    private fun write(socket: Socket, status: Int, type: String, body: ByteArray, origin: String?, extra: String = "") {
        val payload = if (status == 204) byteArrayOf() else body
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            429 -> "Too Many Requests"; else -> "Service Unavailable" }
        val cors = if (origin == null) "" else
            "Access-Control-Allow-Origin: $origin\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization\r\nAccess-Control-Max-Age: 60\r\n" +
            "Access-Control-Expose-Headers: X-Eta-Display-Id, X-Eta-Phase\r\n"
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store, max-age=0\r\nPragma: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\nVary: Origin\r\n" + cors + extra + "\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(payload); flush() }
    }

    companion object {
        const val MAX_FRAME_BYTES = 6 * 1024 * 1024
        val ALLOWED_ORIGINS = setOf("http://127.0.0.1:3070", "http://localhost:3070")
        fun validPng(bytes: ByteArray): Boolean = bytes.size in 8..MAX_FRAME_BYTES &&
            bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
        private fun errorJson(code: String): ByteArray =
            ("{\"ok\":false,\"error\":\"" + code + "\"}").toByteArray(Charsets.UTF_8)

        private fun protocolError(status: Int): String = when (status) {
            401 -> "PREVIEW_AUTH_INVALID"
            404 -> "NO_VIRTUAL_SESSION"
            405 -> "PREVIEW_METHOD_NOT_ALLOWED"
            429 -> "PREVIEW_BUSY"
            204 -> ""
            else -> "PREVIEW_ACCESS_DENIED"
        }

        fun authorize(request: Request, port: Int, token: String): Int {
            if (request.headers["host"] !in setOf("127.0.0.1:$port", "localhost:$port") ||
                request.headers["origin"] !in ALLOWED_ORIGINS) return 403
            if (request.path != "/eta-preview/frame") return 404
            if (request.headers.containsKey("transfer-encoding") ||
                request.headers["content-length"]?.let { it != "0" } == true) return 403
            if (request.method == "OPTIONS") {
                val names = request.headers["access-control-request-headers"].orEmpty()
                    .split(',').map { it.trim().lowercase(Locale.ROOT) }
                return if (request.headers["access-control-request-method"] == "GET" &&
                    names.all { it == "authorization" || it.isEmpty() }) 204 else 403
            }
            if (request.method != "GET") return 405
            return if (MessageDigest.isEqual(("Bearer " + token).toByteArray(Charsets.US_ASCII),
                    request.headers["authorization"].orEmpty().toByteArray(Charsets.US_ASCII))) 200 else 401
        }

        fun readRequest(input: InputStream): Request {
            var remaining = 8192
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    check(remaining-- > 0 && bytes.size() < 2048)
                    val value = input.read()
                    check(value >= 0)
                    if (value == 10) {
                        val raw = bytes.toByteArray()
                        check(raw.isNotEmpty() && raw.last() == 13.toByte())
                        return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
                    }
                    check(value == 9 || value == 13 || value in 32..126)
                    bytes.write(value)
                }
            }
            val start = line().split(' ')
            check(start.size == 3 && start[2] in setOf("HTTP/1.0", "HTTP/1.1"))
            val headers = linkedMapOf<String, String>()
            while (true) {
                val raw = line()
                if (raw.isEmpty()) break
                check(headers.size < 32 && !raw.startsWith(' ') && !raw.startsWith('\t'))
                val split = raw.indexOf(':')
                check(split > 0)
                val key = raw.substring(0, split).lowercase(Locale.ROOT)
                val value = raw.substring(split + 1).trim()
                check(key.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(key))
                check(value.none { it == '\r' || it == '\n' })
                headers[key] = value
            }
            return Request(start[0], start[1], headers)
        }
    }
}
