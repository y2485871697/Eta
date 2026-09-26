package io.github.mangi.eta.agent.device
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayManualCloseHttpTest {
    private val base=mapOf("host" to "127.0.0.1:45678","origin" to "http://127.0.0.1:3070",
        "authorization" to "Bearer read","x-eta-control-token" to "control",
        "x-eta-display-id" to "7","x-eta-display-unique-id" to "virtual:test")
    private fun req(method:String="POST",path:String=VirtualDisplayPreviewHttpServer.CLOSE_PREPARE_PATH,headers:Map<String,String> = base)=
        VirtualDisplayPreviewHttpServer.Request(method,path,headers)
    @Test fun readOnlyTicketCannotMutate() { assertEquals(403,VirtualDisplayPreviewHttpServer.authorize(req(),45678,"read")) }
    @Test fun bothCapabilitiesAreRequired() {
        assertEquals(200,VirtualDisplayPreviewHttpServer.authorize(req(),45678,"read","control"))
        assertEquals(403,VirtualDisplayPreviewHttpServer.authorize(req(headers=base-"x-eta-control-token"),45678,"read","control"))
        assertEquals(401,VirtualDisplayPreviewHttpServer.authorize(req(headers=base-"authorization"),45678,"read","control"))
    }
    @Test fun getAndBadOriginCannotMutate() {
        assertEquals(405,VirtualDisplayPreviewHttpServer.authorize(req(method="GET"),45678,"read","control"))
        assertEquals(403,VirtualDisplayPreviewHttpServer.authorize(req(headers=base+mapOf("origin" to "https://other.invalid")),45678,"read","control"))
    }
    @Test fun targetAndCommitNonceAreMandatory() {
        assertTrue(runCatching { VirtualDisplayPreviewHttpServer.selectedIdentity(req(headers=base-"x-eta-display-unique-id")) }.isFailure)
        val path=VirtualDisplayPreviewHttpServer.CLOSE_COMMIT_PATH
        assertEquals(400,VirtualDisplayPreviewHttpServer.authorize(req(path=path),45678,"read","control"))
        assertEquals(200,VirtualDisplayPreviewHttpServer.authorize(req(path=path,headers=base+mapOf("x-eta-close-nonce" to "nonce-1")),45678,"read","control"))
    }
    @Test fun postBodyAndTransferEncodingAreRejected() {
        listOf(mapOf("content-length" to "1"),mapOf("transfer-encoding" to "chunked"),mapOf("expect" to "100-continue")).forEach {
            assertEquals(403,VirtualDisplayPreviewHttpServer.authorize(req(headers=base+it),45678,"read","control"))
        }
    }
    @Test fun ticketsAndRequestsRedactControlCapability() {
        assertFalse(VirtualDisplayPreviewHttpServer.Ticket(1,"read-secret","control-secret").toString().contains("secret"))
        assertFalse(req(headers=base+mapOf("x-eta-control-token" to "secret")).toString().contains("secret"))
    }
}
