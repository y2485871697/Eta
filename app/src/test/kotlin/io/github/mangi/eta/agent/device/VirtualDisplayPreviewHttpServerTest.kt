package io.github.mangi.eta.agent.device

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayPreviewHttpServerTest {
    private val token = "a".repeat(64)
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
        val duplicate = "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"
        assertFalse(runCatching {
            VirtualDisplayPreviewHttpServer.readRequest(ByteArrayInputStream(duplicate.toByteArray()))
        }.isSuccess)
    }

    @Test fun pngValidationRequiresSignatureAndSixMiBLimit() {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertTrue(VirtualDisplayPreviewHttpServer.validPng(signature))
        assertFalse(VirtualDisplayPreviewHttpServer.validPng(ByteArray(8)))
        assertFalse(VirtualDisplayPreviewHttpServer.validPng(signature + ByteArray(6 * 1024 * 1024)))
    }
}
