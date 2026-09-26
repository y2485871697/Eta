package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same JSON adapter and receipt mapper used by VirtualDisplaySession. */
class VirtualDisplayHandoffDiagnosticsTest {
    private val diagnostics = VirtualDisplayHandoffDiagnostics

    private fun owner(): JSONObject = JSONObject(
        """{
            "v":1, "op":"handoff", "ok":false, "error":"HANDOFF_UNCERTAIN",
            "message":"park:16:IllegalStateException;moved=[] removed=[]",
            "sideEffectsAttempted":true, "handoffPhase":"park:16",
            "handoffProgress":{
                "mutationAttemptedTaskIds":[16,17],
                "relocatedTaskIds":[16],
                "completedTaskIds":[]
            }
        }""",
    )

    private fun progress(
        attempted: List<Int> = listOf(1),
        relocated: List<Int> = listOf(1),
        completed: List<Int> = listOf(1),
    ): JSONObject = JSONObject()
        .put("mutationAttemptedTaskIds", JSONArray(attempted))
        .put("relocatedTaskIds", JSONArray(relocated))
        .put("completedTaskIds", JSONArray(completed))

    private fun receipt(ok: Boolean = false): JSONObject = JSONObject()
        .put("ok", ok).put("error", if (ok) "" else "HANDOFF_UNCERTAIN")
        .put("message", if (ok) "" else "Safe original failure")
        .put("handedOff", ok).put("released", ok)

    private fun arrayValues(value: JSONArray): List<Any> = List(value.length()) { value.get(it) }

    private fun assertUnknown(wire: JSONObject?) {
        val parsed = diagnostics.sanitizeFailure(wire)
        assertNull(parsed.progress)
        val result = diagnostics.annotate(receipt(), parsed, uncertain = true)
        assertFalse(result.has("handoffProgress"))
        assertEquals("unknown", result.getString("handoff_progress_evidence"))
        assertEquals("unknown", result.getString("task_location"))
        assertFalse(result.getBoolean("ok"))
        assertFalse(result.getBoolean("retryable"))
        assertTrue(result.getString("handoff_diagnostic").contains("Tasks MAY already have moved"))
    }

    @Test fun actualOwnerJsonMapsToNestedHistoricalAttemptAndFailureReceipt() {
        val wire = owner()
        val before = wire.toString()
        val parsed = diagnostics.sanitizeFailure(wire)
        assertEquals(true, parsed.sideEffectsAttempted)
        assertEquals("park:16", parsed.handoffPhase)
        assertEquals(listOf(16, 17), parsed.progress!!.mutationAttemptedTaskIds)
        assertEquals(listOf(16), parsed.progress.relocatedTaskIds)
        assertEquals(emptyList<Int>(), parsed.progress.completedTaskIds)
        val attempt = diagnostics.annotate(receipt(), parsed)
        val result = diagnostics.annotate(receipt().put("handoffAttempts", JSONArray().put(attempt)),
            parsed, uncertain = true)
        val history = result.getJSONObject("handoffProgress")
        assertEquals(listOf(16, 17), arrayValues(history.getJSONArray("mutationAttemptedTaskIds")))
        assertEquals(listOf(16), arrayValues(history.getJSONArray("relocatedTaskIds")))
        assertEquals(0, history.getJSONArray("completedTaskIds").length())
        assertEquals(history.toString(), attempt.getJSONObject("handoffProgress").toString())
        assertFalse(result.has("attemptedTaskIds"))
        assertFalse(result.has("mutationAttemptedTaskIds"))
        assertEquals("historical_checkpoints", result.getString("handoff_progress_evidence"))
        assertFalse(result.getBoolean("location_evidence_fresh"))
        assertTrue(result.getBoolean("mutation_uncertain"))
        assertFalse(result.getBoolean("automatic_retry_allowed"))
        assertEquals("Safe original failure", result.getString("message"))
        assertEquals(before, wire.toString())
        wire.getJSONObject("handoffProgress").getJSONArray("mutationAttemptedTaskIds").put(99)
        assertEquals(listOf(16, 17), parsed.progress.mutationAttemptedTaskIds)
        assertEquals(listOf(16, 17), arrayValues(history.getJSONArray("mutationAttemptedTaskIds")))
    }

