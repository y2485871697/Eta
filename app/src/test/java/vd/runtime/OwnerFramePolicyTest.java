package vd.runtime;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OwnerFramePolicyTest {
    private static final OwnerFramePolicy.Encoder ENCODER =
            (rgba, width, height) -> new byte[]{rgba[0]};

    @Test public void noFrameDoesNotInvokeEncoder() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        assertNull(frames.snapshot((rgba, width, height) -> {
            fail("encoding without an image");
            return null;
        }));
        assertFalse(frames.hasFrame());
        assertEquals(0, frames.timestampNs());
    }

    @Test public void idleAnimationAndMetadataReadsNeverCopyFullFrames() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        for (int i = 1; i <= 7200; i++) {
            frames.replace(new FakeFrame(counts, i, (byte) i, 1216, 2640));
            assertTrue(frames.hasFrame());
            assertEquals(i, frames.frameCount());
            assertEquals(i, frames.timestampNs());
            assertEquals(1216, frames.width());
            assertEquals(2640, frames.height());
        }
        assertEquals(0, counts.copies);
        assertEquals(7199, counts.closes);
        frames.close();
        assertEquals(7200, counts.closes);
    }

    @Test public void staticFirstFrameRemainsAvailableAndIsCopiedOnlyOnce() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 123, (byte) 7));
        AtomicInteger encodes = new AtomicInteger();
        OwnerFramePolicy.Encoder encoder = (rgba, width, height) -> {
            encodes.incrementAndGet();
            return new byte[]{rgba[0]};
        };
        OwnerFramePolicy.Snapshot first = frames.snapshot(encoder);
        for (int i = 0; i < 1000; i++) {
            OwnerFramePolicy.Snapshot cached = frames.snapshot(encoder);
            assertSame(first, cached);
            // Never fabricate a newer capture/input timestamp on a cache hit.
            assertEquals(123, cached.timestampNs);
            assertEquals(1, cached.frameCount);
        }
        assertArrayEquals(new byte[]{7}, first.png);
        assertEquals(1, counts.copies);
        assertEquals(1, encodes.get());
        assertEquals(0, counts.closes);
        frames.close();
        assertEquals(1, counts.closes);
    }

    @Test public void nextDemandUsesNewestImageNotLastEncodedImage() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 10, (byte) 1));
        assertEquals(10, frames.snapshot(ENCODER).timestampNs);
        for (int i = 11; i <= 100; i++) {
            frames.replace(new FakeFrame(counts, i, (byte) i, 2, 1));
        }
        assertEquals(1, counts.copies);
        OwnerFramePolicy.Snapshot latest = frames.snapshot(ENCODER);
        assertEquals(100, latest.timestampNs);
        assertEquals(91, latest.frameCount);
        assertEquals(2, latest.width);
        assertEquals(1, latest.height);
        assertArrayEquals(new byte[]{100}, latest.png);
        assertEquals(2, counts.copies);
    }

    @Test public void duplicateTimestampsDoNotReuseAnUnrelatedPng() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 42, (byte) 1));
        OwnerFramePolicy.Snapshot old = frames.snapshot(ENCODER);
        frames.replace(new FakeFrame(counts, 42, (byte) 2));
        OwnerFramePolicy.Snapshot latest = frames.snapshot(ENCODER);
        assertNotSame(old, latest);
        assertArrayEquals(new byte[]{1}, old.png);
        assertArrayEquals(new byte[]{2}, latest.png);
        assertEquals(42, latest.timestampNs);
        assertEquals(2, latest.frameCount);
        assertEquals(2, counts.copies);
    }

    @Test public void oldEncoderCannotPoisonNewFramesCacheOrTimestamp() throws Exception {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 11, (byte) 1));
        BlockingEncoder encoder = new BlockingEncoder();
        FutureTask<OwnerFramePolicy.Snapshot> pending = start(() -> frames.snapshot(encoder));
        try {
            await(encoder.entered);
            frames.replace(new FakeFrame(counts, 22, (byte) 2, 2, 1));
            assertEquals(1, counts.closes);
            encoder.finish.countDown();
            OwnerFramePolicy.Snapshot old = pending.get(5, TimeUnit.SECONDS);
            assertEquals(11, old.timestampNs);
            assertEquals(1, old.width);
            assertArrayEquals(new byte[]{1}, old.png);
            OwnerFramePolicy.Snapshot latest = frames.snapshot(encoder);
            assertEquals(22, latest.timestampNs);
            assertEquals(2, latest.width);
            assertEquals(2, latest.frameCount);
            assertArrayEquals(new byte[]{2}, latest.png);
            assertSame(latest, frames.snapshot(encoder));
            assertEquals(2, counts.copies);
            assertEquals(2, encoder.calls.get());
        } finally {
            encoder.finish.countDown();
            frames.close();
        }
    }

    @Test public void concurrentRequestsCoalesceOneCopyAndOneEncode() throws Exception {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 11, (byte) 1));
        BlockingEncoder encoder = new BlockingEncoder();
        FutureTask<OwnerFramePolicy.Snapshot> first = start(() -> frames.snapshot(encoder));
        try {
            await(encoder.entered);
            FutureTask<OwnerFramePolicy.Snapshot> second = start(() -> frames.snapshot(encoder));
            encoder.finish.countDown();
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, counts.copies);
            assertEquals(1, encoder.calls.get());
        } finally {
            encoder.finish.countDown();
            frames.close();
        }
    }

    @Test public void releaseDuringEncodingRejectsResultAndLateFrames() throws Exception {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 11, (byte) 1));
        BlockingEncoder encoder = new BlockingEncoder();
        FutureTask<OwnerFramePolicy.Snapshot> pending = start(() -> frames.snapshot(encoder));
        try {
            await(encoder.entered);
            frames.close(); // Must not wait for the encoder lock.
            frames.replace(new FakeFrame(counts, 22, (byte) 2));
            assertTrue(frames.isClosed());
            assertFalse(frames.hasFrame());
            assertEquals(0, frames.timestampNs());
            assertEquals(0, frames.width());
            assertEquals(0, frames.height());
            encoder.finish.countDown();
            assertNull(pending.get(5, TimeUnit.SECONDS));
            assertNull(frames.snapshot(ENCODER));
            frames.close();
            assertEquals(2, counts.closes);
            assertEquals(1, counts.copies);
        } finally {
            encoder.finish.countDown();
        }
    }

    @Test public void closeDropsCachedPngAndCannotBeReopened() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 11, (byte) 1));
        assertNotNull(frames.snapshot(ENCODER));
        frames.close();
        frames.close();
        frames.replace(new FakeFrame(counts, 22, (byte) 2));
        assertNull(frames.snapshot(ENCODER));
        assertEquals(2, counts.closes);
        assertEquals(1, counts.copies);
    }

    @Test public void failedEncoderDoesNotPopulateCacheAndCanBeRetried() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        frames.replace(new FakeFrame(counts, 11, (byte) 3));
        try {
            frames.snapshot((rgba, width, height) -> { throw new IllegalStateException("encode"); });
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals("encode", expected.getMessage());
        }
        assertTrue(frames.hasFrame());
        OwnerFramePolicy.Snapshot recovered = frames.snapshot(ENCODER);
        assertArrayEquals(new byte[]{3}, recovered.png);
        assertSame(recovered, frames.snapshot(ENCODER));
        assertEquals(2, counts.copies);
    }

    @Test public void failedCopyLeavesSourceAvailableForAnotherDemand() {
        OwnerFramePolicy frames = new OwnerFramePolicy();
        Counters counts = new Counters();
        FakeFrame source = new FakeFrame(counts, 11, (byte) 3);
        source.failCopy = true;
        frames.replace(source);
        try {
            frames.snapshot(ENCODER);
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals("copy", expected.getMessage());
        }
        source.failCopy = false;
        assertEquals(11, frames.snapshot(ENCODER).timestampNs);
        assertEquals(0, counts.closes);
    }

    private static <T> FutureTask<T> start(Callable<T> callable) {
        FutureTask<T> task = new FutureTask<>(callable);
        Thread thread = new Thread(task, "frame-policy-test");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("latch timed out", latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static final class BlockingEncoder implements OwnerFramePolicy.Encoder {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);

        @Override public byte[] encode(byte[] rgba, int width, int height) {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(finish);
            }
            return new byte[]{rgba[0]};
        }
    }

    private static final class Counters {
        int copies;
        int closes;
    }

    private static final class FakeFrame implements OwnerFramePolicy.Frame {
        final Counters counts;
        final long timestamp;
        final byte pixel;
        final int width;
        final int height;
        boolean closed;
        boolean failCopy;

        FakeFrame(Counters counts, long timestamp, byte pixel) {
            this(counts, timestamp, pixel, 1, 1);
        }

        FakeFrame(Counters counts, long timestamp, byte pixel, int width, int height) {
            this.counts = counts;
            this.timestamp = timestamp;
            this.pixel = pixel;
            this.width = width;
            this.height = height;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public long timestampNs() { return timestamp; }
        @Override public byte[] copyRgba() {
            assertFalse("copy after source closed", closed);
            counts.copies++;
            if (failCopy) throw new IllegalStateException("copy");
            byte[] rgba = new byte[width * height * 4];
            Arrays.fill(rgba, pixel);
            return rgba;
        }
        @Override public void close() {
            assertFalse("source closed twice", closed);
            closed = true;
            counts.closes++;
        }
    }
}
