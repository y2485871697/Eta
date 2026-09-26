package io.github.mangi.eta.agent.device
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayCloseGrantTest {
    private val id=VirtualDisplayPreviewHttpServer.Identity(7,"virtual:test")
    @Test fun issuedNonceIsSingleUseAndBoundToIdentity() {
        val g=VirtualDisplayCloseGrant();g.issue("nonce",id)
        assertFalse(g.consume("nonce",id.copy(uniqueId="replacement")))
        assertFalse(g.consume("nonce",id));g.issue("new-nonce",id)
        assertTrue(g.consume("new-nonce",id));assertFalse(g.consume("new-nonce",id))
    }
    @Test fun oldAndOtherGrantCannotBeReused() {
        val a=VirtualDisplayCloseGrant();val b=VirtualDisplayCloseGrant();a.issue("nonce",id)
        assertFalse(b.consume("nonce",id));a.revoke();assertFalse(a.consume("nonce",id))
    }
}
