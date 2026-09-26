package vd.runtime;

/**
 * Owns one latest image, not a stream of heap copies. Image replacement/metadata reads never
 * copy pixels. Only an uncached snapshot does, so a static first frame survives indefinitely
 * without polling, timers or a future producer frame. Callers must reserve two additional
 * ImageReader slots for acquireLatestImage while this policy owns one slot.
 */
final class OwnerFramePolicy {
    interface Frame {
        int width();
        int height();
        long timestampNs();
        byte[] copyRgba();
        void close();
    }

    interface Encoder {
        byte[] encode(byte[] rgba, int width, int height);
    }

    static final class Snapshot {
        final byte[] png;
        final int width;
        final int height;
        final long timestampNs;
        final int frameCount;

        Snapshot(byte[] png, int width, int height, long timestampNs, int frameCount) {
            this.png = png;
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.frameCount = frameCount;
        }
    }

    private final Object encodingLock = new Object();
    private Frame latest;
    private Snapshot cached;
    private int frameCount;
    private boolean closed;

    /** Takes ownership even after close, so late callbacks cannot leak or resurrect a frame. */
    synchronized void replace(Frame frame) {
        if (frame == null) return;
        if (closed) {
            closeQuietly(frame);
            return;
        }
        if (frame == latest) return;
        Frame previous = latest;
        latest = frame;
        cached = null;
        frameCount++;
        closeQuietly(previous);
    }

    synchronized boolean isClosed() { return closed; }
    synchronized boolean hasFrame() { return latest != null; }
    synchronized int frameCount() { return frameCount; }
    synchronized int width() { return latest == null ? 0 : latest.width(); }
    synchronized int height() { return latest == null ? 0 : latest.height(); }
    synchronized long timestampNs() { return latest == null ? 0 : latest.timestampNs(); }

    synchronized void close() {
        closed = true;
        Frame previous = latest;
        latest = null;
        cached = null;
        closeQuietly(previous);
    }

    Snapshot snapshot(Encoder encoder) {
        // Coalesce concurrent requests for the same image; do not serialize release behind PNG.
        synchronized (encodingLock) {
            Frame source;
            byte[] rgba;
            int width;
            int height;
            int count;
            long timestampNs;
            synchronized (this) {
                if (closed || latest == null) return null;
                if (cached != null) return cached;
                source = latest;
                width = source.width();
                height = source.height();
                timestampNs = source.timestampNs();
                count = frameCount;
                // Replacement cannot close the native image during the sole demanded copy.
                rgba = source.copyRgba();
            }
            Snapshot result = new Snapshot(encoder.encode(rgba, width, height), width, height,
                    timestampNs, count);
            synchronized (this) {
                if (closed) return null;
                // An old encoder must neither poison the new frame's cache nor its timestamp.
                // Identity, not timestamp equality, also handles duplicate producer timestamps.
                if (latest == source) cached = result;
                return result;
            }
        }
    }

    private static void closeQuietly(Frame frame) {
        if (frame == null) return;
        try { frame.close(); } catch (RuntimeException ignored) { /* Already closed. */ }
    }
}
