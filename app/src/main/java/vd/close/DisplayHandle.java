package vd.close;

/** Source-display identity. Non-null on a plan; not an Android display object. */
public final class DisplayHandle {
    public final String id;

    public DisplayHandle(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id");
        }
        this.id = id;
    }

    @Override
    public String toString() {
        return "DisplayHandle(" + id + ")";
    }
}
