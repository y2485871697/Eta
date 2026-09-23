package vd.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;

/**
 * Retains the newest composed frame of the owner's virtual display.
 *
 * <p>The {@link ImageReader} is the display's output surface and this listener is the only
 * consumer, so frames are copied into a private byte array and the {@link Image} is closed
 * immediately. With two reader buffers there is no reason to hold an image open, and the display
 * never stalls waiting for a release.
 *
 * <p>Conversion only happens on demand, so a caller that never asks for a snapshot pays only the
 * copy. If a frame is copied while the previous copy is being encoded, the newer copy wins.
 */
final class OwnerFrameStore implements ImageReader.OnImageAvailableListener {
    static final class Snapshot {
        final byte[] png;
        final int width;
        final int height;
        final long timestampNs;

        Snapshot(byte[] png, int width, int height, long timestampNs) {
            this.png = png;
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
        }
    }

    private final Object lock = new Object();
    private byte[] latest;
    private int frameWidth;
    private int frameHeight;
    private long frameTimestampNs;
    private int frameCount;

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                return;
            }
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0 || pixelStride != 4 || rowStride < width * pixelStride) {
                return; // unexpected layout; keep the previous good frame
            }
            byte[] frame = new byte[width * height * 4];
            int base = buffer.position();
            for (int row = 0; row < height; row++) {
                int rowStart = base + row * rowStride;
                int dst = row * width * 4;
                for (int col = 0; col < width; col++) {
                    int src = rowStart + col * pixelStride;
                    frame[dst] = buffer.get(src);
                    frame[dst + 1] = buffer.get(src + 1);
                    frame[dst + 2] = buffer.get(src + 2);
                    frame[dst + 3] = buffer.get(src + 3);
                    dst += 4;
                }
            }
            synchronized (lock) {
                latest = frame;
                frameWidth = width;
                frameHeight = height;
                frameTimestampNs = image.getTimestamp();
                frameCount++;
            }
        } catch (Throwable ignored) {
            // a dropped frame is not an owner failure; the previous frame stays available
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    boolean hasFrame() {
        synchronized (lock) {
            return latest != null;
        }
    }

    int frameCount() {
        synchronized (lock) {
            return frameCount;
        }
    }

    long latestTimestampNs() {
        synchronized (lock) {
            return frameTimestampNs;
        }
    }

    int frameWidth() {
        synchronized (lock) {
            return frameWidth;
        }
    }

    int frameHeight() {
        synchronized (lock) {
            return frameHeight;
        }
    }

    void clear() {
        synchronized (lock) {
            latest = null;
            frameWidth = 0;
            frameHeight = 0;
        }
    }

    /** PNG encode of the newest frame. Throws {@code NO_FRAME} or {@code IMAGE_TOO_LARGE}. */
    Snapshot encodePng(int maxBytes) throws OwnerException {
        byte[] frame;
        int width;
        int height;
        long timestampNs;
        synchronized (lock) {
            if (latest == null) {
                throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
            }
            frame = latest;
            width = frameWidth;
            height = frameHeight;
            timestampNs = frameTimestampNs;
        }
        byte[] png;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame));
                ByteArrayOutputStream out = new ByteArrayOutputStream(frame.length / 4);
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new OwnerException(OwnerProtocol.ERROR_SNAPSHOT_FAILED, "compress");
                }
                png = out.toByteArray();
            } finally {
                bitmap.recycle();
            }
        } catch (OwnerException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new OwnerException(OwnerProtocol.ERROR_SNAPSHOT_FAILED,
                    ex.getClass().getSimpleName());
        }
        if (png.length > maxBytes) {
            throw new OwnerException(OwnerProtocol.ERROR_IMAGE_TOO_LARGE, "png " + png.length);
        }
        return new Snapshot(png, width, height, timestampNs);
    }
}
