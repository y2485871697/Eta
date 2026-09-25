package vd.runtime;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StagingRootGuardTest {
    private final Object token = new Object(), binder = new Object(), cookie = new Object();

    private StagingRootGuard.View empty() {
        return new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true);
    }
    private StagingRootGuard guard() {
        return StagingRootGuard.identify(Collections.singletonMap(1, new Object()),
                Collections.singletonList(empty()), cookie);
    }
    private static final class Access implements StagingRootGuard.Access {
        StagingRootGuard.View first, second;
        List<?> children = Collections.emptyList();
        int reads, deletes;
        Object deletedToken;
        boolean absent = true;
        Access(StagingRootGuard.View view) { first = second = view; }
        @Override public StagingRootGuard.View read(int id) { return ++reads == 1 ? first : second; }
        @Override public List<?> children(Object token) { return children; }
        @Override public void delete(Object token) { deletes++; deletedToken = token; }
        @Override public boolean absent(int id, Object binder) { return absent; }
    }
    private void rejectsWithoutDelete(StagingRootGuard.View changed) {
        Access access = new Access(changed);
        assertThrows(IllegalStateException.class, () -> guard().delete(access));
        assertEquals(0, access.deletes);
    }

    @Test public void onlyOurNewUniqueCookieRootIsEligible() {
        StagingRootGuard guard = guard();
        assertEquals(900, guard.id);
        assertSame(token, guard.token);
        assertSame(binder, guard.binder);
        Map<Integer, Object> sameId = Collections.singletonMap(900, new Object());
        assertThrows(IllegalStateException.class, () -> StagingRootGuard.identify(
                sameId, Collections.singletonList(empty()), cookie));
        Map<Integer, Object> reusedToken = Collections.singletonMap(1, binder);
        assertThrows(IllegalStateException.class, () -> StagingRootGuard.identify(
                reusedToken, Collections.singletonList(empty()), cookie));
        assertThrows(IllegalStateException.class, () -> StagingRootGuard.identify(
                Collections.emptyMap(), Arrays.asList(empty(), empty()), cookie));
        assertThrows(IllegalStateException.class, () -> StagingRootGuard.identify(
                Collections.emptyMap(), Collections.singletonList(empty()), new Object()));
    }

    @Test public void occupiedRootAndIdentityBearingEmptyRootCannotBeDeleted() {
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 1, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{8}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, false));
        Access occupied = new Access(empty());
        occupied.children = Collections.singletonList(new Object()); // Even a zero-activity child.
        assertThrows(IllegalStateException.class, () -> guard().delete(occupied));
        assertEquals(0, occupied.deletes);
    }

    @Test public void unknownChildrenAndMissingRootNeverAuthorizeDelete() {
        Access unknown = new Access(empty());
        unknown.children = null;
        assertThrows(IllegalStateException.class, () -> guard().delete(unknown));
        assertEquals(0, unknown.deletes);
        rejectsWithoutDelete(null);
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), null, true));
    }

    @Test public void changedBinderBetweenChildAndRootReadsPreventsDelete() {
        Access changed = new Access(empty());
        changed.second = new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, new Object(),
                new Object(), Collections.singletonList(cookie), new int[]{900}, true);
        assertThrows(IllegalStateException.class, () -> guard().delete(changed));
        assertEquals(2, changed.reads);
        assertEquals(0, changed.deletes);
    }

    @Test public void changedCookieDisplayUserParentOrModePreventsDelete() {
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(new Object()), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 2, 0, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 10, -1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, 1, 0, 0, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 1, 1, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
        rejectsWithoutDelete(new StagingRootGuard.View(900, 0, 0, -1, 0, 0, 5, token, binder,
                Collections.singletonList(cookie), new int[]{900}, true));
    }

    @Test public void successfulDeleteUsesOnlySavedTokenAfterFreshEmptyProof() throws Exception {
        Access access = new Access(empty());
        guard().delete(access);
        assertEquals(2, access.reads);
        assertEquals(1, access.deletes);
        assertSame(token, access.deletedToken);
    }

    @Test public voidUnverifiedDeleteThrowsWithoutRepeatingDeletion() {
        Access access = new Access(empty());
        access.absent = false;
        assertThrows(IllegalStateException.class, () -> guard().delete(access));
        assertEquals(1, access.deletes);
    }
}
