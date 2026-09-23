package vd.close;

/**
 * Receipt from one port call. The controller keeps this object when the call
 * does not succeed so the caller can see the original failure.
 */
public final class ActionResult {
    public enum Kind {
        SUCCESS,
        FAILED,
        UNCERTAIN
    }

    public final Kind kind;
    public final String detail;

    private ActionResult(Kind kind, String detail) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public static ActionResult success() {
        return new ActionResult(Kind.SUCCESS, "");
    }

    public static ActionResult failed(String detail) {
        return new ActionResult(Kind.FAILED, detail);
    }

    public static ActionResult uncertain(String detail) {
        return new ActionResult(Kind.UNCERTAIN, detail);
    }

    @Override
    public String toString() {
        return kind + (detail.isEmpty() ? "" : ":" + detail);
    }
}
