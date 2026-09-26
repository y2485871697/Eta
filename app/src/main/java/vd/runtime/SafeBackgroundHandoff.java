package vd.runtime;

import java.util.List;

/** Ordered, fakeable movement only. Never retries, compensates, or repairs focus after mutation. */
final class SafeBackgroundHandoff {
    interface Backend {
        /** Resolve every capability used below; no display/task/window mutations. */
        void preflight() throws Exception;
        Object createStaging() throws Exception;
        void checkStaging(Object staging) throws Exception;
        void checkSource(int taskId) throws Exception;
        void checkDestination(int taskId) throws Exception;
        void checkFocus() throws Exception;
        void hide(int taskId) throws Exception;
        /** One WCT: selected -> staging bottom -> current display default bottom; restore/reorder. */
        void relocateAndRestore(int taskId, Object staging) throws Exception;
        /** Re-prove identity and emptiness immediately before deletion; verify absence afterwards. */
        void deleteStaging(Object staging) throws Exception;
    }

    private final Backend backend;
    private final HandoffProgress progress;
    private boolean prepared;
    private boolean started;

    SafeBackgroundHandoff(Backend backend, HandoffProgress progress) {
        this.backend = backend;
        this.progress = progress;
    }

    void preflight() throws OwnerHandoff.HandoffFailure {
        if (started) throw failure("background:already-attempted", null, true);
        try {
            backend.preflight();
            prepared = true;
        } catch (Throwable ex) {
            throw failure("preflight:capability", ex, false);
        }
    }

    /** Called only AFTER the source anchor side effect; every failure here is uncertain. */
    void move(List<Integer> selected) throws OwnerHandoff.HandoffFailure {
        if (started) throw failure("background:already-attempted", null, true);
        started = true;
        String phase = "background:prepared";
        try {
            if (!prepared) throw new IllegalStateException("not prepared");
            if (selected.isEmpty()) return;
            phase = "staging:before";
            backend.checkFocus();
            phase = "staging:create";
            Object staging = backend.createStaging();
            backend.checkFocus();
            for (int id : selected) {
                phase = "before:" + id;
                backend.checkSource(id);
                backend.checkFocus();
                backend.checkStaging(staging);
                phase = "hide:" + id;
                // Record BEFORE calling a mutator: even an exception may follow a committed IPC.
                progress.attempted(id);
                backend.hide(id);
                backend.checkSource(id);
                backend.checkFocus();
                phase = "move:" + id;
                backend.checkStaging(staging);
                backend.relocateAndRestore(id, staging);
                backend.checkDestination(id);
                // This fact must survive the immediately following strict focus rejection.
                progress.relocated(id);
                backend.checkFocus();
                phase = "complete:" + id;
                backend.checkStaging(staging);
                // The focus/staging reads must not mask loss of the selected task identity.
                backend.checkDestination(id);
                progress.completed(id);
            }
            phase = "staging:delete";
            backend.checkFocus();
            // Earlier completed tasks can disappear while later tasks move. Historical progress
            // is not authorization to clean up when their current identity is no longer proven.
            for (int id : selected) backend.checkDestination(id);
            backend.deleteStaging(staging);
            backend.checkFocus();
        } catch (Throwable ex) {
            // No finally cleanup: a failed apply may have stranded a child inside staging.
            throw failure(phase, ex, true);
        }
    }

    private OwnerHandoff.HandoffFailure failure(String phase, Throwable cause, boolean attempted) {
        HandoffProgress.Snapshot snapshot = progress.snapshot();
        return new OwnerHandoff.HandoffFailure(phase, cause, attempted,
                "moved=" + snapshot.completedTaskIds + " removed=[]", snapshot);
    }
}
