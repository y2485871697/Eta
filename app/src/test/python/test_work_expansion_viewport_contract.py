"""Static wiring contracts only; Compose frame behaviour is tested separately in Kotlin."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / 'main/kotlin/io/github/mangi/eta/ui/components'
BODY = (COMPONENTS / 'AgentChatBody.kt').read_text()
RECOVERY = (COMPONENTS / 'BottomFollowViewportRecovery.kt').read_text()
POLICY = (COMPONENTS / 'BottomFollowViewportStep.kt').read_text()
ROWS = (COMPONENTS / 'AgentTimelineRows.kt').read_text()
APPEARANCE = (COMPONENTS / 'MessageAppearancePolicy.kt').read_text()


def function_body(source, name):
    match = re.search(r'\bfun\s+(?:\w+\.)?' + re.escape(name) + r'\(', source)
    if match is None:
        raise AssertionError('function declaration missing: ' + name)
    cursor = match.end()
    params = 1
    while params:
        if source[cursor] == '(':
            params += 1
        elif source[cursor] == ')':
            params -= 1
        cursor += 1
    brace = source.index('{', cursor)
    depth = 1
    cursor = brace + 1
    while depth:
        if source[cursor] == '{':
            depth += 1
        elif source[cursor] == '}':
            depth -= 1
        cursor += 1
    return source[brace + 1:cursor - 1]


class WorkExpansionViewportContractTest(unittest.TestCase):
    def test_explicit_capture_happens_before_projection_toggle(self):
        capture = BODY.index('viewportRecovery.beginWorkExpansion(')
        toggle = BODY.index('workExpansionOverrides = workExpansionOverrides +', capture)
        window = BODY[capture:toggle]
        self.assertIn('stepKeys = entry.group.messages.mapTo(HashSet<Any>())', window)
        self.assertIn('"work-step:${it.id}"', window)
        self.assertIn('canOwnWorkExpansionViewport()', window)
        self.assertIn('scrollState.isConversationAtBottom()', window)
        self.assertIn('entry.key, entry.group.messages.map { "work-step:${it.id}" }', window)

    def test_failed_capture_replaces_stale_owner_before_returning(self):
        capture = function_body(RECOVERY, 'beginWorkExpansion')
        self.assertLess(capture.index('workExpansion = null'), capture.index('return false'))
        self.assertIn('System.nanoTime() >= expiresAtNanos', capture)
        self.assertIn('it.key == groupKey', capture)
        self.assertIn('it.index > header.index', capture)
        self.assertIn('anchor.key, anchor.offset, expiresAtNanos', capture)
        self.assertNotIn('dispatchRawDelta', capture)

    def test_same_layout_callback_runs_both_guarded_paths(self):
        callback = BODY.split('.onGloballyPositioned {', 1)[1].split('.pointerInput(Unit)', 1)[0]
        self.assertIn('viewportRecovery.recover(', callback)
        self.assertIn('canRecoverExpansion = canOwnWorkExpansionViewport', callback)
        self.assertIn('shouldFollowBottom && canOwnWorkExpansionViewport()', callback)
        owners = BODY.split('val canOwnWorkExpansionViewport:', 1)[1].split('    Box(', 1)[0]
        for argument in ('keepBottomAnchored = currentAnchor.value',
                         'initialBottomPositionPending = initialBottomPositionPending',
                         'pointerDown = pointerDown[0]', 'isUserDragging = currentDragging.value',
                         'isUserScrolling = isUserScrolling',
                         'navigationActive = messageNavigationJob != null || currentScrollTarget != null'):
            self.assertIn(argument, owners)

    def test_recovery_is_bounded_reentrant_and_measured(self):
        recovery = function_body(RECOVERY, 'recover')
        self.assertIn('if (recovering) return 0f', recovery)
        self.assertIn('.coerceAtMost(36)', recovery)
        self.assertIn('repeat(attempts)', recovery)
        self.assertIn('val info = state.layoutInfo', recovery)
        self.assertIn('it.key == expansion.anchorKey', recovery)
        self.assertIn('it.key in expansion.precedingStepKeys', recovery)
        self.assertIn('it.offset + it.size', recovery)
        self.assertIn('!actual.isFinite() || actual <= 0f', recovery)
        self.assertIn('finally', recovery)
        self.assertIn('recovering = false', recovery)
        dispatch = recovery.index('state.dispatchRawDelta(step)')
        gate = recovery.rfind('state.isScrollInProgress', 0, dispatch)
        self.assertGreater(gate, recovery.index('val step ='))
        self.assertIn('canRecoverExpansion()', recovery[gate:dispatch])

    def test_no_tail_estimate_without_stable_key_evidence(self):
        distance = function_body(POLICY, 'resolveWorkExpansionViewportStep')
        self.assertIn('measuredAnchorOffsetPx ?: measuredPrecedingBottomPx ?: return 0f', distance)
        self.assertIn('observed.toLong() - anchorOffsetPx.toLong()', distance)
        self.assertIn('.coerceAtLeast(0L)', distance)
        tail = function_body(RECOVERY, 'measuredBottomFollowTailOverflow')
        self.assertIn('resolveTailBottomPx(', tail)
        self.assertIn('?: return null', tail)

    def test_static_attachment_transparency_fix_is_not_reverted(self):
        self.assertIn('(isStreaming || isBottomSettling) && keepBottomAnchored', BODY)
        self.assertIn('AnchorChatTailOnViewportChange(scrollState, bottomItemIndex)', BODY)
        bar = BODY.split('private fun AgentChatBottomBar(', 1)[1].split('internal fun shouldShowMorphLoadingIndicator', 1)[0]
        self.assertNotIn('.background(', bar)
        self.assertIn('containerColor = Color.Transparent', BODY)

    def test_prior_messages_do_not_replay_fade_and_new_messages_keep_it(self):
        self.assertIn('val appearedMessageKeys = remember { HashSet<String>() }', BODY)
        self.assertIn('remember(entry.key, workExpansionOverrides) { appearedMessageKeys.add(entry.key) }', BODY)
        self.assertIn('fadeInSpec = if (firstMessageAppearance) tween(durationMillis = 180) else null', BODY)
        self.assertIn('val fromBottom = animation?.fromBottom ?: false', BODY)
        self.assertIn('enter = tailDetailsEnter(fromBottom = fromBottom)', BODY)
        self.assertIn('exit = tailDetailsExit(toBottom = fromBottom)', BODY)

    def test_pre_existing_messages_are_marked_from_data_before_the_toggle(self):
        toggle = BODY.index('onToggle = {')
        mutate = BODY.index('workExpansionOverrides = workExpansionOverrides +', toggle)
        window = BODY[toggle:mutate]
        # Mark from data (projected rows + the raw message list) before any toggle side
        # effect, so an answer that was never lazily mounted cannot be mistaken for a new
        # one and replay the 180ms fade when the collapse first brings it into viewport.
        self.assertIn('markExistingMessages(appearedMessageKeys, timelineRows, visibleMessages)', window)
        self.assertLess(window.index('markExistingMessages('),
                        window.index('viewportRecovery.beginWorkExpansion('))
        # The latch is still per-key dedup, so a message created after the click fades.
        self.assertIn('remember(entry.key, workExpansionOverrides) { appearedMessageKeys.add(entry.key) }', BODY)
        # No clock window or batch latch that could pre-mark a brand-new streaming message.
        self.assertNotIn('workExpandStarts', BODY)
        self.assertNotIn('WORK_STEP_APPEAR_WINDOW_NANOS', BODY)
        self.assertNotIn('markPreExistingMessageKeys', BODY)

    def test_message_appearance_policy_reads_projected_rows_and_raw_messages(self):
        body = function_body(APPEARANCE, 'markExistingMessages')
        self.assertIn('if (row is AgentTimelineRow.Message) appeared.add(row.key)', body)
        self.assertIn('messages.forEach { appeared.add(it.id) }', body)
        self.assertNotIn('System.nanoTime', body)

    def test_real_draw_regression_exists_and_records_first_frames(self):
        fixture = ROOT / 'test/kotlin/io/github/mangi/eta/ui/components/AgentWorkExpansionViewportRegressionTest.kt'
        source = fixture.read_text()
        for evidence in ('checkExpansion(18, false)', 'checkExpansion(32, false)',
                         'checkExpansion(32, true)', 'enableRecovery = recoveryEnabled',
                         'probes[rev] == null', 'layer.record {', 'images[rev] = layer.toImageBitmap()',
                         'compose.mainClock.autoAdvance = false', 'compose.mainClock.advanceTimeByFrame()',
                         'markerPixels(checkNotNull(images[frame]))', 'canRecoverExpansion =',
                         'expiredSecondCaptureCannotReuseTheFirstGroupsAnchor',
                         'rejectedSecondCaptureCannotReuseTheFirstGroupsAnchor'):
            self.assertIn(evidence, source)
        # The step entrance/exit must use production's admission cohort and real shrink
        # exit, not the old test-only 500ms clock window or `ExitTransition.None`.
        self.assertIn('newWorkGroupAnimation(', source)
        self.assertIn('animation.entrance.claim(key)', source)
        self.assertIn('exit = tailDetailsExit(toBottom = fromBottom)', source)
        self.assertIn('LaunchedEffect(workAnimation.value?.generation)', source)
        self.assertNotIn('ExitTransition.None', source)
        self.assertNotIn('< 500L', source)
        self.assertNotIn('ExitTransition', source)
        self.assertNotIn('class BottomFollowViewportRecovery', source)
        self.assertIn('oldUnknownTailPathIsANegativeControlNotAnEventualIdleAssertion', source)
        self.assertIn('Modifier.fillMaxSize().graphicsLayer {', source)
        self.assertIn('info.measuredBottomFollowTailOverflow(SENTINEL)', source)
        self.assertIn('?.coerceAtMost(info.afterContentPadding)', source)
        self.assertIn('nextHeldTailLift(', source)
        self.assertIn('translationY = -heldTailLift[0].toFloat()', source)
        self.assertNotIn('bottomFollowLayer(', source)

    def test_rollback_keeps_inline_graphics_layer_and_no_placement_lift(self):
        self.assertIn('.then(if (shouldLiftTail) {\n                    Modifier.graphicsLayer {\n                        val overflow = scrollState.followTailOverflow()', BODY)
        self.assertNotIn('translationY = if (shouldLiftTail)', BODY)
        self.assertNotIn('bottomFollowLayer(', BODY + POLICY + RECOVERY)
        self.assertNotIn('placeWithLayer', POLICY)
        self.assertFalse((ROOT / 'test/kotlin/io/github/mangi/eta/ui/components/BottomFollowDrawPhaseTest.kt').exists())

    def test_expansion_does_not_mutate_other_group_keys(self):
        self.assertIn('workExpansionOverrides + (entry.key to !entry.expanded)', BODY)
        self.assertIn('expandedOverrides[entry.key] ?: (running || (isStreaming && entry.key == trailingWorkKey))', ROWS)
        self.assertIn('"work-step:${message.id}"', ROWS)
        self.assertIn('override val key: String get() = group.key', ROWS)


if __name__ == '__main__':
    unittest.main()
