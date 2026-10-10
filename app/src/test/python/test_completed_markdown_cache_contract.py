"""Wiring contracts only; Compose lifecycle and geometry are tested separately."""
from pathlib import Path
import unittest
from test_agent_chat_viewport_contract import code_only, balanced_end


def function(source, name):
    start = source.index("fun " + name + "(")
    args = source.index("(", start)
    args_end = balanced_end(source, args, "(", ")")
    body = source.index("{", args_end)
    end = balanced_end(source, body, "{", "}")
    return source[args:args_end + 1], source[body:end + 1]


def block(source, marker):
    start = source.index("{", source.index(marker))
    return source[start:balanced_end(source, start, "{", "}") + 1]


class CompletedMarkdownCacheContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[3]
        folder = root / "src/main/kotlin/io/github/mangi/eta/ui/components"
        cls.chat = code_only((folder / "AgentChatBody.kt").read_text())
        cls.items = code_only((folder / "ChatMessageItem.kt").read_text())
        cls.cache = code_only((folder / "CompletedMarkdownCache.kt").read_text())
        cls.reentry = code_only((root / "src/test/kotlin/io/github/mangi/eta/ui/components/CompletedMarkdownReentryTest.kt").read_text())

    def test_cache_owner_is_outside_lazy_rows_and_keyed_to_list_state(self):
        _, body = function(self.chat, "AgentConversationMessages")
        self.assertEqual(body.count("CompletedMarkdownCache()"), 1)
        self.assertIn("remember(scrollState) { CompletedMarkdownCache() }", body)
        self.assertLess(body.index("val completedMarkdownCache"), body.index("LazyColumn("))

    def test_rows_receive_the_outer_cache(self):
        _, body = function(self.chat, "AgentConversationMessages")
        self.assertIn("LocalCompletedMarkdownCache provides completedMarkdownCache", body)
        self.assertGreater(body.index("LocalCompletedMarkdownCache provides"), body.index("LazyColumn("))

    def test_stable_markdown_uses_cached_state_at_entry(self):
        args, _ = function(self.items, "LegacyStableMarkdown")
        self.assertIn("markdownState: MarkdownState = rememberCompletedMarkdownState(content)", args)

    def test_success_records_the_matching_document_before_rendering(self):
        _, body = function(self.items, "LegacyStableMarkdown")
        success = block(body, "success =")
        self.assertIn("CacheCompletedMarkdownSuccess(content, markdownState, state)", success)
        self.assertLess(success.index("CacheCompletedMarkdownSuccess("), success.index("ChatMarkdownDocument("))
        for marker in ("loading =", "error ="):
            fallback = block(body, marker)
            self.assertNotIn("CacheCompletedMarkdownSuccess", fallback)
            self.assertIn("text = content", fallback)

    def test_thinking_completed_branch_uses_document_renderer_not_legacy_parser(self):
        _, body = function(self.items, "ThinkingRow")
        self.assertIn("StableMarkdown(", body)
        self.assertNotIn("rememberCompletedMarkdownState", body)
        _, wrapper = function(self.items, "StableMarkdown")
        self.assertIn("DocumentStaticMarkdown(", wrapper)
        self.assertNotIn("MarkdownState", wrapper)

    def test_renderer_and_regression_use_the_same_state_identity_boundary(self):
        _, body = function(self.items, "LegacyStableMarkdown")
        host = block(body, "CompletedMarkdownStateHost(markdownState)")
        self.assertIn("Markdown(", host)
        _, test_body = function(self.reentry, "ObservedDocument")
        self.assertIn("CompletedMarkdownStateHost(markdownState)", test_body)
        self.assertIn("MarkdownSuccess(success, components, modifier)", test_body)
        self.assertNotIn("collectAsState", self.reentry)
        self.assertIn("observations.first().state", self.reentry)

    def test_mounted_miss_is_remembered_and_content_changes_reset_its_state(self):
        _, body = function(self.cache, "rememberCompletedMarkdownState")
        self.assertIn("key(cache, content)", body)
        self.assertIn("remember { cache?.get(content) }", body)
        self.assertIn("rememberMarkdownState(content, retainState = true)", body)
        _, host = function(self.cache, "CompletedMarkdownStateHost")
        self.assertIn("key(markdownState)", host)

    def test_only_matching_success_is_admitted_to_the_cache(self):
        self.assertIn("success.content != content", self.cache)
        self.assertIn("!success.linksLookedUp", self.cache)
        _, publish = function(self.cache, "CacheCompletedMarkdownSuccess")
        self.assertIn("SideEffect", publish)
        self.assertIn("markdownState.state.value === success", publish)
        self.assertIn("cache.put(content, success, markdownState.links.value)", publish)

    def test_streaming_renderer_does_not_use_the_completed_cache(self):
        _, body = function(self.items, "StreamingMarkdown")
        self.assertNotIn("rememberCompletedMarkdownState", body)
        self.assertNotIn("CacheCompletedMarkdownSuccess", body)
        _, agent = function(self.items, "AgentMessageBlock")
        streaming = block(agent, "streamingState != null ->")
        self.assertIn("StreamingMarkdown(", streaming)
        self.assertNotIn("StableMarkdown(", streaming)


if __name__ == "__main__":
    unittest.main()
