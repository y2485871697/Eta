package io.github.mangi.eta.ui.model

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CloudUsageReceiptCodecTest {
    @Test fun receiptRestoresOnlyForIdenticalConversationModelAndHistory() {
        val raw = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 152885)
        assertEquals(152885, CloudUsageReceiptCodec.decode(raw, "c", "p", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "other", "p", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "other", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "p", "other", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "p", "m", "edited"))
        assertNull(CloudUsageReceiptCodec.decode("broken", "c", "p", "m", "history"))
        assertEquals("", CloudUsageReceiptCodec.encode("c", "p", "m", "history", null))
    }

    @Test fun unknownCloudUsageShowsZeroAndLimitButDoesNotBecomeMeasuredUsage() {
        val usage = AgentContextUsageUi(null, 260000)
        assertEquals("0K / 260K tokens · 0.0%", formatContextUsage(usage, noUsageText = "暂无上下文用量", locale = Locale.US))
        assertNull(usage.contextTokens)
        assertFalse(isContextWindowExceeded(usage))
        assertEquals("0K / 260K tokens · 0.0%", formatContextUsage(AgentContextUsageUi(0, 260000), locale = Locale.US))
    }
}
