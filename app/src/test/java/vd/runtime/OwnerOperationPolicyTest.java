package vd.runtime;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerOperationPolicyTest {
    @Test public void readOnlyResponseLossDoesNotPoisonRecovery() {
        for (String op : new String[]{OwnerProtocol.OP_STATUS, OwnerProtocol.OP_SNAPSHOT}) {
            assertFalse(OwnerOperationPolicy.mayMutate(op));
            assertFalse(OwnerOperationPolicy.timeoutMayHaveMutated(op, true));
        }
    }
    @Test public void queuedCancelledMutationHasNotRun() {
        assertFalse(OwnerOperationPolicy.timeoutMayHaveMutated(OwnerProtocol.OP_RELEASE, false));
    }
    @Test public void startedMutationAndUnknownOperationRemainFailClosed() {
        assertTrue(OwnerOperationPolicy.timeoutMayHaveMutated(OwnerProtocol.OP_RELEASE, true));
        assertTrue(OwnerOperationPolicy.mayMutate("unknown"));
        assertTrue(OwnerOperationPolicy.mayMutate(null));
    }
}