    @Test fun legacyFlatMissingNullAndWrongShapeProgressStayUnknown() {
        val flat = JSONObject().put("attemptedTaskIds", JSONArray().put(1))
            .put("relocatedTaskIds", JSONArray().put(1)).put("completedTaskIds", JSONArray())
        val oldNested = progress().apply {
            put("attemptedTaskIds", remove("mutationAttemptedTaskIds"))
        }
        val cases = listOf<JSONObject?>(
            null, JSONObject(), flat, progress(),
            owner().put("handoffProgress", JSONObject.NULL),
            owner().put("handoffProgress", "{\"mutationAttemptedTaskIds\":[1]}"),
            owner().put("handoffProgress", JSONArray()),
            owner().put("handoffProgress", JSONObject()),
            owner().put("handoffProgress", oldNested),
            owner().put("handoffProgress", progress().apply { remove("completedTaskIds") }),
            owner().put("handoffProgress", progress().put("token", "secret")),
        )
        cases.forEach(::assertUnknown)
        // A malformed nested object must not fall back to plausible flat fields.
        assertUnknown(flat.put("handoffProgress", oldNested))
    }

    @Test fun everyArrayRequiresBoundedDistinctPositiveIntValuesWithoutCoercion() {
        val invalid = listOf(
            listOf(0), listOf(-1), listOf("1"), listOf(1L), listOf(1.0), listOf(true),
            listOf(JSONObject.NULL), listOf(JSONObject()), listOf(JSONArray().put(1)),
            listOf(Int.MAX_VALUE.toLong() + 1), listOf(1, 1),
            (1..(VirtualDisplayHandoffDiagnostics.MAX_TASK_IDS + 1)).toList(),
        )
        for (key in diagnostics.PROGRESS_KEYS) {
            for (values in invalid) {
                assertUnknown(owner().put("handoffProgress", progress().put(key, JSONArray(values))))
            }
            for (value in listOf(1, "[1]", JSONObject.NULL, JSONObject())) {
                assertUnknown(owner().put("handoffProgress", progress().put(key, value)))
            }
        }
        val maximum = (1..VirtualDisplayHandoffDiagnostics.MAX_TASK_IDS).toList()
        assertEquals(maximum, diagnostics.sanitizeFailure(owner().put("handoffProgress",
            progress(maximum, maximum, maximum))).progress!!.completedTaskIds)
        val large = listOf(Int.MAX_VALUE)
        assertEquals(large, diagnostics.sanitizeFailure(owner().put("handoffProgress",
            progress(large, large, large))).progress!!.mutationAttemptedTaskIds)
    }

    @Test fun contradictoryIdSubsetsRejectWholeProgressRatherThanSalvagingArrays() {
        val cases = listOf(
            progress(listOf(1), listOf(2), emptyList()),
            progress(listOf(1, 2), listOf(1), listOf(2)),
            progress(emptyList(), listOf(1), emptyList()),
            progress(listOf(1), emptyList(), listOf(1)),
            progress(listOf(1), listOf(1), listOf(2)),
        )
        cases.forEach { assertUnknown(owner().put("handoffProgress", it)) }
    }

