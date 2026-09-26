package vd.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;

/**
 * Retains one newest native image. Idle callbacks only acquire/replace/close images; no full-frame
 * heap allocation or pixel copy occurs until an uncached PNG is requested. Three reader slots
 * allow acquireLatestImage to drain older buffers while one image is retained for static pages.
 * There is no idle timer and a snapshot never depends on the producer drawing another frame.
 */
final class OwnerFrameStore implements ImageReader.OnImageAvailableListener {
    static final class Snapshot {
        final byte[] png;
        final int width;
        final int height;
        final long timestampNs;
        final int frameCount;

        Snapshot(OwnerFramePolicy.Snapshot snapshot) {
            png = snapshot.png;
            width = snapshot.width;
            height = snapshot.height;
            timestampNs = snapshot.timestampNs;
            frameCount = snapshot.frameCount;
        }
    }

    private final Object acquisitionLock = new Object();
    private final OwnerFramePolicy frames = new OwnerFramePolicy();

    @Override
    public void onImageAvailable(ImageReader reader) {
        refresh(reader);
    }

    /** Also called immediately before a snapshot, even if its image callback is still queued. */
    void refresh(ImageReader reader) {
        synchronized (acquisitionLock) {
            if (frames.isClosed()) return;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Image.Plane[] planes = image.getPlanes();
                if (image.getFormat() != PixelFormat.RGBA_8888 || planes.length != 1) return;
                Image.Plane plane = planes[0];
                ByteBuffer buffer = plane.getBuffer();
                int width = image.getWidth();
                int height = image.getHeight();
                int rowStride = plane.getRowStride();
                int pixelStride = plane.getPixelStride();
                OwnerRgbaCopy.byteCount(buffer, width, height, pixelStride, rowStride);
                frames.replace(new PlaneFrame(image, buffer, width, height, rowStride, pixelStride));
                image = null; // Ownership transferred; replacing/clearing closes it exactly once.
            } catch (Throwable ignored) {
                // A malformed/dropped frame leaves the previous valid native image available.
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) { /* Already closed. */ }
                }
            }
        }
    }

    boolean hasFrame() { return frames.hasFrame(); }
    int frameCount() { return frames.frameCount(); }
    long latestTimestampNs() { return frames.timestampNs(); }
    int frameWidth() { return frames.width(); }
    int frameHeight() { return frames.height(); }

    /** Terminal: queued callbacks and encoders can never repopulate this store after release. */
    void clear() {
        synchronized (acquisitionLock) {
            frames.close();
        }
    }

    /** PNG bytes and metadata always belong to the same captured image, including cache hits. */
    Snapshot encodePng(int maxBytes) throws OwnerException {
        try {
            OwnerFramePolicy.Snapshot snapshot = frames.snapshot(PNG_ENCODER);
            if (snapshot == null) {
                throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
            }
            // A cache hit must still honor this request's byte budget.
            if (snapshot.png.length > maxBytes) {
                throw new OwnerException(OwnerProtocol.ERROR_IMAGE_TOO_LARGE,
                        "png " + snapshot.png.length);
            }
            return new Snapshot(snapshot);
        } catch (OwnerException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SNAPSHOT_FAILED,
                    ex.getClass().getSimpleName());
        }
    }

    private static final OwnerFramePolicy.Encoder PNG_ENCODER = new OwnerFramePolicy.Encoder() {
        @Override
        public byte[] encode(byte[] rgba, int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
                ByteArrayOutputStream out = new ByteArrayOutputStream(rgba.length / 4);
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("compress");
                }
                return out.toByteArray();
            } finally {
                bitmap.recycle();
            }
        }
    };

    private static final class PlaneFrame implements OwnerFramePolicy.Frame {
        private final Image image;
        private final ByteBuffer buffer;
        private final int width;
        private final int height;
        private final int rowStride;
        private final int pixelStride;
        private final long timestampNs;

        PlaneFrame(Image image, ByteBuffer buffer, int width, int height, int rowStride,
                int pixelStride) {
            this.image = image;
            this.buffer = buffer;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            this.timestampNs = image.getTimestamp();
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public long timestampNs() { return timestampNs; }
        @Override public byte[] copyRgba() {
            return OwnerRgbaCopy.copy(buffer, width, height, pixelStride, rowStride);
        }
        @Override public void close() { image.close(); }
    }
}
