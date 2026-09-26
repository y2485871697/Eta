package vd.runtime;

import java.nio.ByteBuffer;

/** Checked, packed RGBA extraction. No allocation occurs until the complete layout is valid. */
final class OwnerRgbaCopy {
    private OwnerRgbaCopy() {}

    static int byteCount(ByteBuffer source, int width, int height, int pixelStride,
            int rowStride) {
        if (source == null || width <= 0 || height <= 0 || pixelStride != 4) {
            throw new IllegalArgumentException("RGBA geometry");
        }
        long rowBytes = (long) width * 4;
        if (rowBytes > Integer.MAX_VALUE || rowStride < rowBytes) {
            throw new IllegalArgumentException("RGBA row stride");
        }
        long byteCount = rowBytes * height;
        long end = (long) source.position() + (long) (height - 1) * rowStride + rowBytes;
        // The last row need not contain trailing padding; honor limit, not just capacity.
        if (byteCount > Integer.MAX_VALUE || end > source.limit() || end > source.capacity()) {
            throw new IllegalArgumentException("RGBA bounds");
        }
        return (int) byteCount;
    }

    static byte[] copy(ByteBuffer source, int width, int height, int pixelStride,
            int rowStride) {
        int size = byteCount(source, width, height, pixelStride, rowStride);
        int rowBytes = width * 4;
        byte[] pixels = new byte[size];
        ByteBuffer input = source.duplicate();
        int base = input.position();
        for (int row = 0; row < height; row++) {
            input.position((int) (base + (long) row * rowStride));
            input.get(pixels, row * rowBytes, rowBytes);
        }
        return pixels;
    }
}
