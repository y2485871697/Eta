package vd.runtime;

import java.util.UUID;

/**
 * Pure admission policy for one secondary-display launch. No Android types and no shell: every
 * decision here is a pure function that can be unit tested without a device.
 *
 * <p>Minimal-safe contrast candidate. The reference launch requested a standalone document task
 * ({@code NEW_TASK | NEW_DOCUMENT | MULTIPLE_TASK}); this candidate requests only {@code NEW_TASK}
 * and instead relies on the unique random base-intent data URI plus an exact post-launch identity
 * check to prove provenance. Dropping the forced {@code NEW_DOCUMENT} / {@code MULTIPLE_TASK} is the
 * whole point of the contrast, so a request that asks for them is refused, never silently
 * downgraded.
 *
 * <p>Nothing here adopts an existing task. There is no same-package, exact-component,
 * sole-new-task or "previous id + 1" heuristic: the only accepted identity is an exact marker match,
 * and anything unknown fails closed.
 */
final class LaunchAdmission {
    /** {@code Intent.FLAG_ACTIVITY_NEW_TASK} — the only task flag this candidate requests. */
    static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    /** {@code Intent.FLAG_ACTIVITY_MULTIPLE_TASK} — never requested by this candidate. */
    static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    /** {@code Intent.FLAG_ACTIVITY_NEW_DOCUMENT} — never requested by this candidate. */
    static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;

    /** Task-shaping flags the reference forced and this candidate must never emit. */
    static final int FORBIDDEN_TASK_FLAGS = FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK;

    /** The only task flag accepted from a caller. */
    static final int ALLOWED_FLAGS = FLAG_ACTIVITY_NEW_TASK;

    /** Exact task shape this candidate requests: {@code NEW_TASK}, nothing else. */
    static final int LAUNCH_FLAGS = FLAG_ACTIVITY_NEW_TASK;

    /** Launch identity marker scheme. The data URI is the sole provenance token. */
    static final String MARKER_PREFIX = "eta-vd://session/";

    private LaunchAdmission() {
    }

    /**
     * A fresh random launch marker. Two launches never share a marker, so an exact marker match can
     * only describe the task this launch created.
     */
    static String newMarker() {
        return MARKER_PREFIX + UUID.randomUUID().toString();
    }

    /**
     * True only for a non-empty {@code eta-vd://session/<something>} marker with no whitespace or
     * control characters (such a value would break the line-framed owner protocol anyway).
     */
    static boolean isMarker(String value) {
        if (value == null || value.length() <= MARKER_PREFIX.length()
                || !value.startsWith(MARKER_PREFIX)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * The task flags to request. A request naming {@code NEW_DOCUMENT} or {@code MULTIPLE_TASK} is
     * refused (never downgraded), and any other flag outside {@link #ALLOWED_FLAGS} is refused too,
     * so this candidate can never be talked back into the reference's standalone-document shape.
     * The result is always exactly {@link #LAUNCH_FLAGS}.
     */
    static int admittedFlags(int requestedFlags) {
        int forbidden = requestedFlags & FORBIDDEN_TASK_FLAGS;
        if (forbidden != 0) {
            throw new IllegalArgumentException("forbidden launch flags 0x"
                    + Integer.toHexString(forbidden));
        }
        int unsupported = requestedFlags & ~ALLOWED_FLAGS;
        if (unsupported != 0) {
            throw new IllegalArgumentException("unsupported launch flags 0x"
                    + Integer.toHexString(unsupported));
        }
        return LAUNCH_FLAGS;
    }

    /**
     * Provenance check for one freshly observed task on the owned display: its base intent must carry
     * exactly this launch's marker and belong to the requested target package. A {@code null} or
     * malformed marker, a {@code null}/blank observed data string, or a foreign package is never a
     * match. Same package with a foreign or missing marker is not adopted.
     */
    static boolean isProvenanceMatch(String marker, String targetPackage, String observedDataString,
            String observedPackage) {
        if (!isMarker(marker) || targetPackage == null || targetPackage.isEmpty()) {
            return false;
        }
        return marker.equals(observedDataString) && targetPackage.equals(observedPackage);
    }
}
