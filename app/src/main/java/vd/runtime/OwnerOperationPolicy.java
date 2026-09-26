package vd.runtime;

/** Read-only response loss is not evidence of a lifecycle mutation. Unknown ops fail closed. */
final class OwnerOperationPolicy {
    private OwnerOperationPolicy() {}
    static boolean mayMutate(String op) {
        return !OwnerProtocol.OP_STATUS.equals(op) && !OwnerProtocol.OP_SNAPSHOT.equals(op);
    }
    static boolean timeoutMayHaveMutated(String op, boolean started) {
        return started && mayMutate(op);
    }
}
