package vd.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Pure proof for the one empty root we created, never a generic organizer-root cleanup policy. */
final class StagingRootGuard {
    static final class View {
        final int id, display, user, parent, activities, activityType, windowingMode;
        final Object token, binder;
        final List<?> cookies;
        final int[] childIds;
        final boolean identityFree;
        View(int id, int display, int user, int parent, int activities, int activityType,
                int windowingMode, Object token, Object binder, List<?> cookies, int[] childIds,
                boolean identityFree) {
            this.id = id;
            this.display = display;
            this.user = user;
            this.parent = parent;
            this.activities = activities;
            this.activityType = activityType;
            this.windowingMode = windowingMode;
            this.token = token;
            this.binder = binder;
            this.cookies = cookies == null ? null : new ArrayList<Object>(cookies);
            this.childIds = childIds == null ? null : childIds.clone();
            this.identityFree = identityFree;
        }
    }

    /** The production deleter uses this same read/check/delete sequence; all reads fail closed. */
    interface Access {
        View read(int id) throws Exception;
        List<?> children(Object token) throws Exception;
        void delete(Object token) throws Exception;
        boolean absent(int id, Object binder) throws Exception;
    }

    final int id;
    final Object token;
    final Object binder;
    private final Object cookie;

    private StagingRootGuard(View view, Object cookie) {
        this.id = view.id;
        this.token = view.token;
        this.binder = view.binder;
        this.cookie = cookie;
    }

    static StagingRootGuard identify(Map<Integer, Object> before, List<View> after, Object cookie) {
        if (before == null || after == null || cookie == null) throw rejected();
        View match = null;
        for (View view : after) {
            if (view == null || view.cookies == null) throw rejected();
            if (!view.cookies.contains(cookie)) continue;
            if (match != null) throw rejected();
            match = view;
        }
        if (match == null || before.containsKey(match.id) || before.containsValue(match.binder)) {
            throw rejected();
        }
        StagingRootGuard guard = new StagingRootGuard(match, cookie);
        guard.checkRoot(match);
        return guard;
    }

    private void checkRoot(View view) {
        if (view == null || id <= 0 || view.id != id || binder == null || token == null
                || view.token == null || !binder.equals(view.binder)
                || view.display != 0 || view.user != 0 || view.parent != -1
                || view.activities != 0 || view.activityType != 0 || view.windowingMode != 1
                || !view.identityFree || !Collections.singletonList(cookie).equals(view.cookies)
                || !OwnerHandoff.validRootChildMarkers(id, view.childIds)) {
            throw rejected();
        }
    }

    void check(Access access) throws Exception {
        checkRoot(access.read(id));
        List<?> children = access.children(token);
        if (children == null || !children.isEmpty()) throw rejected();
        // Re-read the identity after the child IPC. This is not an atomic admission fence.
        checkRoot(access.read(id));
    }

    void delete(Access access) throws Exception {
        check(access);
        // Only this saved token is eligible; never delete by inventory id or a replacement token.
        access.delete(token);
        if (!access.absent(id, binder)) throw rejected();
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException("staging identity or emptiness unproven");
    }
}
