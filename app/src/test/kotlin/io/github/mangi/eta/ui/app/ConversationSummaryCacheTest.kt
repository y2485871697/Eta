package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.TimeZone

class ConversationSummaryCacheTest {
    private val environment = ConversationSummaryEnvironment("en-US", 20_000L, TimeZone.getTimeZone("UTC"), true)
    private fun key(id: String) = ConversationSummaryKey(
        title = "title-$id", previewInput = conversationSummaryPreviewInput(AgentMessageUi("message-$id", "preview-$id")),
        createdAtMillis = 100L, updatedAtMillis = 200L, isPinned = false, isActiveRun = false,
        hasCompletionMarker = false, folderId = null, environment = environment,
    )

    // The cache is independent of Android resources. This deterministic projection includes every
    // output field; production keeps the existing preview/string/date projection inside build.
    private fun project(id: String, key: ConversationSummaryKey) = ConversationSummaryUi(
        id = id, title = key.title,
        preview = key.previewInput.text.take(48),
        timeLabel = "${key.createdAtMillis ?: key.updatedAtMillis}-${key.environment.localDay}",
        updatedAtMillis = key.updatedAtMillis ?: 0L,
        createdAtMillis = key.createdAtMillis ?: key.updatedAtMillis ?: 0L,
        mode = ConversationModeUi.Chat, isPinned = key.isPinned, isActiveRun = key.isActiveRun,
        hasCompletionMarker = key.hasCompletionMarker, folderId = key.folderId,
    )

    @Test fun changingOneConversationReusesEveryOtherReferenceAndMatchesUncachedProjection() {
        val cache = ConversationSummaryCache()
        val keys = (0 until 128).associate { "$it" to key("$it") }
        var builds = 0
        fun refresh(input: Map<String, ConversationSummaryKey>) = input.map { (id, key) ->
            cache.getOrBuild(id, key) { builds++; project(id, key) }
        }
        val first = refresh(keys)
        val changed = keys + ("17" to keys.getValue("17").copy(previewInput = conversationSummaryPreviewInput(AgentMessageUi("message-17", "changed"))))
        val second = refresh(changed)
        assertEquals(129, builds)
        assertEquals(changed.map { (id, key) -> project(id, key) }, second)
        first.indices.forEach { index ->
            if (index == 17) assertNotSame(first[index], second[index])
            else assertSame(first[index], second[index])
        }
    }

    @Test fun everySummaryInputAndTimeEnvironmentInvalidatesTheEntry() {
        val original = key("c")
        val changes = listOf(
            original.copy(title = "new title"), original.copy(previewInput = conversationSummaryPreviewInput(null)),
            original.copy(createdAtMillis = null), original.copy(updatedAtMillis = 201L),
            original.copy(isPinned = true), original.copy(isActiveRun = true),
            original.copy(hasCompletionMarker = true), original.copy(folderId = "folder"),
            original.copy(environment = environment.copy(configuration = "zh-CN")),
            original.copy(environment = environment.copy(localDay = 20_001L)),
            original.copy(environment = environment.copy(timeZone = TimeZone.getTimeZone("Asia/Shanghai"))),
            original.copy(environment = environment.copy(use24HourClock = false)),
        )
        changes.forEach { changed ->
            val cache = ConversationSummaryCache()
            val first = cache.getOrBuild("c", original) { project("c", original) }
            val second = cache.getOrBuild("c", changed) { project("c", changed) }
            assertNotSame(first, second)
            assertEquals(project("c", changed), second)
        }
    }

    @Test fun streamingSnapshotReuseRequiresTheSameDateEnvironment() {
        val cache = ConversationSummaryCache()
        val original = key("streaming").copy(isActiveRun = true)
        val first = cache.getOrBuild("streaming", original) { project("streaming", original) }
        assertSame(first, cache.current("streaming", environment.copy()))

        val changedEnvironments = listOf(
            environment.copy(localDay = environment.localDay + 1),
            environment.copy(timeZone = TimeZone.getTimeZone("Asia/Shanghai")),
            environment.copy(configuration = "zh-CN"),
            environment.copy(use24HourClock = false),
        )
        changedEnvironments.forEach { changed ->
            assertNull(cache.current("streaming", changed))
        }
        val nextDay = original.copy(environment = changedEnvironments.first())
        val refreshed = cache.getOrBuild("streaming", nextDay) { project("streaming", nextDay) }
        assertNotSame(first, refreshed)
        assertSame(refreshed, cache.current("streaming", nextDay.environment))
        assertNull(cache.current("missing", nextDay.environment))
    }

    @Test fun irrelevantMessageFieldsAndThinkingContentDoNotInvalidatePreview() {
        val assistant = AgentMessageUi("a", "same", isStreaming = true, renderMarkdown = false)
        assertEquals(conversationSummaryPreviewInput(assistant),
            conversationSummaryPreviewInput(assistant.copy(id = "different", isStreaming = false, renderMarkdown = true)))
        assertEquals(conversationSummaryPreviewInput(io.github.mangi.eta.ui.model.ThinkingMessageUi("t", "before", isStreaming = false)),
            conversationSummaryPreviewInput(io.github.mangi.eta.ui.model.ThinkingMessageUi("t", "after", isStreaming = false)))
        val notice = io.github.mangi.eta.ui.model.SystemNoticeMessageUi("n", io.github.mangi.eta.ui.model.SystemNoticeCode.Stopped, "details")
        assertEquals(conversationSummaryPreviewInput(notice), conversationSummaryPreviewInput(notice.copy(detail = "different")))
    }

    @Test fun equalCopiedInputsReuseButDeletionAndRecreatedIdDoNot() {
        val cache = ConversationSummaryCache()
        val original = key("c")
        val first = cache.getOrBuild("c", original) { project("c", original) }
        assertSame(first, cache.getOrBuild("c", original.copy()) { error("unchanged") })
        cache.retain(emptySet())
        val recreated = cache.getOrBuild("c", original) { project("c", original) }
        assertNotSame(first, recreated)
        assertEquals(first, recreated)
    }
}
