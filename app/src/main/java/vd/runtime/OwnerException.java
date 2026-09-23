package vd.runtime;

/**
 * Failure raised by one owner operation.
 *
 * <p>{@link #code} is a stable, machine-readable token from {@link OwnerProtocol}.
 * The IPC layer turns a thrown exception into an {@code ok=false} response line.
 * Operations never return a successful response to hide a failure.
 */
public class OwnerException extends Exception {
    public final String code;

    public OwnerException(String code, String message) {
        super(message == null ? code : message);
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code");
        }
        this.code = code;
    }

    public OwnerException(String code) {
        this(code, code);
    }
}
