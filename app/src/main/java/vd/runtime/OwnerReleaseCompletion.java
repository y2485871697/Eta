package vd.runtime;

/**
 * Per-command rendezvous between owner dispatch and its socket response attempt. Only verified
 * release authorizes exit. A completed flush, failed flush, disconnect or timed-out waiter all
 * finish delivery, but none can authorize release. Either event may arrive first.
 */
final class OwnerReleaseCompletion {
    private boolean verifiedReleased;
    private boolean responseFinished;
    private boolean stopClaimed;

    synchronized boolean dispatchFinished(boolean released) {
        verifiedReleased |= released;
        return claimStop();
    }

    synchronized boolean responseFinished() {
        responseFinished = true;
        return claimStop();
    }

    private boolean claimStop() {
        if (!verifiedReleased || !responseFinished || stopClaimed) return false;
        stopClaimed = true;
        return true;
    }
}
