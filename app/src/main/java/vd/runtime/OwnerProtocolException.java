package vd.runtime;

/** Malformed request framing. The connection answers with {@code ok=false} and this code. */
public final class OwnerProtocolException extends OwnerException {

    public OwnerProtocolException(String code, String message) {
        super(code, message);
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code");
        }
    }
}
