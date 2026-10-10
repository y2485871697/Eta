"""Source contracts only: no Android execution, correctness or latency claims."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def source(name):
    return (SRC / name).read_text(encoding="utf-8")


def code(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def between(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


class StreamRenderPersistenceDiagnosticsContract(unittest.TestCase):
    def test_transparent_helper_measures_and_draws_once_per_branch(self):
        helper = code(source("ui/components/StreamDiagnosticModifier.kt"))
        construction = between(helper, "internal fun Modifier.streamDiagnosticMeasure", "private data class StreamDiagnosticMeasureElement")
        self.assertIn("if (!StreamPerformanceDiagnostics.enabled) return this", construction)
        self.assertIn("return this.then(StreamDiagnosticMeasureElement(stage, attribution))", construction)
        self.assertLess(construction.index("return this"), construction.index("return this.then"))
        element = between(helper, "private data class StreamDiagnosticMeasureElement", "private class StreamDiagnosticMeasureNode")
        self.assertIn("(val stage: String, val attribution: StreamDiagnosticAttribution?) : ModifierNodeElement<StreamDiagnosticMeasureNode>()", element)
        self.assertIn("override fun create() = StreamDiagnosticMeasureNode(stage, attribution)", element)
        self.assertIn("override fun update(node: StreamDiagnosticMeasureNode) { node.stage = stage; node.attribution = attribution }", element)
        self.assertNotIn("override fun equals", element)  # Data-class equality is by the fixed stage label.
        self.assertNotIn("override fun hashCode", element)
        measure = between(helper, "private class StreamDiagnosticMeasureNode", "internal fun Modifier.streamDiagnosticDraw")
        self.assertIn("(var stage: String, var attribution: StreamDiagnosticAttribution?) : Modifier.Node(), LayoutModifierNode", measure)
        self.assertIn("override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult", measure)
        self.assertEqual(measure.count("measurable.measure(constraints)"), 1)
        self.assertEqual(measure.count("layout(child.width, child.height)"), 1)
        self.assertEqual(measure.count("child.placeRelative(0, 0)"), 1)
        self.assertLess(measure.index("measureDetail(stage)"), measure.index("measurable.measure(constraints)"))
        self.assertNotIn("System.nanoTime", construction + element + measure)
        # Default node invalidation and intrinsic dispatch run the same transparent measure policy.
        for forbidden in ("shouldAutoInvalidate", "invalidateMeasurement", "override fun minIntrinsic", "override fun maxIntrinsic"):
            self.assertNotIn(forbidden, construction + element + measure)
        draw = helper.split("internal fun Modifier.streamDiagnosticDraw", 1)[1]
        self.assertIn("if (!StreamPerformanceDiagnostics.enabled) return this", draw)
        self.assertLess(draw.index("return this"), draw.index("return this.then(StreamDiagnosticDrawElement(stage, attribution))"))
        self.assertEqual(draw.count("drawContent()"), 1)
        self.assertIn("measureDetail(stage) { drawContent() }", draw)
        for forbidden in ("semantics", "graphicsLayer", "mutableState", "post", "note(", "launch", "coroutineScope", "onAttach", "onDetach"):
            self.assertNotIn(forbidden, helper)
        original_helpers = helper.split("internal fun Modifier.streamDiagnosticPlacement", 1)[0]
        self.assertEqual(original_helpers.count("!StreamPerformanceDiagnostics.enabled"), 2)
        self.assertEqual(helper.count("!StreamPerformanceDiagnostics.enabled"), 3)

    def test_hidden_item_still_measures_once_and_never_places_hidden_child(self):
        hidden = code(source("ui/components/StreamingListItemLayout.kt"))
        self.assertEqual(hidden.count("measurable.measure(constraints)"), 1)
        self.assertEqual(hidden.count("placeable.placeRelative(0, 0)"), 1)
        self.assertIn("!visible && StreamPerformanceDiagnostics.enabled", hidden)
        measure_body = between(hidden, "val measureItem = {", "if (diagnoseHidden) {\n            StreamPerformanceDiagnostics.measureDetail")
        invisible = measure_body.split("} else {", 1)[1]
        self.assertNotIn("placeRelative", invisible)
        self.assertNotIn(".place(", invisible)
        self.assertIn("layout(placeable.width, constraints.minHeight) {}", invisible)
        for label in ("markdown.hidden.childHeight", "markdown.hidden.reportHeight"):
            self.assertIn('"' + label + '"', invisible)
        dispatch = hidden.split("if (diagnoseHidden) {\n            StreamPerformanceDiagnostics.measureDetail", 1)[1]
        self.assertIn('(\"markdown.hidden.measure\", block = measureItem)', dispatch)
        self.assertIn("} else {\n            measureItem()", dispatch)
        self.assertNotIn("System.nanoTime", hidden)
        self.assertNotIn("finally", hidden)

    def test_remember_builds_are_measured_without_new_builds(self):
        chat = source("ui/components/ChatMessageItem.kt")
        citation = between(chat, "val displayContent = remember(message.content)", "// Completion belongs")
        self.assertEqual(citation.count("NumericCitationMarkup.strip(message.content)"), 1)
        self.assertIn('measure("markdown.citation.strip"', citation)
        for start, end, stage, build in (
            ("private fun ChatRevealRawText", "private fun ChatRevealMarkdownText", "markdown.annotated.raw", "AnnotatedString("),
            ("private fun ChatRevealMarkdownText", "private fun ChatRevealAnnotatedText", "markdown.annotated.build", "buildAnnotatedString {"),
        ):
            body = between(chat, start, end)
            self.assertEqual(body.count(build), 1)
            self.assertLess(body.index("remember("), body.index('measure("' + stage + '"'))
        cell = between(chat, 'measure("markdown.annotated.cell"', "val revealState =")
        self.assertEqual(cell.count("buildAnnotatedString {"), 1)
        frozen = between(chat, "private fun FrozenMarkdownElement", "internal fun shouldFreezeStreamingMarkdownBlock")
        self.assertIn("Modifier.graphicsLayer(", frozen)
        self.assertIn("val retainTexture = freeze && retainedHeightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX", frozen)
        self.assertIn("CompositingStrategy.Offscreen", frozen)
        self.assertIn("CompositingStrategy.Auto", frozen)
        self.assertLess(frozen.index("compositingStrategy"), frozen.index(".onSizeChanged"))
        self.assertIn('streamDiagnosticMeasure(if (freeze) "markdown.stable.measure" else "markdown.tail.measure", diagnosticAttribution)', frozen)
        self.assertIn('measure("markdown.blockDraw")', frozen)
        self.assertIn('if (freeze) "markdown.stable.draw" else "markdown.tail.draw"', frozen)
        draw = frozen.split(".drawWithContent {", 1)[1]
        enabled, rest = draw.split("} else {", 1)
        self.assertEqual(enabled.count("drawContent()"), 1)
        self.assertEqual(rest.split("if (freeze)", 1)[0].count("drawContent()"), 1)

    def test_parse_context_is_captured_in_existing_target_and_restored_inside_worker(self):
        chat = source("ui/components/ChatMessageItem.kt")
        target = between(chat, "internal data class StreamingMarkdownTarget", "internal fun streamingMarkdownBatchSize")
        self.assertIn("captureAttribution()", target)
        parser = between(chat, "val parsed = withContext(Dispatchers.Default)", "val newerTarget =")
        self.assertIn("withAttribution(target.diagnosticAttribution)", parser)
        self.assertEqual(parser.count("parserSession.parse("), 1)
        for stage in ("markdown.queueWait", "markdown.parse"):
            self.assertIn('"' + stage + '"', parser)
        publish = between(chat, "val publishTarget = target", "if (target.isStreaming)")
        self.assertIn("withAttribution(publishTarget.diagnosticAttribution)", publish)
        self.assertIn('"markdown.publishBlock"', publish)
        self.assertNotIn("withContext", publish)
        self.assertNotIn("delay(", publish)
        self.assertNotIn("message.id.hashCode", chat)

    def test_reveal_measures_existing_paths_and_keeps_save_restore_and_draw_branches(self):
        reveal = source("ui/components/SmoothTextReveal.kt")
        for stage in ("reveal.layout.update", "reveal.record.update", "reveal.record.cacheHit",
                      "reveal.graphemes.append", "reveal.graphemes.rebuild", "reveal.graphemes.cacheHit",
                      "reveal.paths.append", "reveal.paths.rebuild", "reveal.paths.cacheHit",
                      "reveal.paths.nextGrapheme", "reveal.drawContent", "reveal.saveLayer"):
            self.assertIn('"' + stage + '"', reveal)
        update = between(reveal, "private fun updateRecord", "private fun completeRecord")
        self.assertLess(update.index("return"), update.index('measureDetail("reveal.record.update"'))
        self.assertEqual(update.count("updateGraphemeBoundaries("), 1)
        self.assertIn('measureDetail("reveal.record.update", text.length.toLong())', update)
        measure = between(reveal, "override fun MeasureScope.measure", "override fun ContentDrawScope.draw")
        self.assertIn('return StreamPerformanceDiagnostics.measureDetail("reveal.measure")', measure)
        self.assertEqual(measure.count("measurable.measure(constraints)"), 1)
        self.assertIn("layout(placeable.width, measuredHeight)", measure)
        self.assertEqual(measure.count("placeable.place(0, 0)"), 1)
        self.assertNotIn("return layout", measure)
        next_path = between(reveal, "private fun nextGraphemePath", "private fun clearPathCache")
        self.assertIn('measureDetail("reveal.paths.nextGrapheme")', next_path)
        self.assertEqual(next_path.count("return@measureDetail null"), 2)
        self.assertNotIn("?: return null", next_path)
        self.assertEqual(next_path.count("getPathForRange(start, end)"), 1)
        for synchronous_body in (update, measure, next_path):
            self.assertNotIn("System.nanoTime", synchronous_body)
            self.assertNotIn("withContext", synchronous_body)
            self.assertNotIn("delay(", synchronous_body)
        body = between(reveal, "private fun ContentDrawScope.drawInsideMeasuredHeight", "private fun ensurePaths")
        self.assertEqual(body.count("drawDiagnosticContent()"), 5)  # Four original calls plus declaration.
        self.assertEqual(body.count("drawContent()"), 1)  # The wrapper alone owns the actual call.
        self.assertEqual(body.count("drawContext.canvas.saveLayer("), 1)
        self.assertEqual(body.count("drawContext.canvas.restore()"), 1)
        self.assertLess(body.index("saveLayer("), body.index("finally"))
        self.assertNotIn(".note(", code(reveal))
        self.assertNotIn(".post", code(reveal))
        self.assertNotIn("Regex(", code(reveal))

    def test_settings_uses_root_modifier_and_successful_composition_counter_only(self):
        settings = source("ui/SettingsScreen.kt")
        side = between(settings, "// Counts only successfully", "val coroutineScope")
        self.assertIn("SideEffect", side)
        self.assertIn("StreamPerformanceDiagnostics.enabled", side)
        self.assertIn('record("settings.composition")', side)
        self.assertNotIn("mutableState", side)
        scaffold = between(settings, "MiuixScaffoldPage(", ") {")
        self.assertIn('streamDiagnosticMeasure("settings.root.measure")', scaffold)
        self.assertIn('streamDiagnosticDraw("settings.root.draw")', scaffold)
        self.assertNotIn("graphicsLayer", scaffold)
        self.assertNotIn("semantics", scaffold)

    def test_datastore_times_edit_entry_transform_and_commit_tail_without_suspend_in_measure(self):
        store = source("data/datastore/SettingsDataStore.kt")
        for suffix in ("editEntryWait", "transform", "commitTail"):
            self.assertIn('"settings.' + suffix + '"', store)
        # Atomic ledger edits reuse SettingsDataStore's SAME diagnosticEdit queue/commit timer.
        # No second file-store timing, no renamed stages, no suspend inside measure.
        ledger_store = source("data/datastore/PreferencesUsageLedger.kt")
        for suffix in ("editEntryWait", "transform", "commitTail"):
            self.assertIn('"usage.' + suffix + '"', store)
        self.assertIn('usageLedger = PreferencesUsageLedger(preferencesStore) { transform ->', store)
        self.assertEqual(store.count('diagnosticEdit("usage.editEntryWait", "usage.transform", "usage.commitTail", transform)'), 1)
        ledger_update = between(ledger_store, "suspend fun update(", "suspend fun replace(")
        self.assertIn("withContext(NonCancellable + Dispatchers.IO)", ledger_update)
        self.assertIn("edit { prefs ->", ledger_update)
        self.assertIn("replaceIn(prefs, transform(current))", ledger_update)
        self.assertNotIn("storage.write", ledger_store)
        self.assertNotIn("pureDisk", ledger_store)
        helper = between(store, "private suspend fun diagnosticEdit", "\n    }\n")
        self.assertIn("if (!StreamPerformanceDiagnostics.enabled)", helper)
        self.assertIn("dataStore.edit { prefs -> transform(prefs) }", helper)
        self.assertLess(helper.index("dataStore.edit { prefs ->\n                StreamPerformanceDiagnostics.withAttribution"), helper.index("measure(transformStage)"))
        self.assertIn("finally", helper)
        self.assertIn("var transformExited: Long? = null", helper)
        self.assertIn("val exited = transformExited", helper)
        self.assertIn("if (exited != null)", helper)
        self.assertIn("System.nanoTime() - exited", helper)
        self.assertNotIn("transformExited = 0L", helper)
        self.assertNotIn("transformExited != 0L", helper)
        self.assertNotIn("measure(entryStage)", helper)
        self.assertNotIn("pureDisk", store)

    def test_usage_existing_reads_update_and_serializer_are_wired_once(self):
        usage = source("data/repository/UsageStatsRepository.kt")
        for stage in ("usage.load.dao.perDay", "usage.load.dao.conversations", "usage.load.dao.messages",
                      "usage.load.dao.liveIds", "usage.load.decode", "usage.lockWait"):
            self.assertIn('"' + stage + '"', usage)
        # Exactly one apply site emits usage.ledger.update: the atomic Preferences transform.
        joined = usage + source("data/datastore/PreferencesUsageLedger.kt")
        self.assertEqual(joined.count('measure("usage.ledger.update"'), 1)
        self.assertIn('measure("usage.ledger.update") { applyModelUsageDelta(raw, delta) }', joined)
        for call in ("dao.conversationCountPerDay(startAt)", "dao.conversationCount()", "dao.totalMessageCount()", "dao.conversations()"):
            self.assertEqual(usage.count(call), 1)
        record = between(usage, "suspend fun recordModelUsage", "// Suspend DAO calls")
        self.assertIn("modelUsageLock.withLock", record)
        self.assertIn("withAttribution(attribution)", record)
        self.assertIn("SettingsDataStore.recordModelUsage(delta)", record)
        read = between(usage, "private suspend inline fun", "\n}\n")
        self.assertIn("block: suspend () -> T", read)
        self.assertIn("try", read)
        self.assertIn("finally", read)
        self.assertNotIn(".measure(", read)
        model_ledger = source("data/repository/ModelUsageLedger.kt")
        self.assertIn('"usage.ledger.encodeEvents"', model_ledger)
        self.assertIn('"usage.ledger.serialize"', model_ledger)
        self.assertEqual(model_ledger.count('measure("usage.ledger.serialize")'), 2)
        self.assertEqual(model_ledger.count('measure("usage.ledger.encodeEvents"'), 2)
        single = between(model_ledger, "fun serialize(): String {", "private class WorkingModel")
        self.assertIn('measure("usage.ledger.serialize") { root!!.toString() }', single)
        # The batch entry point reuses the same mutable ledger instead of a second serializer.
        batch = between(model_ledger, "internal fun applyModelUsageDeltas", "private fun decodeEvents")
        self.assertIn("MutableModelUsageLedger(raw.orEmpty()).also { ledger -> deltas.forEach { ledger.apply(it) } }.serialize()", batch)
        self.assertNotIn('measure("usage.ledger.serialize")', batch)
        # Event encoding stays inside the single mutable ledger and is measured exactly once there.
        materialize = between(model_ledger, "fun materialize()", "private fun JSONObject.isBatchSafeLedger")
        self.assertEqual(materialize.count('measure("usage.ledger.encodeEvents"'), 1)
        self.assertIn("encodeEvents(events)", materialize)
        trim = between(model_ledger, "if (working.events.size > MAX_MODEL_EVENTS)", "working.applied = true")
        self.assertIn("clear()", trim)
        self.assertIn('measure("usage.ledger.encodeEvents"', model_ledger)

    def test_checkpoint_keeps_same_monitor_schedule_and_single_write_encode(self):
        recorder = source("agent/runtime/AgentRunCheckpointRecorder.kt")
        self.assertIn("@Synchronized private fun acceptLocked", recorder)
        self.assertIn("acceptLocked(event, requested)", recorder)
        self.assertIn('"runtime.checkpoint.lockWait"', recorder)
        self.assertIn('"runtime.checkpoint.merge"', recorder)
        self.assertIn("requested: Long?", recorder)
        self.assertIn("if (requested != null)", recorder)
        self.assertIn("private var pendingObservedAtNs: Long? = null", recorder)
        self.assertIn("private var pendingDiagnosticGeneration: Long? = null", recorder)
        self.assertNotIn("requested != 0L", recorder)
        self.assertNotIn("pendingObservedAtNs = 0L", recorder)
        accept = between(recorder, "@Synchronized private fun acceptLocked", "/** 把最后")
        creation = accept.split('flushPendingDelta("runtime.checkpoint.flush.boundary")', 1)[1].split("val elapsed =", 1)[0]
        self.assertIn("pendingDelta = checkpointEvent", creation)
        self.assertIn("pendingDiagnosticGeneration = StreamPerformanceDiagnostics.currentSessionToken()", creation)
        self.assertIn("pendingObservedAtNs = pendingDiagnosticGeneration?.let { System.nanoTime() }", creation)
        self.assertIn("pendingObservedDeltas = if (pendingDiagnosticGeneration != null) 1L else 0L", creation)
        self.assertLess(creation.index("pendingDiagnosticGeneration = StreamPerformanceDiagnostics.currentSessionToken()"), creation.index("pendingDelta = checkpointEvent"))
        merge = accept.split("} else {", 1)[0]
        self.assertIn("pendingDiagnosticGeneration != StreamPerformanceDiagnostics.currentSessionToken()", merge)
        self.assertIn("clearPendingObservations()", merge)
        self.assertIn("else if (pendingDiagnosticGeneration != null)", merge)
        self.assertEqual(merge.count("pendingObservedDeltas++"), 1)
        self.assertNotIn("pendingDiagnosticGeneration = StreamPerformanceDiagnostics.currentSessionToken()", merge)
        for suffix in ("chars", "events", "residency"):
            self.assertIn('"runtime.checkpoint.buffer.' + suffix + '"', recorder)
        self.assertIn("private const val MAX_BUFFERED_DELTA_CHARS = 512", recorder)
        self.assertIn("private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L", recorder)
        self.assertEqual(recorder.count("sortIndex = nextSortIndex++"), 1)
        flush = between(recorder, "private fun flushPendingDelta", "private fun append")
        self.assertIn("diagnosticGeneration != null && observedAt != null", flush)
        self.assertIn("StreamPerformanceDiagnostics.currentSessionToken() == diagnosticGeneration", flush)
        self.assertEqual(flush.count("recordForSession(diagnosticGeneration,"), 3)
        self.assertNotIn("StreamPerformanceDiagnostics.record(", flush)
        self.assertNotIn("withAttribution", flush)
        self.assertIn('"runtime.checkpoint.buffer.chars", value = event.deltaChars.toLong()', flush)
        self.assertIn('"runtime.checkpoint.buffer.events", value = pendingObservedDeltas', flush)
        self.assertIn('"runtime.checkpoint.buffer.residency", System.nanoTime() - observedAt', flush)
        clear = between(recorder, "private fun clearPendingObservations", "private fun append")
        self.assertIn("pendingObservedAtNs = null", clear)
        self.assertIn("pendingDiagnosticGeneration = null", clear)
        self.assertIn("pendingObservedDeltas = 0L", clear)
        self.assertNotIn("pendingDelta =", clear)
        discard = between(recorder, "@Synchronized fun discard", "// These timings")
        discard_actions = [discard.index(s) for s in ("sealed = true", "pendingDelta = null", "clearPendingObservations()", "AgentRunCheckpointStore.remove(appContext, runId)")]
        self.assertEqual(discard_actions, sorted(discard_actions))
        self.assertNotIn("append(", discard)
        actions = [flush.index(s) for s in ("clearPendingObservations()", "pendingDelta = null", "append(event)", "lastFlushNanos = nanoTime()")]
        self.assertEqual(actions, sorted(actions))
        store = between(source("agent/runtime/AgentRunCheckpointStore.kt"), "fun append(", "/** 返回所有")
        self.assertEqual(store.count("AgentEventJsonCodec.encode(event)"), 1)
        self.assertEqual(store.count("dao.appendInFlightEvent("), 1)
        self.assertIn("runBlocking(Dispatchers.IO)", store)
        self.assertIn('"runtime.checkpoint.encode"', store)
        self.assertIn('"runtime.checkpoint.write"', store)
        self.assertIn("finally", store)
        self.assertNotIn(".measure(\"runtime.checkpoint.write", store)
        self.assertNotIn("delay(", code(store + recorder))
        recorder_append = between(recorder, "private fun append", "private fun AgentEvent")
        self.assertIn("checkpointWrites.launch", recorder_append)
        self.assertLess(recorder_append.index("checkpointWrites.launch"), recorder_append.index("AgentRunCheckpointStore.append"))
        self.assertNotIn("runBlocking", recorder_append)
        self.assertNotIn("awaitCheckpointWrites", recorder_append)
        self.assertIn("awaitCheckpointWrites()", between(recorder, "fun seal()", "fun discard()"))


if __name__ == "__main__":
    unittest.main()
