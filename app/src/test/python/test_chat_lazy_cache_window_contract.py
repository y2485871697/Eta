"""Source contract for the bounded chat LazyColumn cache experiment."""
from pathlib import Path
import re
import unittest
from test_agent_chat_viewport_contract import balanced_end, code_only


class ChatLazyCacheWindowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = code_only((Path(__file__).resolve().parents[3] /
            "src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt").read_text())
        found = re.search(r"\bfun\s+AgentChatBody\s*\(", cls.source)
        if found is None:
            raise AssertionError("AgentChatBody declaration missing")
        params = cls.source.index("(", found.start())
        body_start = cls.source.index("{", balanced_end(cls.source, params, "(", ")"))
        cls.body = cls.source[body_start + 1:balanced_end(cls.source, body_start, "{", "}")]

    def test_cache_window_is_bounded_to_one_viewport_each_side(self):
        self.assertRegex(self.source, r"CHAT_CACHE_AHEAD_VIEWPORTS\s*=\s*1f\b")
        self.assertRegex(self.source, r"CHAT_CACHE_BEHIND_VIEWPORTS\s*=\s*1f\b")
        self.assertEqual(self.body.count("LazyLayoutCacheWindow("), 1)
        self.assertRegex(self.body, r"aheadFraction\s*=\s*CHAT_CACHE_AHEAD_VIEWPORTS")
        self.assertRegex(self.body, r"behindFraction\s*=\s*CHAT_CACHE_BEHIND_VIEWPORTS")

    def test_the_actual_list_state_owns_the_cache_window(self):
        state = re.search(r"val\s+scrollState\s*=\s*rememberLazyListState\s*\(", self.body)
        self.assertIsNotNone(state)
        opening = self.body.index("(", state.start())
        call = self.body[opening + 1:balanced_end(self.body, opening, "(", ")")]
        self.assertRegex(call, r"cacheWindow\s*=\s*chatCacheWindow")
        self.assertRegex(call, r"initialFirstVisibleItemIndex\s*=\s*initialBottomItemIndex")
        self.assertLess(self.body.index("val chatCacheWindow"), state.start())

    def test_no_visual_or_row_animation_logic_is_changed_by_the_cache_owner(self):
        cache_setup = self.body[self.body.index("val chatCacheWindow"):self.body.index("val currentBrowserMessageId")]
        for forbidden in ("AnimatedVisibility", "animateItem", "graphicsLayer", "scrollBy", "ChatMessageItem"):
            self.assertNotIn(forbidden, cache_setup)


if __name__ == "__main__":
    unittest.main()
