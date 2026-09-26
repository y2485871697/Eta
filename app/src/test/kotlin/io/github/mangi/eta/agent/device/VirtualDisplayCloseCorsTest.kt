package io.github.mangi.eta.agent.device

import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayCloseCorsTest {
    private fun preflight(ticket: VirtualDisplayPreviewHttpServer.Ticket, path: String, method: String, names: String): String =
        Socket("127.0.0.1", ticket.port).use { socket ->
            socket.soTimeout = 3000
            val crlf = 13.toChar().toString() + 10.toChar()
            val raw = listOf("OPTIONS $path HTTP/1.1", "Host: 127.0.0.1:${ticket.port}",
                "Origin: http://127.0.0.1:3070", "Access-Control-Request-Method: $method",
                "Access-Control-Request-Headers: $names", "", "").joinToString(crlf)
            socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
            socket.getInputStream().bufferedReader().readText()
        }

    @Test fun controlPreflightIsScopedToTheExactRouteAndNeverInvokesOwner() {
        val calls = AtomicInteger()
        val server = VirtualDisplayPreviewHttpServer(
            discover = { calls.incrementAndGet(); VirtualDisplayPreviewHttpServer.Displays() },
            capture = { calls.incrementAndGet(); VirtualDisplayPreviewHttpServer.Frame() },
            prepareClose = { calls.incrementAndGet(); VirtualDisplayManualClose.Result("blocked", "TEST") },
            commitClose = { _, _ -> calls.incrementAndGet(); VirtualDisplayManualClose.Result("blocked", "TEST") },
        )
        try {
            val ticket = server.start()
            val read = "Authorization, X-Eta-Display-Id, X-Eta-Display-Unique-Id"
            val prepare = preflight(ticket, VirtualDisplayPreviewHttpServer.CLOSE_PREPARE_PATH, "POST", read + ", X-Eta-Control-Token")
            assertTrue(prepare.startsWith("HTTP/1.1 204"))
            assertTrue(prepare.contains("Access-Control-Allow-Methods: POST, OPTIONS"))
            assertTrue(prepare.contains("Access-Control-Allow-Headers: $read, X-Eta-Control-Token"))
            assertFalse(prepare.contains("X-Eta-Close-Nonce"))
            val commit = preflight(ticket, VirtualDisplayPreviewHttpServer.CLOSE_COMMIT_PATH, "POST", read + ", X-Eta-Control-Token, X-Eta-Close-Nonce")
            assertTrue(commit.startsWith("HTTP/1.1 204"))
            assertTrue(commit.contains("X-Eta-Control-Token, X-Eta-Close-Nonce"))
            val frame = preflight(ticket, VirtualDisplayPreviewHttpServer.FRAME_PATH, "GET", read)
            assertTrue(frame.startsWith("HTTP/1.1 204"))
            assertTrue(frame.contains("Access-Control-Allow-Methods: GET, OPTIONS"))
            assertFalse(frame.contains("X-Eta-Control-Token"))
            assertEquals(0, calls.get())
        } finally { server.stop() }
    }

    @Test fun readOnlyGrantCannotAdvertiseCloseCapability() {
        val server = VirtualDisplayPreviewHttpServer(
            discover = { VirtualDisplayPreviewHttpServer.Displays() },
            capture = { VirtualDisplayPreviewHttpServer.Frame() },
        )
        try {
            val response = preflight(server.start(), VirtualDisplayPreviewHttpServer.CLOSE_PREPARE_PATH, "POST",
                "Authorization, X-Eta-Display-Id, X-Eta-Display-Unique-Id, X-Eta-Control-Token")
            assertTrue(response.startsWith("HTTP/1.1 403"))
            assertFalse(response.contains("X-Eta-Control-Token"))
        } finally { server.stop() }
    }
}
