package vd.runtime;

/** Malformed request framing. The connection answers with {@code ok=false} and this code. */
public final class OwnerProtocolException extends Exception {
    public final String code;

    public OwnerProtocolException(String code, String message) {
        super(message == null ? code : message);
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code");
        }
        this.code = code;
    }
}
