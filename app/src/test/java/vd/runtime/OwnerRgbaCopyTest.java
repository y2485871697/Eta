package vd.runtime;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import org.junit.Test;

public class OwnerRgbaCopyTest {
    @Test public void packedCopyPreservesPositionLimitAndMark() {
        ByteBuffer input = sequence(24);
        input.position(3);
        input.limit(19);
        input.mark();
        assertArrayEquals(bytes(3, 19), OwnerRgbaCopy.copy(input, 2, 2, 4, 8));
        assertEquals(3, input.position());
        assertEquals(19, input.limit());
        input.reset();
        assertEquals(3, input.position());
    }

    @Test public void paddedRowsDoNotRequireLastRowsTrailingPadding() {
        ByteBuffer input = sequence(64);
        input.position(3);
        input.limit(23); // 3 byte prefix, 8 pixels bytes, 4 padding, 8 pixel bytes.
        byte[] expected = {3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19, 20, 21, 22};
        assertEquals(16, OwnerRgbaCopy.byteCount(input, 2, 2, 4, 12));
        assertArrayEquals(expected, OwnerRgbaCopy.copy(input, 2, 2, 4, 12));
        assertEquals(3, input.position());
    }

    @Test public void singleRowNeedsNoStridePadding() {
        assertArrayEquals(bytes(0, 4),
                OwnerRgbaCopy.copy(sequence(4), 1, 1, 4, Integer.MAX_VALUE));
    }

    @Test public void directReadOnlyBufferCanBeCopiedWithoutArrayAccess() {
        ByteBuffer input = ByteBuffer.allocateDirect(20);
        input.put(bytes(0, 20));
        input.position(4);
        input.limit(20);
        ByteBuffer readOnly = input.asReadOnlyBuffer();
        assertArrayEquals(bytes(4, 20), OwnerRgbaCopy.copy(readOnly, 2, 2, 4, 8));
        assertEquals(4, readOnly.position());
    }

    @Test public void slicedBufferHonorsItsOwnOffsetAndLimit() {
        ByteBuffer input = sequence(40);
        input.position(7);
        input.limit(27);
        ByteBuffer slice = input.slice();
        slice.position(4);
        assertArrayEquals(bytes(11, 27), OwnerRgbaCopy.copy(slice, 2, 2, 4, 8));
        assertEquals(4, slice.position());
        assertEquals(7, input.position());
    }

    @Test public void truncatedLastRowIsRejectedEvenWhenCapacityIsSufficient() {
        ByteBuffer input = sequence(64);
        input.position(3);
        input.limit(22);
        rejects(input, 2, 2, 4, 12);
        assertEquals(3, input.position());
        assertEquals(22, input.limit());
    }

    @Test public void rejectsInvalidGeometryAndPixelStride() {
        rejects(null, 1, 1, 4, 4);
        for (int dimension : new int[]{0, -1, Integer.MIN_VALUE}) {
            rejects(sequence(16), dimension, 1, 4, 4);
            rejects(sequence(16), 1, dimension, 4, 4);
        }
        for (int pixelStride : new int[]{-4, 0, 1, 3, 8, Integer.MAX_VALUE}) {
            rejects(sequence(16), 1, 1, pixelStride, 4);
        }
    }

    @Test public void rejectsNegativeAndOverlappingRowStrides() {
        for (int rowStride : new int[]{Integer.MIN_VALUE, -1, 0, 4, 7}) {
            rejects(sequence(32), 2, 2, 4, rowStride);
        }
    }

    @Test public void rejectsRowBytesAndTotalByteCountOverflowBeforeAllocation() {
        rejects(sequence(4), Integer.MAX_VALUE, Integer.MAX_VALUE, 4, Integer.MAX_VALUE);
        rejects(sequence(4), 536870912, 1, 4, Integer.MAX_VALUE);
        rejects(sequence(4), 536870911, 2, 4, 2147483644);
        rejects(sequence(4), 1, Integer.MAX_VALUE, 4, 4);
        rejects(sequence(4), 65536, 65536, 4, 262144);
    }

    @Test public void rejectsRowOffsetOverflowAndNonzeroBaseOverrun() {
        rejects(sequence(32), 1, 3, 4, Integer.MAX_VALUE);
        ByteBuffer input = sequence(16);
        input.position(1);
        rejects(input, 2, 2, 4, 8);
    }

    private static void rejects(ByteBuffer input, int width, int height, int pixelStride,
            int rowStride) {
        try {
            OwnerRgbaCopy.copy(input, width, height, pixelStride, rowStride);
            fail("invalid layout accepted");
        } catch (IllegalArgumentException expected) {
            // In particular, never allocate wrapped lengths or fail later with index errors.
        }
    }

    private static ByteBuffer sequence(int size) { return ByteBuffer.wrap(bytes(0, size)); }

    private static byte[] bytes(int first, int end) {
        byte[] bytes = new byte[end - first];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (first + i);
        return bytes;
    }
}
