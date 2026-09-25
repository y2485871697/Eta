package vd.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class BackgroundTaskApiTest {
    public interface Cookie { }
    public static final class Token {
        final String name;
        int display;
        Token(String name, int display) { this.name = name; this.display = display; }
    }
    static final class Op {
        final Token child, parent;
        final boolean reparent, onTop;
        Op(Token child, Token parent, boolean reparent, boolean onTop) {
            this.child = child; this.parent = parent; this.reparent = reparent; this.onTop = onTop;
        }
    }
    public static final class Tx {
        final List<Op> hierarchy = new ArrayList<Op>();
        Token hiddenTarget, focusTarget;
        boolean hidden, focusable;
        public Tx() { }
        public Tx setHidden(Token token, boolean value) {
            hiddenTarget = token; hidden = value; return this;
        }
        public Tx setFocusable(Token token, boolean value) {
            focusTarget = token; focusable = value; return this;
        }
        public Tx reparent(Token child, Token parent, boolean onTop) {
            hierarchy.add(new Op(child, parent, true, onTop)); return this;
        }
        public Tx reorder(Token child, boolean onTop) {
            hierarchy.add(new Op(child, null, false, onTop)); return this;
        }
    }
    public static final class Organizer {
        static int constructors, registrations, creates, deletions;
        static int display, mode;
        static boolean removeWithOrganizer;
        static Object cookie;
        static final List<Tx> applied = new ArrayList<Tx>();
        public Organizer() { constructors++; }
        public void registerOrganizer() { registrations++; }
        public void unregisterOrganizer() { registrations++; }
        public void createRootTask(int displayId, int windowingMode, Cookie launchCookie, boolean remove) {
            creates++; display = displayId; mode = windowingMode;
            cookie = launchCookie; removeWithOrganizer = remove;
        }
        public boolean deleteRootTask(Token token) { deletions++; return true; }
        public List<Object> getChildTasks(Token token, int[] types) { return Collections.emptyList(); }
        public void applyTransaction(Tx change) {
            applied.add(change);
            for (Op op : change.hierarchy) {
                // Null resolves the child's CURRENT display, not display 0 by magic.
                if (op.reparent && op.parent != null) op.child.display = op.parent.display;
            }
        }
    }
    public static final class MissingReparentTx {
        public MissingReparentTx() { fail("must resolve methods before constructing a transaction"); }
        public MissingReparentTx setHidden(Token token, boolean value) { return this; }
        public MissingReparentTx setFocusable(Token token, boolean value) { return this; }
        public MissingReparentTx reorder(Token token, boolean value) { return this; }
    }

    @Before public void reset() {
        Organizer.constructors = Organizer.registrations = Organizer.creates = Organizer.deletions = 0;
        Organizer.cookie = null;
        Organizer.applied.clear();
    }
    private BackgroundTaskApi api() throws Exception {
        return new BackgroundTaskApi(Organizer.class, Tx.class, Token.class, Cookie.class);
    }

    @Test public void oneMovementTransactionUsesOrderedBottomHopsAndSameTransactionRestore() throws Exception {
        BackgroundTaskApi api = api();
        Token selected = new Token("selected", 2);
        Token staging = new Token("staging", 0);
        Token foreground = new Token("main-user-task", 0);
        api.relocateAndRestore(selected, staging);
        assertEquals(1, Organizer.applied.size());
        Tx tx = Organizer.applied.get(0);
        assertEquals(3, tx.hierarchy.size());
        Op first = tx.hierarchy.get(0), second = tx.hierarchy.get(1), third = tx.hierarchy.get(2);
        assertTrue(first.reparent); assertTrue(second.reparent); assertFalse(third.reparent);
        assertSame(staging, first.parent);
        assertNull(second.parent);
        for (Op op : tx.hierarchy) {
            assertSame(selected, op.child);
            assertFalse(op.onTop);
            assertNotSame(foreground, op.parent);
        }
        assertEquals(0, selected.display);
        assertSame(selected, tx.hiddenTarget); assertFalse(tx.hidden);
        assertSame(selected, tx.focusTarget); assertTrue(tx.focusable);
        assertEquals(0, Organizer.registrations);
        assertEquals(0, Organizer.creates);
        assertEquals(0, Organizer.deletions);
    }

    @Test public void creationIsUnregisteredFullscreenMainRootWithExactCookie() throws Exception {
        BackgroundTaskApi api = api();
        Cookie cookie = new Cookie() { };
        api.create(cookie);
        assertEquals(1, Organizer.creates);
        assertEquals(0, Organizer.display);
        assertEquals(1, Organizer.mode);
        assertSame(cookie, Organizer.cookie);
        assertFalse(Organizer.removeWithOrganizer);
        assertEquals(0, Organizer.registrations);
        assertTrue(Organizer.applied.isEmpty());
    }

    @Test public void preflightRecipeDoesNotApplyOrCreateAnything() throws Exception {
        BackgroundTaskApi api = api();
        Token selected = new Token("selected", 2);
        api.movement(selected, selected);
        assertTrue(Organizer.applied.isEmpty());
        assertEquals(0, Organizer.creates);
        assertEquals(0, Organizer.deletions);
        assertEquals(0, Organizer.registrations);
    }

    @Test public void unavailableRequiredApiFailsBeforeConstructorsAndSideEffects() {
        assertThrows(NoSuchMethodException.class, () -> new BackgroundTaskApi(
                Organizer.class, MissingReparentTx.class, Token.class, Cookie.class));
        assertEquals(0, Organizer.constructors);
        assertEquals(0, Organizer.creates);
        assertTrue(Organizer.applied.isEmpty());
    }
}
