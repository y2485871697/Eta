package io.github.mangi.eta.agent.device

import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayPreviewHttpServerTest {
    private val token = "a".repeat(64)
    private val identity = VirtualDisplayPreviewHttpServer.Identity(7, "virtual:eta-123")
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    private val selection = mapOf("X-Eta-Display-Id" to "7", "X-Eta-Display-Unique-Id" to identity.uniqueId)
    private fun request(method: String = "GET", path: String = "/eta-preview/frame",
        auth: String = "Bearer $token", origin: String = "http://127.0.0.1:3070") =
        VirtualDisplayPreviewHttpServer.Request(method, path, mapOf(
            "host" to "127.0.0.1:45678",
            "origin" to origin,
            "authorization" to auth,
        ))

    @Test fun acceptsOnlyTheLocalPreviewOriginAndExactBearerToken() {
        assertEquals(200, VirtualDisplayPreviewHttpServer.authorize(request(), 45678, token))
        assertEquals(401, VirtualDisplayPreviewHttpServer.authorize(
            request(auth = "Bearer " + "b".repeat(64)), 45678, token))
        assertEquals(403, VirtualDisplayPreviewHttpServer.authorize(
            request(origin = "http://evil.invalid"), 45678, token))
        assertEquals(403, VirtualDisplayPreviewHttpServer.authorize(
            request().copy(headers = request().headers + ("host" to "127.0.0.1:45679")), 45678, token))
    }

    @Test fun corsPreflightIsReadOnlyAndBounded() {
        val preflight = request("OPTIONS").copy(headers = request("OPTIONS").headers + mapOf(
            "access-control-request-method" to "GET",
            "access-control-request-headers" to "authorization",
        ))
        assertEquals(204, VirtualDisplayPreviewHttpServer.authorize(preflight, 45678, token))
        val bad = preflight.copy(headers = preflight.headers + ("access-control-request-method" to "POST"))
        assertEquals(403, VirtualDisplayPreviewHttpServer.authorize(bad, 45678, token))
    }

    @Test fun parserRejectsAmbiguousOrOversizedHeaders() {
        val valid = "GET /eta-preview/frame HTTP/1.1\r\nHost: 127.0.0.1:45678\r\n\r\n"
        val parsed = VirtualDisplayPreviewHttpServer.readRequest(ByteArrayInputStream(valid.toByteArray()))
        assertEquals("GET", parsed.method)
        assertEquals("/eta-preview/frame", parsed.path)
        assertEquals("127.0.0.1:45678", parsed.headers["host"])
        for (headers in listOf("Host: a\r\nHost: b", "X-Eta-Display-Id: 7\r\nx-eta-display-id: 8",
                "X-Long: " + "a".repeat(2048))) {
            assertFalse(runCatching {
                VirtualDisplayPreviewHttpServer.readRequest(ByteArrayInputStream(
                    "GET / HTTP/1.1\r\n$headers\r\n\r\n".toByteArray()))
            }.isSuccess)
        }
    }

    @Test fun pngValidationRequiresSignatureAndSixMiBLimit() {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertTrue(VirtualDisplayPreviewHttpServer.validPng(signature))
        assertFalse(VirtualDisplayPreviewHttpServer.validPng(ByteArray(8)))
        assertFalse(VirtualDisplayPreviewHttpServer.validPng(signature + ByteArray(6 * 1024 * 1024)))
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private fun exchange(ticket: VirtualDisplayPreviewHttpServer.Ticket,
        path: String = VirtualDisplayPreviewHttpServer.FRAME_PATH, method: String = "GET",
        headers: Map<String, String> = emptyMap(), auth: Boolean = true,
        origin: String = "http://127.0.0.1:3070",
    ): Response = Socket("127.0.0.1", ticket.port).use { socket ->
        socket.soTimeout = 3000
        val all = linkedMapOf("Host" to "127.0.0.1:${ticket.port}", "Origin" to origin)
        if (auth) all["Authorization"] = "Bearer ${ticket.token}"
        all.putAll(headers)
        val raw = "$method $path HTTP/1.1\r\n" +
            all.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        socket.getOutputStream().apply { write(raw.toByteArray(Charsets.US_ASCII)); flush() }
        val bytes = socket.getInputStream().readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        check(split >= 0) { "HTTP response missing" }
        val lines = text.substring(0, split).split("\r\n")
        Response(lines.first().split(' ')[1].toInt(), lines.drop(1).associate {
            it.substringBefore(':').lowercase(Locale.ROOT) to it.substringAfter(':').trim()
        }, bytes.copyOfRange(split + 4, bytes.size))
    }

    private fun withServer(
        discover: () -> VirtualDisplayPreviewHttpServer.Displays = {
            VirtualDisplayPreviewHttpServer.Displays(listOf(
                VirtualDisplayPreviewHttpServer.Display(identity.displayId, identity.uniqueId, "held")))
        },
        capture: (VirtualDisplayPreviewHttpServer.Identity?) -> VirtualDisplayPreviewHttpServer.Frame = {
            VirtualDisplayPreviewHttpServer.Frame(png, identity.displayId, "held", uniqueId = identity.uniqueId)
        },
        block: (VirtualDisplayPreviewHttpServer.Ticket) -> Unit,
    ) {
        val server = VirtualDisplayPreviewHttpServer(discover, capture)
        try { block(server.start()) } finally { server.stop() }
    }

    @Test fun loopbackListIsAuthenticatedAndContainsOnlyPublicIdentity() {
        withServer { ticket ->
            val response = exchange(ticket, VirtualDisplayPreviewHttpServer.DISPLAYS_PATH)
            assertEquals(200, response.status)
            assertEquals("application/json", response.headers["content-type"])
            assertEquals("{\"ok\":true,\"displays\":[{\"displayId\":7,\"uniqueId\":\"virtual:eta-123\",\"phase\":\"held\"}]}", response.text)
            assertFalse(response.text.contains(ticket.token))
            assertFalse(response.text.contains("socket"))
            assertEquals("no-store, max-age=0", response.headers["cache-control"])
        }
    }

    @Test fun loopbackSelectedFrameUsesPngAndEchoesExactIdentity() {
        withServer(capture = { selected ->
            assertEquals(identity, selected)
            VirtualDisplayPreviewHttpServer.Frame(png, 7, "held", uniqueId = identity.uniqueId)
        }) { ticket ->
            val response = exchange(ticket, headers = selection)
            assertEquals(200, response.status)
            assertEquals("image/png", response.headers["content-type"])
            assertEquals("7", response.headers["x-eta-display-id"])
            assertEquals(identity.uniqueId, response.headers["x-eta-display-unique-id"])
            assertArrayEquals(png, response.body)
            assertTrue(VirtualDisplayPreviewHttpServer.validPng(response.body))
            assertEquals("http://127.0.0.1:3070", response.headers["access-control-allow-origin"])
        }
    }

    @Test fun loopbackLegacyFrameStillReturnsRealIdentity() {
        withServer(capture = { selected ->
            assertEquals(null, selected)
            VirtualDisplayPreviewHttpServer.Frame(png, 7, uniqueId = identity.uniqueId)
        }) { ticket ->
            val response = exchange(ticket)
            assertEquals(200, response.status)
            assertEquals(identity.uniqueId, response.headers["x-eta-display-unique-id"])
        }
    }

    @Test fun loopbackRejectsPartialIllegalAndQueryIdentityBeforeCapture() {
        val calls = AtomicInteger()
        withServer(capture = { calls.incrementAndGet(); VirtualDisplayPreviewHttpServer.Frame() }) { ticket ->
            for (bad in listOf(mapOf("X-Eta-Display-Id" to "7"),
                    mapOf("X-Eta-Display-Unique-Id" to identity.uniqueId),
                    selection + ("X-Eta-Display-Id" to "0"),
                    selection + ("X-Eta-Display-Id" to "2147483648"),
                    selection + ("X-Eta-Display-Unique-Id" to "bad/id"))) {
                assertEquals(400, exchange(ticket, headers = bad).status)
            }
            for (path in listOf("/eta-preview/frame?displayId=7&uniqueId=virtual:eta-123",
                    "/eta-preview/frame?", "/eta-preview/displays?token=x")) {
                assertEquals(400, exchange(ticket, path).status)
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun loopbackRejectsReusedIdWithDifferentUniqueId() {
        withServer { ticket ->
            val response = exchange(ticket, headers = selection + ("X-Eta-Display-Unique-Id" to "virtual:old"))
            assertEquals(410, response.status)
            assertTrue(response.text.contains("PREVIEW_DISPLAY_GONE"))
            assertFalse(response.headers.containsKey("x-eta-display-id"))
        }
    }

    @Test fun loopbackPreflightAllowsOnlyAuthorizationAndPairedIdentityHeaders() {
        withServer { ticket ->
            fun preflight(names: String, method: String = "GET") = exchange(ticket, method = "OPTIONS", auth = false,
                headers = mapOf("Access-Control-Request-Method" to method, "Access-Control-Request-Headers" to names))
            val response = preflight("Authorization, X-Eta-Display-Id, X-Eta-Display-Unique-Id")
            assertEquals(204, response.status)
            assertEquals(0, response.body.size)
            assertEquals("Authorization, X-Eta-Display-Id, X-Eta-Display-Unique-Id", response.headers["access-control-allow-headers"])
            assertEquals(204, preflight("authorization").status)
            for (names in listOf("", "authorization, x-evil", "authorization, x-eta-display-id",
                    "x-eta-display-id, x-eta-display-unique-id", "authorization, authorization")) {
                assertEquals(403, preflight(names).status)
            }
            assertEquals(403, preflight("authorization", "POST").status)
        }
    }

    @Test fun loopbackUnauthorizedRequestsNeverReachOwner() {
        val calls = AtomicInteger()
        withServer(discover = { calls.incrementAndGet(); VirtualDisplayPreviewHttpServer.Displays() },
            capture = { calls.incrementAndGet(); VirtualDisplayPreviewHttpServer.Frame() }) { ticket ->
            for (path in listOf(VirtualDisplayPreviewHttpServer.DISPLAYS_PATH, VirtualDisplayPreviewHttpServer.FRAME_PATH)) {
                assertEquals(401, exchange(ticket, path, auth = false).status)
                assertEquals(401, exchange(ticket, path, headers = mapOf("Authorization" to "Bearer wrong")).status)
                val denied = exchange(ticket, path, origin = "http://evil.invalid")
                assertEquals(403, denied.status)
                assertFalse(denied.headers.containsKey("access-control-allow-origin"))
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun loopbackOwnerFailureIsNotAnEmptyList() {
        for (error in listOf("BOOT_ID_UNAVAILABLE", "RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN")) {
            withServer(discover = { VirtualDisplayPreviewHttpServer.Displays(error = error) }) { ticket ->
                val response = exchange(ticket, VirtualDisplayPreviewHttpServer.DISPLAYS_PATH)
                assertEquals(503, response.status)
                assertTrue(response.text.contains(error))
                assertFalse(response.text.contains("displays"))
            }
        }
    }

    @Test fun loopbackRejectsNonPngPayload() {
        withServer(capture = { VirtualDisplayPreviewHttpServer.Frame(ByteArray(8), 7, uniqueId = identity.uniqueId) }) { ticket ->
            assertEquals(503, exchange(ticket, headers = selection).status)
        }
    }
}