    @Test fun emptyCheckpointsAndFalseSideEffectsCannotProveNoMovement() {
        val parsed = diagnostics.sanitizeFailure(owner().put("sideEffectsAttempted", false)
            .put("handoffProgress", progress(emptyList(), emptyList(), emptyList())))
        assertNotNull(parsed.progress)
        val result = diagnostics.annotate(receipt(), parsed, uncertain = true)
        val message = result.getString("handoff_diagnostic")
        assertTrue(message.contains("Tasks MAY already have moved"))
        assertTrue(message.contains("empty progress arrays do not prove no move"))
        assertTrue(message.contains("Relocation is not completion"))
        assertTrue(message.contains("Do not retry handoff/release, ask the user to reattempt"))
        assertEquals("unknown", result.getString("task_location"))
        assertFalse(result.getBoolean("retryable"))
        assertFalse(result.getBoolean("ok"))
        assertFalse(result.getBoolean("handedOff"))
        assertFalse(result.getBoolean("released"))
    }

    @Test fun invalidBooleansPhasesSecretsAndArbitraryOwnerErrorsAreNotReflected() {
        for (value in listOf("true", 1, JSONObject.NULL)) {
            assertNull(diagnostics.sanitizeFailure(owner().put("sideEffectsAttempted", value)).sideEffectsAttempted)
        }
        for (phase in listOf("secretAlphaNumericToken", "Intent_payload", "eta-vd://session/secret",
            "park:0", "move:2147483648", "move:-1", "anchor:secret", "focus\n", "x".repeat(81))) {
            assertNull(diagnostics.sanitizeFailure(owner().put("handoffPhase", phase)).handoffPhase)
        }
        assertNull(diagnostics.sanitizeFailure(owner().put("handoffPhase", true)).handoffPhase)
        val raw = owner().put("error", "SECRET_TOKEN_abc").put("message", "Intent eta-vd://session/secret")
            .put("handoffPhase", "SECRET_TOKEN_abc").put("token", "SECRET_TOKEN_abc")
            .put("released", true).put("retryable", true).put("ok", true)
        val result = diagnostics.annotate(receipt(), diagnostics.sanitizeFailure(raw), uncertain = true)
        assertFalse(result.toString().contains("SECRET_TOKEN_abc"))
        assertFalse(result.toString().contains("eta-vd://"))
        assertFalse(result.has("token"))
        assertFalse(result.has("handoffPhase"))
        assertEquals("Safe original failure", result.getString("message"))
        assertFalse(result.getBoolean("ok"))
        assertFalse(result.getBoolean("released"))
        assertEquals("HANDOFF_UNCERTAIN", diagnostics.safeHandoffError(raw.getString("error")))
        assertEquals("HANDOFF_UNCERTAIN", diagnostics.safeHandoffError(""))
        assertEquals("HANDOFF_PREFLIGHT_FAILED", diagnostics.safeHandoffError("HANDOFF_PREFLIGHT_FAILED"))
    }

    @Test fun annotationsNeverReplaceExistingSuccessFailureOrReleaseFlags() {
        val parsed = diagnostics.sanitizeFailure(owner())
        for (ok in listOf(false, true)) {
            val original = receipt(ok).put("handoffComplete", ok).put("sourceEmpty", ok)
                .put("mutationUncertain", !ok).put("releaseAttempted", true)
            val expected = JSONObject(original.toString())
            assertSame(original, diagnostics.annotate(original, parsed, uncertain = true))
            for (key in listOf("ok", "error", "message", "released", "handedOff",
                "handoffComplete", "sourceEmpty", "mutationUncertain", "releaseAttempted")) {
                assertEquals(key, expected.get(key), original.get(key))
            }
            if (ok) {
                assertFalse(original.has("mutation_uncertain"))
                assertFalse(original.has("retryable"))
            }
        }
        val existing = receipt().put("mutation_uncertain", false).put("retryable", false)
        diagnostics.annotate(existing, parsed, uncertain = true)
        assertFalse(existing.getBoolean("mutation_uncertain"))
    }

