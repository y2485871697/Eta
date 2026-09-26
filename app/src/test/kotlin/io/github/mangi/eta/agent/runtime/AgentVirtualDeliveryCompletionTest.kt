package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeArchiveRunEntity
import io.github.mangi.eta.data.db.RuntimeResultEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentVirtualDeliveryCompletionTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun runResultAndLegacyBundleDefaultToIncompleteDelivery() {
        val result = AgentRuntimeWire.RunResult(
            runId = "legacy",
            ok = true,
            content = "done",
        )
        assertFalse(result.virtualDeliveryCompleted)

        val legacyBundle = Bundle().apply {
            putString("run_id", result.runId)
            putBoolean("ok", result.ok)
            putString("content", result.content)
        }
        assertFalse(AgentRuntimeWire.runResultFromBundle(legacyBundle).virtualDeliveryCompleted)
    }

    @Test
    fun resultBundleRoundTripsBothCompletionStates() {
        listOf(false, true).forEach { completed ->
            val result = completedRun(completed).result
            val restored = AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result))

            assertEquals(result.runId, restored.runId)
            assertEquals(completed, restored.virtualDeliveryCompleted)
        }
    }

    @Test
    fun completedRunAndDrainBundlesRoundTripBothCompletionStates() {
        val runs = listOf(completedRun(false), completedRun(true))
        runs.forEach { run ->
            val restored = AgentRuntimeWire.completedRunFromBundle(AgentRuntimeWire.toBundle(run))
            assertEquals(run.result.runId, restored.result.runId)
            assertEquals(run.result.virtualDeliveryCompleted, restored.result.virtualDeliveryCompleted)
        }

        val drained = AgentRuntimeWire.completedRunsFromBundle(AgentRuntimeWire.completedRunsToBundle(runs))
        assertEquals(
            runs.associate { it.result.runId to it.result.virtualDeliveryCompleted },
            drained.associate { it.result.runId to it.result.virtualDeliveryCompleted },
        )
    }

    @Test
    fun resultStorePersistsBothCompletionStatesAcrossDatabaseReopen() {
        val runs = listOf(completedRun(false), completedRun(true))
        val expected = runs.associate { it.result.runId to it.result.virtualDeliveryCompleted }
        runs.forEach { assertTrue(AgentRuntimeResultStore.add(context, it)) }

        val stored = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context).runtimeRunDao().runtimeResults()
                .associate { it.runId to it.virtualDeliveryCompleted }
        }
        assertEquals(expected, stored)
        EtaDatabase.closeForTests()

        repeat(2) {
            val restored = AgentRuntimeResultStore.list(context)
                .associate { it.result.runId to it.result.virtualDeliveryCompleted }
            assertEquals(expected, restored)
        }
    }

    @Test
    fun archiveStorePersistsBothCompletionStatesAcrossDatabaseReopen() {
        val runs = listOf(completedRun(false), completedRun(true))
        val expected = runs.associate { it.result.runId to it.result.virtualDeliveryCompleted }
        runs.forEach { run ->
            AgentRunArchiveStore.add(
                context,
                AgentRunArchiveStore.ArchivedRun(
                    handoff = run.handoff,
                    events = emptyList(),
                    result = run.result,
                    createdAt = run.createdAt,
                ),
            )
        }

        val stored = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context).runtimeRunDao().archivedRuns()
                .associate { it.run.runId to it.run.virtualDeliveryCompleted }
        }
        assertEquals(expected, stored)
        EtaDatabase.closeForTests()

        repeat(2) {
            val restored = AgentRunArchiveStore.list(context)
                .associate { it.result.runId to it.result.virtualDeliveryCompleted }
            assertEquals(expected, restored)
        }
    }

    @Test
    fun entitiesWithoutCompletionFlagDefaultToFalseWhenLoadedByStores() {
        val resultId = "legacy-result-${UUID.randomUUID()}"
        val archiveId = "legacy-archive-${UUID.randomUUID()}"
        val createdAt = System.currentTimeMillis()
        val result = RuntimeResultEntity(
            runId = resultId,
            handoffId = resultId,
            handoffSource = "test",
            handoffPayload = "{}",
            dismissEntrySurface = false,
            ok = true,
            content = "legacy result",
            error = null,
            reasoningContent = "",
            transcriptJson = "[]",
            createdAt = createdAt,
        )
        val archive = RuntimeArchiveRunEntity(
            archiveRunId = archiveId,
            runId = archiveId,
            handoffId = archiveId,
            handoffSource = "test",
            handoffPayload = "{}",
            dismissEntrySurface = false,
            ok = true,
            content = "legacy archive",
            error = null,
            reasoningContent = "",
            transcriptJson = "[]",
            userImagePreviewsJson = "[]",
            createdAt = createdAt,
        )
        assertFalse(result.virtualDeliveryCompleted)
        assertFalse(archive.virtualDeliveryCompleted)
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context).runtimeRunDao()
            dao.upsertRuntimeResult(result)
            dao.replaceArchivedRun(archive, emptyList())
        }
        EtaDatabase.closeForTests()

        val restoredResult = AgentRuntimeResultStore.list(context).single().result
        val restoredArchive = AgentRunArchiveStore.list(context).single().result
        assertEquals(resultId, restoredResult.runId)
        assertEquals(archiveId, restoredArchive.runId)
        assertFalse(restoredResult.virtualDeliveryCompleted)
        assertFalse(restoredArchive.virtualDeliveryCompleted)
    }

    private fun completedRun(completed: Boolean): AgentRuntimeWire.CompletedRun {
        val runId = "virtual-delivery-${UUID.randomUUID()}"
        return AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "test",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "done",
                virtualDeliveryCompleted = completed,
            ),
            createdAt = System.currentTimeMillis(),
        )
    }
}
