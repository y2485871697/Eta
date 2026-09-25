package vd.runtime;

import org.json.JSONObject;

/**
 * Maps protocol operations onto {@link VirtualDisplayOwner}. Runs on the owner looper thread.
 *
 * <p>An operation is only ever reported as {@code ok=true} when the owner returned a body. Every
 * {@link OwnerException} becomes {@code ok=false} with its code, and any unexpected throwable
 * becomes {@code INTERNAL} rather than a silent success.
 *
 * <p>This layer also nests the bounded, read-only {@link OwnerRootTaskDiagnostic} under
 * {@link OwnerRootTaskDiagnostic#FIELD} on every {@code status} reply and on a
 * {@link LaunchTargetOccupancy#UNKNOWN} launch failure, so a caller can see which root task the
 * inventory could not identify. Attaching the diagnostic never changes the ok/error outcome and
 * never relaxes a launch / handoff / release gate.
 */
final class OwnerCommandDispatcher implements OwnerIpcServer.Dispatcher {
    private final VirtualDisplayOwner owner;
    private volatile boolean stop;

    OwnerCommandDispatcher(VirtualDisplayOwner owner) {
        this.owner = owner;
    }

    @Override
    public JSONObject dispatch(OwnerProtocol.Request request) {
        try {
            if (OwnerProtocol.OP_STATUS.equals(request.op)) {
                JSONObject body = owner.status();
                attachDiagnostic(body);
                return OwnerProtocol.ok(request.op, body);
            }
            if (OwnerProtocol.OP_LAUNCH.equals(request.op)) {
                return OwnerProtocol.ok(request.op, owner.launch(request.payload));
            }
            if (OwnerProtocol.OP_INPUT.equals(request.op)) {
                return OwnerProtocol.ok(request.op, owner.input(request.payload));
            }
            if (OwnerProtocol.OP_SNAPSHOT.equals(request.op)) {
                return OwnerProtocol.ok(request.op, owner.snapshot(request.payload));
            }
            if (OwnerProtocol.OP_HANDOFF.equals(request.op)) {
                return OwnerProtocol.ok(request.op, owner.handoff(request.payload));
            }
            if (OwnerProtocol.OP_RELEASE.equals(request.op)) {
                JSONObject body = owner.release(request.payload);
                stop = true;
                return OwnerProtocol.ok(request.op, body);
            }
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_UNKNOWN_OP, request.op);
        } catch (OwnerException ex) {
            JSONObject response = failureResponse(request.op, ex);
            if (LaunchTargetOccupancy.UNKNOWN.equals(ex.code)) {
                attachDiagnostic(response);
            }
            return response;
        } catch (Throwable unexpected) {
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL,
                    unexpected.getClass().getSimpleName());
        }
    }

    /** Pure wire encoding. Progress is historical only, never a cleanup/retry/release capability. */
    static JSONObject failureResponse(String op, OwnerException failure) {
        JSONObject response = OwnerProtocol.fail(op, failure.code, failure.getMessage());
        if (failure instanceof OwnerHandoff.HandoffFailure) {
            OwnerHandoff.HandoffFailure handoff = (OwnerHandoff.HandoffFailure) failure;
            try {
                response.put("retryable", handoff.retryable());
                response.put("sideEffectsAttempted", handoff.sideEffectsAttempted());
                response.put("handoffPhase", OwnerHandoff.sanitizePhase(handoff.phase()));
                HandoffProgress.Snapshot progress = handoff.progress();
                response.put("attemptedTaskIds", new org.json.JSONArray(progress.mutationAttemptedTaskIds));
                response.put("relocatedTaskIds", new org.json.JSONArray(progress.relocatedTaskIds));
                response.put("completedTaskIds", new org.json.JSONArray(progress.completedTaskIds));
                // Keep the draft's nested diagnostic for consumers that already read it.
                response.put(HandoffProgress.FIELD, progress.toJson());
                response.put("focusSamples", new org.json.JSONArray(handoff.focusSamples()));
            } catch (Exception ignored) { /* Diagnostics must not change the failure outcome. */ }
        }
        return response;
    }

    /**
     * Nests the read-only inventory diagnostic under {@link OwnerRootTaskDiagnostic#FIELD}.
     * A diagnostic failure must never change the reported outcome, so it is swallowed.
     */
    private static void attachDiagnostic(JSONObject response) {
        if (response == null) {
            return;
        }
        try {
            response.put(OwnerRootTaskDiagnostic.FIELD, OwnerRootTaskDiagnostic.collect());
        } catch (Exception ignored) {
            // Never turn a reported outcome into a different one.
        }
    }

    @Override
    public boolean shouldStop() {
        return stop || owner.isReleased();
    }
}
