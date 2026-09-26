package io.github.mangi.eta.agent.overlay

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeSessionRegistry
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.EntrySurfaceGuard
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentVirtualRunOverlayServiceTest {
    @Test
    fun `virtual events do not claim record reveal or dismiss the entry surface`() = withService { service ->
        val session = AgentRuntimeSession("virtual", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        registry(service).put(session)
        var dismissals = 0
        val guard = entryGuard { dismissals++; true }
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)

        assertFalse(call(service, "claimOverlay", session, false) as Boolean)
        assertFalse(call(service, "claimOverlay", session, true) as Boolean)
        for (event in events()) call(service, "handleAcceptedRunEvent", session, event, guard)
        idle()

        assertEquals(0, dismissals)
        assertFalse(guard.wasTriggered)
        assertFalse(field(service, "hasExecutedForegroundTool") as Boolean)
        assertNull(field(service, "overlaySession"))
        assertEquals(AgentOverlayState.Initial, overlayState(service))
        assertNoWindows(service)
        assertTrue(windows.added.isEmpty())
        assertTrue(windows.removed.isEmpty())
    }

    @Test
    fun `background success failure and cancellation remain quiet after removal and preference changes`() = withService { service ->
        Prefs.initLocal(service)
        val previous = AgentTaskSurface.stored()
        try {
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.BACKGROUND.wire)
            val sessions = (0..2).map { AgentRuntimeSession("virtual-$it") }
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
            val windows = RecordingWindows()
            setField(service, "windowManager", windows.manager)
            // Deliberately seed stale foreground accounting: terminal gates must use the run.
            setField(service, "hasExecutedForegroundTool", true)
            for ((index, session) in sessions.withIndex()) {
                assertEquals(AgentTaskSurfaceMode.BACKGROUND, session.taskSurfaceMode)
                registry(service).put(session)
                if (index == 2) call(service, "cancelRun", session.runId)
                val result = AgentRuntimeWire.RunResult(
                    session.runId, ok = index != 1, content = "done",
                    error = if (index == 1) "failed" else null,
                )
                assertTrue(session.complete(result))
                val terminal = session.terminalResult!!
                if (index == 2) assertEquals("已停止", terminal.error)
                call(service, "postTerminalOverlay", session, terminal, null, null)
                idle()
                assertFalse(registry(service).contains(session))
                // A duplicate worker callback after removal must not surface a popup either.
                call(service, "postTerminalOverlay", session, terminal, null, null)
                idle()
                assertNoWindows(service)
                assertNull(field(service, "overlaySession"))
            }
            assertTrue(windows.added.isEmpty())
            assertTrue(windows.removed.isEmpty())
        } finally {
            Prefs.putString(AgentTaskSurface.PREF_KEY, previous.wire)
        }
    }

    @Test
    fun `late direct window paths reject background originating identity`() = withService { service ->
        val session = AgentRuntimeSession("virtual", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        val windows = RecordingWindows()
        // Simulate a stale owner after registry removal to exercise the final defenses.
        setField(service, "overlaySession", session)
        setField(service, "windowManager", windows.manager)
        setField(service, "hasExecutedForegroundTool", true)
        call(service, "showOverlay")
        call(service, "showBubble", windows.manager)
        call(service, "showResultCard", windows.manager, session)
        call(service, "enterFinalState", finishedState(), true, session)
        assertNoWindows(service)
        assertEquals(AgentOverlayState.Initial, overlayState(service))
        assertTrue(windows.added.isEmpty())
        assertTrue(windows.removed.isEmpty())
    }

    @Test
    fun `background terminal callbacks preserve another foreground runs windows and state`() = withService { service ->
        val foreground = AgentRuntimeSession("foreground", taskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND)
        registry(service).put(foreground)
        assertTrue(call(service, "claimOverlay", foreground, true) as Boolean)
        setField(service, "hasExecutedForegroundTool", true)
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)
        val orb = ComposeView(service)
        val bubble = ComposeView(service)
        setField(service, "orbView", orb)
        setField(service, "bubbleView", bubble)
        val originalState = overlayState(service)
        for (error in listOf(null, "failed", "已停止")) {
            val background = AgentRuntimeSession("virtual-$error", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
            registry(service).put(background)
            for (event in events()) call(service, "handleAcceptedRunEvent", background, event, null)
            val result = AgentRuntimeWire.RunResult(background.runId, error == null, "done", error)
            assertTrue(background.complete(result))
            call(service, "postTerminalOverlay", background, result, null, null)
            idle()
            assertSame(foreground, field(service, "overlaySession"))
            assertSame(orb, field(service, "orbView"))
            assertSame(bubble, field(service, "bubbleView"))
            assertSame(originalState, overlayState(service))
            assertTrue(field(service, "hasExecutedForegroundTool") as Boolean)
            assertTrue(registry(service).contains(foreground))
            assertNull(field(service, "resultCardView"))
        }
        // Also preserve a foreground result card after its session has been removed.
        val result = AgentRuntimeWire.RunResult(foreground.runId, true, "foreground result")
        assertTrue(foreground.complete(result))
        call(service, "postTerminalOverlay", foreground, result, null, null)
        idle()
        val card = field(service, "resultCardView")
        assertNotNull(card)
        val completedState = overlayState(service)
        val added = windows.added.size
        val removed = windows.removed.size
        val background = AgentRuntimeSession("virtual-last", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        registry(service).put(background)
        val backgroundResult = AgentRuntimeWire.RunResult(background.runId, false, "", "failed")
        assertTrue(background.complete(backgroundResult))
        call(service, "postTerminalOverlay", background, backgroundResult, null, null)
        idle()
        assertSame(card, field(service, "resultCardView"))
        assertSame(completedState, overlayState(service))
        assertEquals(added, windows.added.size)
        assertEquals(removed, windows.removed.size)
    }

    @Test
    fun `stale same ID completion cannot consume replacement or alter foreground windows`() = withService { service ->
        val old = AgentRuntimeSession("same", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        val replacement = AgentRuntimeSession("same", taskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND)
        registry(service).put(old)
        val oldResult = AgentRuntimeWire.RunResult(old.runId, false, "", "已停止")
        assertTrue(old.complete(oldResult))
        registry(service).put(replacement)
        assertTrue(call(service, "claimOverlay", replacement, true) as Boolean)
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)
        val orb = ComposeView(service)
        setField(service, "orbView", orb)
        setField(service, "hasExecutedForegroundTool", true)
        call(service, "recordSupplementEvent", replacement.runId, "keep", "request", "[]")
        val supplements = field(service, "supplementsByRunId") as Map<*, *>
        val replacementSupplements = supplements[replacement.runId]
        assertNotNull(replacementSupplements)

        call(service, "postTerminalOverlay", old, oldResult, null, null)
        idle()
        assertSame(replacement, registry(service).get("same"))
        assertSame(replacement, field(service, "overlaySession"))
        assertSame(replacementSupplements, supplements["same"])
        assertSame(orb, field(service, "orbView"))
        assertNull(field(service, "resultCardView"))
        assertTrue(windows.removed.isEmpty())
        assertTrue(windows.added.isEmpty())
    }

    @Test
    fun `foreground dismissal recording and completion survive preference change to background`() = withService { service ->
        Prefs.initLocal(service)
        val previous = AgentTaskSurface.stored()
        try {
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
            val session = AgentRuntimeSession("foreground")
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.BACKGROUND.wire)
            registry(service).put(session)
            assertTrue(call(service, "claimOverlay", session, true) as Boolean)
            val windows = RecordingWindows()
            setField(service, "windowManager", windows.manager)
            val orb = ComposeView(service)
            val bubble = ComposeView(service)
            setField(service, "orbView", orb)
            setField(service, "bubbleView", bubble)
            var dismissals = 0
            val guard = entryGuard { dismissals++; true }
            call(service, "handleAcceptedRunEvent", session, AgentEvent.ToolStarted(1, "call", "tap", "{}"), guard)
            idle()
            assertEquals(1, dismissals)
            assertTrue(guard.wasTriggered)
            assertTrue(field(service, "hasExecutedForegroundTool") as Boolean)
            assertSame(orb, field(service, "orbView"))
            val result = AgentRuntimeWire.RunResult(session.runId, true, "foreground result")
            assertTrue(session.complete(result))
            call(service, "postTerminalOverlay", session, result, guard, null)
            idle()
            assertFalse(registry(service).contains(session))
            assertEquals(AgentTaskSurfaceMode.FOREGROUND, session.taskSurfaceMode)
            assertEquals(AgentOverlayPhase.FINISHED, overlayState(service).phase)
            assertEquals("foreground result", overlayState(service).detailText)
            assertNull(field(service, "orbView"))
            assertNull(field(service, "bubbleView"))
            assertNotNull(field(service, "resultCardView"))
            assertEquals(2, windows.removed.size)
            assertEquals(1, windows.added.size)

            // Removed sessions cannot reopen a dismissed result card via a duplicate callback.
            call(service, "dismissAndStop")
            val addsBefore = windows.added.size
            call(service, "postTerminalOverlay", session, result, guard, null)
            idle()
            assertNoWindows(service)
            assertEquals(addsBefore, windows.added.size)
        } finally {
            Prefs.putString(AgentTaskSurface.PREF_KEY, previous.wire)
        }
    }

    @Test
    fun `unreadable stored mode falls back to a frozen ASK value`() = withService { service ->
        Prefs.initLocal(service)
        val prefs = Prefs.localAgentPreferences()!!
        val previous = prefs.all[AgentTaskSurface.PREF_KEY]
        try {
            prefs.edit().putInt(AgentTaskSurface.PREF_KEY, 1).commit()
            val session = AgentRuntimeSession("invalid-preference")
            assertEquals(AgentTaskSurfaceMode.ASK, session.taskSurfaceMode)
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
            assertEquals(AgentTaskSurfaceMode.ASK, session.taskSurfaceMode)
        } finally {
            val editor = prefs.edit()
            when (previous) {
                null -> editor.remove(AgentTaskSurface.PREF_KEY)
                is String -> editor.putString(AgentTaskSurface.PREF_KEY, previous)
                is Int -> editor.putInt(AgentTaskSurface.PREF_KEY, previous)
                else -> error("Unexpected surface preference type")
            }
            editor.commit()
        }
    }

    private fun events(): List<AgentEvent> = listOf(
        AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "launch_app"),
        AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap", contentChars = 2),
        AgentEvent.AssistantReceived(1, 0, "", listOf("launch_app", "observe_screen")),
        AgentEvent.ToolStarted(1, "launch", "launch_app", "{}"),
        AgentEvent.ToolStarted(1, "observe", "observe_screen", "{}"),
        AgentEvent.ToolStarted(1, "tap", "tap", "{}"),
        AgentEvent.ToolStarted(1, "alarm", "set_alarm", "{}"),
        AgentEvent.ToolFinished(1, "observe", "observe_screen", "ok", 1, 1024),
        AgentEvent.ToolImagesAttached(1, "observe_screen", 1, 1024),
    )

    private fun entryGuard(dismiss: () -> Boolean): EntrySurfaceGuard = EntrySurfaceGuard.from(
        AgentRuntimeWire.EntryHandoff(
            "entry", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "{}",
            dismissEntrySurfaceOnForegroundOperation = true,
        ),
        object : AgentLogger {
            override fun debug(message: () -> String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        },
        etaVoiceSurfaceDismissal = dismiss,
    )!!

    private fun finishedState() = AgentOverlayState(
        phase = AgentOverlayPhase.FINISHED,
        status = AgentOverlayStatus.ResultReady,
        detailText = "done",
    )

    private fun withService(block: (AgentRuntimeService) -> Unit) {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun registry(service: AgentRuntimeService) = field(service, "sessions") as AgentRuntimeSessionRegistry

    @Suppress("UNCHECKED_CAST")
    private fun overlayState(service: AgentRuntimeService): AgentOverlayState =
        (field(service, "state") as MutableState<AgentOverlayState>).value

    private fun assertNoWindows(service: AgentRuntimeService) {
        for (name in listOf("orbView", "bubbleView", "resultCardView")) assertNull(name, field(service, name))
    }

    private fun field(service: AgentRuntimeService, name: String): Any? =
        AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: AgentRuntimeService, name: String, value: Any?) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun call(service: AgentRuntimeService, name: String, vararg args: Any?): Any? =
        AgentRuntimeService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }.invoke(service, *args)

    private class RecordingWindows {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        val manager: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "addView" -> { added += args!![0] as View; null }
                "removeView", "removeViewImmediate" -> { removed += args!![0] as View; null }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingWindows"
                else -> null
            }
        } as WindowManager
    }
}
