package vd.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class OwnerReleaseCompletionRegressionTest {
    @Test public void verifiedReleaseWaitsForBothEventsInEitherOrder() {
        for (boolean responseFirst : new boolean[]{false, true}) {
            OwnerReleaseCompletion c = new OwnerReleaseCompletion();
            assertFalse(responseFirst ? c.responseFinished() : c.dispatchFinished(true));
            assertTrue(responseFirst ? c.dispatchFinished(true) : c.responseFinished());
            assertFalse(c.responseFinished());
            assertFalse(c.dispatchFinished(true));
        }
    }

    @Test public void uncertainReleaseNeverStopsEvenAfterResponseFailure() {
        for (boolean responseFirst : new boolean[]{false, true}) {
            OwnerReleaseCompletion c = new OwnerReleaseCompletion();
            assertFalse(responseFirst ? c.responseFinished() : c.dispatchFinished(false));
            assertFalse(responseFirst ? c.dispatchFinished(false) : c.responseFinished());
            assertFalse(c.responseFinished());
            assertFalse(c.dispatchFinished(false));
        }
    }

    @Test public void concurrentCompletionClaimsStopExactlyOnce() throws Exception {
        OwnerReleaseCompletion c = new OwnerReleaseCompletion();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> {
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return c.dispatchFinished(true);
            });
            Future<Boolean> b = pool.submit(() -> {
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return c.responseFinished();
            });
            go.countDown();
            int claims = (a.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claims);
            assertFalse(c.responseFinished());
            assertFalse(c.dispatchFinished(true));
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }
}
