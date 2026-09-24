package vd.runtime;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerTaskInventoryMismatchTest {
    @Test public void replacementIsDiagnosedNotAdopted() {
        OwnerTaskInventoryMismatch m=OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Arrays.asList(31));
        assertEquals(Collections.singleton(30),m.missing);
        assertEquals(Collections.singleton(31),m.foreign);
        assertEquals("preflight:inventory:missing30:unowned31",m.phase());
        OwnerHandoff.HandoffFailure f=new OwnerHandoff.HandoffFailure(m.phase(),m,false,"moved=[] removed=[]");
        assertEquals("HANDOFF_TASK_SET_CHANGED",f.code);
        assertFalse(f.sideEffectsAttempted());
    }
    @Test public void disappearanceAndForeignAppearanceAreNotConfused() {
        assertEquals(Collections.singleton(30),OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Collections.<Integer>emptyList()).missing);
        assertEquals(Collections.singleton(31),OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Arrays.asList(30,31)).foreign);
    }
    @Test public void unchangedSetStillRequiresExistingIdentityChecks() {
        assertNull(OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Arrays.asList(30)));
        assertNull(OwnerTaskInventoryMismatch.detect(Collections.<Integer>emptyList(),Collections.<Integer>emptyList()));
    }
    @Test public void unreadableAndMalformedInventoryFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->OwnerTaskInventoryMismatch.detect(null,Arrays.asList(30)));
        assertThrows(IllegalArgumentException.class,()->OwnerTaskInventoryMismatch.detect(Arrays.asList(30),null));
        assertThrows(IllegalArgumentException.class,()->OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Arrays.asList(31,31)));
        assertThrows(IllegalArgumentException.class,()->OwnerTaskInventoryMismatch.detect(Arrays.asList(0),Arrays.asList(31)));
    }
    @Test public void postMutationMismatchRemainsUncertain() {
        OwnerTaskInventoryMismatch m=OwnerTaskInventoryMismatch.detect(Arrays.asList(30),Arrays.asList(31));
        OwnerHandoff.HandoffFailure f=new OwnerHandoff.HandoffFailure("move",m,true,"moved=[] removed=[]");
        assertEquals("HANDOFF_UNCERTAIN",f.code);
        assertTrue(f.sideEffectsAttempted());
    }
}
