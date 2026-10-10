from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'

class SettingsJankHotPathContract(unittest.TestCase):
    def test_live_run_does_not_resolve_every_body_owner_before_notice_check(self):
        source = (ROOT / 'model/AgentTerminalMessageOrder.kt').read_text()
        self.assertLess(source.index('if (runs.isEmpty()) return messages'), source.index('val messageOwners = messages.map'))
        self.assertIn('if (message !is SystemNoticeMessageUi || !message.code.isTerminal()) return@forEachIndexed', source)

    def test_usage_replaces_one_immutable_slot_and_preserves_holder_fallback(self):
        source = (ROOT / 'app/AgentAppState.kt').read_text().split('private fun updateAssistantUsage(', 1)[1].split('private fun revokeContextActual', 1)[0]
        self.assertIn('if (usage.isEmpty) return', source)
        self.assertIn('if (isStaleUsageAfterCompact(runId, round)) return', source)
        self.assertIn('if (targetIndex < 0)', source)
        self.assertIn('messages + AgentMessageUi(', source)
        self.assertIn('messages.incrementalSnapshot().replacing(targetIndex, target.copy(usage = usage))', source)
        self.assertNotIn('mapIndexed', source)

    def test_measurement_slots_are_opt_in_without_changing_effect_chain(self):
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        scaffold = (ROOT / 'components/MiuixScaffoldPage.kt').read_text().split('fun MiuixScaffold(', 1)[0]
        labels = (ROOT / 'components/BoundedStreamDiagnostics.kt').read_text()
        for name, stage in [('topBarModifier', 'settings.topbar.measure'), ('listModifier', 'settings.lazy.measure')]:
            self.assertIn(f'{name}: Modifier = Modifier', scaffold)
            self.assertIn(f'{name} = Modifier.streamDiagnosticMeasure("{stage}")', settings)
            self.assertIn(f'"{stage}"', labels)
        self.assertIn('.streamDiagnosticPlacement("settings.lazy.place").streamDiagnosticDraw("settings.lazy.draw")', settings)
        manage=(ROOT/'screens/chat/ManageChatsScreen.kt').read_text()
        drawer=(ROOT/'components/ConversationSidePaneScaffold.kt').read_text()
        for source,prefix in ((manage,'manage.lazy'),(drawer,'drawer.lazy')):
            compact=''.join(source.split())
            self.assertIn(f'.streamDiagnosticMeasure("{prefix}.measure").streamDiagnosticPlacement("{prefix}.place").streamDiagnosticDraw("{prefix}.draw")',compact)
            for suffix in ('measure','place','draw'): self.assertIn(f'"{prefix}.{suffix}"',labels)
        self.assertIn('modifier = topBarModifier', scaffold)
        self.assertIn('modifier = listModifier', scaffold)
        chain = ['.fillMaxSize()', '.horizontalCutoutPadding()', '.captureForTopBar(backdrop)', '.scrollEndHaptic()', '.overScrollVertical()', '.nestedScroll(scrollBehavior.nestedScrollConnection)']
        lazy = scaffold.split('LazyColumn(', 1)[1]
        self.assertEqual(sorted(lazy.index(item) for item in chain), [lazy.index(item) for item in chain])
        backdrop = (ROOT / 'components/TopBarBackdrop.kt').read_text()
        capture = backdrop.split('internal fun Modifier.captureForTopBar(', 1)[1].split('@Composable', 1)[0]
        self.assertNotIn('isScrollInProgress', capture)
        self.assertIn('layerBackdrop(backdrop)', capture)


    def test_settings_collects_remembered_store_flows(self):
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        self.assertIn('remember { SettingsDataStore.settingsFlow() }', settings)
        self.assertIn('remember { ProviderRepository.providersFlow() }', settings)
        self.assertIn('remember { RuntimeConfigRepository.selectedProviderIdFlow() }', settings)
        self.assertIn('remember { RuntimeConfigRepository.selectedModelIdFlow() }', settings)
        self.assertNotIn('val appSettings by SettingsDataStore.settingsFlow().collectAsState', settings)

    def test_inactive_chat_route_freezes_message_snapshot(self):
        helper = (ROOT / 'components/ChatUiActive.kt').read_text()
        self.assertIn('staticCompositionLocalOf { true }', helper)
        self.assertIn('val LocalChatUiActive', helper)
        self.assertIn('val LocalChatRouteCovered = compositionLocalOf { false }', helper)
        root = (ROOT / 'app/AgentAppRoot.kt').read_text()
        # 半遮住时聊天还在组合里，继续用实时消息；完全盖住后导航移出组合。
        self.assertIn('LocalChatUiActive provides true', root)
        self.assertIn('LocalChatRouteCovered provides (backStack.lastOrNull() != route)', root)
        self.assertNotIn('if (isCurrentRoute) {\n                    AgentHomeScreen', root)
        body = (ROOT / 'components/AgentChatBody.kt').read_text()
        start = body.index('internal fun AgentChatBody(')
        end = body.index('internal fun AgentChatScaffold(')
        host = body[start:end]
        self.assertIn('val chatUiActive = LocalChatUiActive.current', host)
        self.assertIn('val followLiveTranscript = chatUiActive && (!routeCovered || transitionActive)', host)
        self.assertIn('remember(followLiveTranscript)', host)
        self.assertIn('visibleMessagesCache.project(uiMessages', host)
        self.assertIn('isStreaming = uiStreaming', host)
        self.assertIn('isPaused = uiPaused', host)
        self.assertIn('LaunchedEffect(messages, isStreaming)', host)
        self.assertIn('enabled = !isPaused', host)
        self.assertIn('visibleReveal = followLiveTranscript', host)

    def test_navigation_does_not_write_root_state_from_a_graphics_layer(self):
        root = (ROOT / 'app/AgentAppRoot.kt').read_text()
        helper = (ROOT / 'components/ChatUiActive.kt').read_text()
        activity = (ROOT / 'app/ChatNavigationActivity.kt').read_text()
        self.assertNotIn('navigationAwareMiuixTransition', root)
        self.assertNotIn('LocalChatNavigationInProgress', helper)
        self.assertFalse((ROOT / 'app/ChatNavigationTransition.kt').exists())
        self.assertIn('LocalChatTransitionActive provides navigationActive.value', root)
        self.assertIn('transition = navigationTransition.transition', root)
        self.assertIn('chatNavigationActivityTransition', root)
        draw = activity.split('navGraphicsTransition(opaqueDepth = 1f)', 1)[1].split('applyDefaultCoveredChatTransform', 1)[0]
        self.assertIn('tracker.observeFrame(scope.gesture != null || scope.settle != null)', draw)
        self.assertNotIn('.value', draw)
        self.assertNotIn('onActivityChanged', draw)
        self.assertIn('postFrameCallback', activity)
        self.assertIn('navigationActivityIsCurrent', activity)
        self.assertIn('alpha = 1f - 0.1f * depth.coerceIn(0f, 1f)', activity)
        self.assertIn('width * 0.25f', activity)

    def test_covered_route_does_not_disable_reveal_during_swipe(self):
        item = (ROOT / 'components/ChatMessageItem.kt').read_text()
        self.assertNotIn('revealClockAllowed', item)
        self.assertNotIn('navigationInProgressNow', item)
        # Stopgap restores covered-hold behavior, not hidden-page optimization.
        self.assertIn('val settledCover = routeCovered && !transitionActive', item)
        self.assertIn('state.restoreState.animationsAllowed(isPaused) || (routeCovered && transitionActive)', item)
        self.assertIn('else if (settledCover) revealCoordinator.holdAnimations()', item)
        self.assertNotIn('animationsAllowed(isPaused) || routeCoveredNow.value', item)
        self.assertNotIn('catchUpThrough', item)
        self.assertNotIn('heldLength', item)
        self.assertNotIn('streamingCodeParts', item)
        # 横滑期间露出的聊天必须继续正常打字机，不能追平或停掉显现。
        # 停稳且完全盖住只停时钟，不走追平。
        body = (ROOT / 'components/AgentChatBody.kt').read_text()
        self.assertIn('!routeCovered || transitionActive', body)
        self.assertNotIn('pauseAnimationsAndCatchUp', body)
        self.assertNotIn('holdAnimations', body)

        self.assertIn('LaunchedEffect(revealCoordinator, animationsAllowed, isPaused, settledCover)', item)
        self.assertIn('if (routeCoveredNow.value) {', item)

    def test_selection_completion_does_not_swap_markdown_parent(self):
        container = (ROOT / 'haptics/HapticSelectionContainer.kt').read_text()
        item = (ROOT / 'components/ChatMessageItem.kt').read_text()
        self.assertNotIn('selectionEnabled', container)
        self.assertNotIn('selectionEnabled = !message.isStreaming', item)
        self.assertIn('SelectionContainer(', container)
