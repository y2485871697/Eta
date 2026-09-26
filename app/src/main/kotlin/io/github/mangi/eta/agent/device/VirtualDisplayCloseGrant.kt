package io.github.mangi.eta.agent.device

/** Single-use confirmations belong to one HTTP grant and one exact target. */
internal class VirtualDisplayCloseGrant {
    private var pending: Pair<String, VirtualDisplayPreviewHttpServer.Identity>? = null
    @Synchronized fun issue(nonce: String, identity: VirtualDisplayPreviewHttpServer.Identity) {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        pending = nonce to identity
    }
    @Synchronized fun consume(nonce: String, identity: VirtualDisplayPreviewHttpServer.Identity): Boolean {
        val saved = pending
        pending = null
        return saved != null && saved.first == nonce && saved.second == identity
    }
    @Synchronized fun revoke() { pending = null }
}