    @Test fun preservedReceiptIsDetachedHistoryWithOriginalFactualFailureUnchanged() {
        val prior = diagnostics.annotate(receipt(), diagnostics.sanitizeFailure(owner()), uncertain = true)
            .put("handoffAttempts", JSONArray().put(receipt()))
            .put("fresh_attempt", true).put("receipt_provenance", "original_attempt")
        val before = prior.toString()
        val preserved = diagnostics.preservedReceipt(prior)
        assertNotSame(prior, preserved)
        assertEquals(before, prior.toString())
        assertEquals("preserved_receipt", preserved.getString("receipt_provenance"))
        assertFalse(preserved.getBoolean("fresh_attempt"))
        assertFalse(preserved.getBoolean("location_evidence_fresh"))
        for (key in listOf("ok", "error", "message", "handedOff", "released", "mutation_uncertain",
            "retryable", "automatic_retry_allowed", "handoff_diagnostic")) {
            assertEquals(key, prior.get(key), preserved.get(key))
        }
        assertEquals(prior.getJSONObject("handoffProgress").toString(),
            preserved.getJSONObject("handoffProgress").toString())
        assertEquals(preserved.toString(), diagnostics.preservedReceipt(preserved).toString())
        preserved.getJSONArray("handoffAttempts").getJSONObject(0).put("error", "changed copy")
        assertEquals(before, prior.toString())
    }

    @Test fun cleanFocusRejectionKeepsExistingBoundedRetryAndPendingFailure() {
        val detail = "preflight:focus:Focus_BINDER_CHANGED;moved=[] removed=[]"
        val parsed = diagnostics.sanitizeFailure(owner().put("error", "HANDOFF_PREFLIGHT_FAILED")
            .put("sideEffectsAttempted", false).put("handoffPhase", "preflight:focus")
            .put("handoffProgress", progress(emptyList(), emptyList(), emptyList())))
        var attempts = 0
        val budget = VirtualDisplayHandoffRetry.Budget()
        val outcome = VirtualDisplayHandoffRetry.run(budget, {
            attempts++
            diagnostics.annotate(receipt().put("error", "HANDOFF_PREFLIGHT_FAILED").put("message", detail), parsed)
            VirtualDisplayHandoffRetry.Attempt.Refused("HANDOFF_PREFLIGHT_FAILED", detail, authenticated = true)
        }, { true }, {}) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(VirtualDisplayHandoffRetry.MAX_ATTEMPTS, attempts)
        assertEquals("handoff_pending", outcome.phase)
        assertTrue(outcome.cleanPreflight)
        assertFalse(budget.blocked)
        val result = diagnostics.annotate(receipt().put("error", outcome.failure!!.code)
            .put("message", outcome.failure.detail), parsed, uncertain = !outcome.cleanPreflight)
        assertEquals("HANDOFF_PREFLIGHT_FAILED", result.getString("error"))
        assertEquals(detail, result.getString("message"))
        assertFalse(result.has("mutation_uncertain"))
        assertFalse(result.has("automatic_retry_allowed"))
        assertFalse(result.has("retryable"))
        assertFalse(result.getBoolean("ok"))
        assertFalse(diagnostics.preservedReceipt(result).has("mutation_uncertain"))
    }

    @Test fun completedHistoricalCheckpointsCannotAuthorizeDeliverySuccess() {
        val wire = owner().put("handoffProgress", progress(listOf(16), listOf(16), listOf(16)))
            .put("handedOff", true).put("sourceEmpty", true)
        val parsed = diagnostics.sanitizeFailure(wire)
        val proven = VirtualDisplayHandoffEvidence.completed(
            authenticatedConnection = true, responseOk = false, selectedIds = setOf(16),
            retainedIds = setOf(16), keptIds = setOf(16), removedIds = emptySet(), field = wire::opt,
        )
        assertFalse(proven)
        val result = diagnostics.annotate(receipt(proven), parsed, uncertain = true)
        assertFalse(result.getBoolean("ok"))
        assertFalse(result.getBoolean("handedOff"))
        assertFalse(result.getBoolean("released"))
        assertFalse(result.getBoolean("retryable"))
    }
}
