package vd.runtime;

import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class FocusWitnessSnapshotTest {
    public static final class Token {
        private final Object identity;
        Token(Object identity) { this.identity=identity; }
        public Object asBinder() { return identity; }
    }
    public static final class Root {
        public int taskId=1, displayId=0, userId=0, parentTaskId=-1;
        public Token token;
        public Intent baseIntent=new Intent().setClassName("com.example.launcher", "com.example.launcher.Main");
        public int[] childTaskIds={2};
        public String[] childTaskNames={"com.example.launcher"};
        Root(Object identity) { token=new Token(identity); }
    }
    @Test public void missingInventoryIsRejected() {
        assertThrows(Exception.class, () -> FocusWitness.capture(new Root(new Object()),null,0,0));
    }
    @Test public void inconsistentSnapshotsAreRejected() {
        Object id=new Object(); Root a=new Root(id), b=new Root(id);
        b.baseIntent.setData(Uri.parse("test:other"));
        assertThrows(Exception.class, () -> FocusWitness.capture(a,b,0,0));
        b.baseIntent=new Intent().setClassName("com.other", "com.other.Main");
        assertThrows(Exception.class, () -> FocusWitness.capture(a,b,0,0));
        b.baseIntent=a.baseIntent; b.childTaskIds=new int[]{3};
        assertThrows(Exception.class, () -> FocusWitness.capture(a,b,0,0));
    }
    @Test public void childChangesAndNullBinderAreRejected() throws Exception {
        Object id=new Object(); Root a=new Root(id), b=new Root(id);
        FocusWitness witness=FocusWitness.capture(a,b,0,0);
        witness.check(new Root(id));
        a.childTaskIds[0]=3;
        assertThrows(Exception.class, () -> witness.check(a));
        b.childTaskNames[0]="com.other";
        assertThrows(Exception.class, () -> witness.check(b));
        Root missing=new Root(null);
        assertThrows(Exception.class, () -> FocusWitness.capture(missing,missing,0,0));
    }
    @Test public void baseMissingAndDataChangeHaveDistinctSafeReasons() throws Exception {
        Object id = new Object(); Root valid = new Root(id), missing = new Root(id);
        missing.baseIntent = null;
        assertEquals(FocusWitness.Reason.BASE_MISSING, assertThrows(FocusWitness.Rejected.class,
                () -> FocusWitness.capture(missing, missing, 0, 0)).reason);
        Root other = new Root(id);
        other.baseIntent.setData(Uri.parse("eta-vd://session/private-test-data"));
        FocusWitness.Rejected rejection = assertThrows(FocusWitness.Rejected.class,
                () -> FocusWitness.capture(valid, other, 0, 0));
        assertEquals(FocusWitness.Reason.DATA_CHANGED, rejection.reason);
        assertFalse(rejection.getMessage().contains("private-test-data"));
    }

    @Test public void malformedChildStructuresHaveDistinctReasonsWithoutLoggingNames() {
        assertEquals(FocusWitness.Reason.CHILD_IDS_UNREADABLE, FocusWitness.substructureFailure(1, null, null));
        assertEquals(FocusWitness.Reason.CHILD_NAMES_LENGTH_MISMATCH,
                FocusWitness.substructureFailure(1, new int[]{2}, new String[0]));
        assertEquals(FocusWitness.Reason.CHILD_IDS_DUPLICATE,
                FocusWitness.substructureFailure(1, new int[]{2, 2}, null));
        assertEquals(FocusWitness.Reason.CHILD_ID_INVALID,
                FocusWitness.substructureFailure(1, new int[]{-2}, null));
        assertEquals(FocusWitness.Reason.CHILD_NAMES_MISSING,
                FocusWitness.substructureFailure(1, new int[]{2}, null));
        assertEquals(FocusWitness.Reason.CHILD_NAME_UNKNOWN,
                FocusWitness.substructureFailure(1, new int[]{2}, new String[]{""}));
        assertNull(FocusWitness.substructureFailure(1, new int[]{2}, new String[]{"com.example.launcher"}));
    }

    @Test public void markerNameLengthMismatchIsRejected() {
        assertFalse(FocusWitness.focusSubstructureKnown(16,new int[]{16},new String[0]));
    }
}
