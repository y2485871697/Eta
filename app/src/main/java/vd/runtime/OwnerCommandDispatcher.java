package vd.runtime;

import org.json.JSONObject;

/**
 * Maps protocol operations onto {@link VirtualDisplayOwner}. Runs on the owner looper thread.
 *
 * <p>An operation is only ever reported as {@code ok=true} when the owner returned a body. Every
 * {@link OwnerException} becomes {@code ok=false} with its code, and any unexpected throwable
 * becomes {@code INTERNAL} rather than a silent success.
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
                return OwnerProtocol.ok(request.op, owner.status());
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
            return OwnerProtocol.fail(request.op, ex.code, ex.getMessage());
        } catch (Throwable unexpected) {
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL,
                    unexpected.getClass().getSimpleName());
        }
    }

    @Override
    public boolean shouldStop() {
        return stop || owner.isReleased();
    }
}
