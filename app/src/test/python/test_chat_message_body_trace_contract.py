"""Source contract for the minimal chat-body execution trace.

A body marker proves the enclosing content function body actually ran: Compose
skipped content has no body execution at all, so markers separate "the parent
list item lambda ran" from "this card content really executed". Each marker is a
zero-length beginSection/endSection point pair, i.e. NOT a render/commit timing.

Cost and privacy boundaries pinned here: the platform gate is read per emission,
a disabled gate emits nothing, there is no snapshot state, no per-frame
collector, no @NonSkippable and no logcat output, and marker names carry string
literals plus the anonymous mount number only. That mount namespace is local: it
may only be correlated with the list-level chat.row r / chat.list i identities
by time.

The helper-file checks pass on their own. The ChatMessageItem.kt wiring checks
fail until the traceChatBodyRun call sites land there.
"""
from pathlib import Path
import re
import unittest
from test_agent_chat_viewport_contract import balanced_end, code_only


ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta/ui/components"
# Comments are stripped but string literals are kept, so marker names stay checkable.
CALL = re.compile(r'\btraceChatBodyRun\s*\(\s*(?:kind\s*=\s*)?"([a-z][a-z0-9_.]*)"')
SIDE_EFFECT_CALL = re.compile(r"SideEffect\s*\{\s*traceChatBodyRun\s*\(")
MOUNT = re.compile(r"remember\s*\{\s*nextChatBodyTraceMount\(\)\s*\}")


def strip_comments(source):
    return re.sub(r"//[^\n]*|/\*.*?\*/", lambda match: " " * len(match.group()), source, flags=re.DOTALL)


def function_span(source, name):
    found = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if found is None:
        raise AssertionError(f"Missing function {name}")
    opening = source.index("(", found.start())
    body_start = source.index("{", balanced_end(source, opening, "(", ")"))
    return body_start + 1, balanced_end(source, body_start, "{", "}")


def has_function(source, name):
    return re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source) is not None


class ChatBodyTraceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        helper_raw = (ROOT / "ChatBodyTrace.kt").read_text(encoding="utf-8")
        items_raw = (ROOT / "ChatMessageItem.kt").read_text(encoding="utf-8")
        # code_only blanks comments and strings; strip_comments blanks comments only.
        # Both keep offsets stable, so a span measured on one slices the other.
        cls.helper = code_only(helper_raw)
        cls.helper_text = strip_comments(helper_raw)
        cls.items = code_only(items_raw)
        cls.items_text = strip_comments(items_raw)

    def helper_body(self, name, text=False):
        start, end = function_span(self.helper, name)
        return (self.helper_text if text else self.helper)[start:end]

    def item_body(self, name, text=False):
        start, end = function_span(self.items, name)
        return (self.items_text if text else self.items)[start:end]

    def test_gate_is_read_at_emission_and_disabled_emits_nothing(self):
        emit = self.helper_body("emitChatBodyTrace")
        run = self.helper_body("traceChatBodyRun")
        self.assertLess(emit.index("if (!enabled) return"), emit.index("sink.section("))
        self.assertIn("Trace.isEnabled()", run)
        self.assertIn("emitChatBodyTrace(", run)
        self.assertEqual(len(re.findall(r"Trace\.isEnabled\(\)", self.helper)), 1)

    def test_marker_is_a_zero_length_synchronous_point_pair(self):
        self.assertLess(self.helper.index("Trace.beginSection("), self.helper.index("Trace.endSection()"))
        self.assertNotIn("Trace.beginAsyncSection", self.helper)
        self.assertNotIn("Trace.endAsyncSection", self.helper)

    def test_helper_stays_off_frames_state_and_logging(self):
        for forbidden in ("mutableStateOf", "snapshotFlow", "withFrameNanos", "collectAsState",
                          "LaunchedEffect", "Log.", "delay(", "ChatScrollAnonymousIds", "chat.row."):
            self.assertNotIn(forbidden, self.helper)

    def test_mount_numbers_are_anonymous_local_and_never_a_row_identity(self):
        self.assertRegex(self.helper, r"private\s+val\s+chatBodyTraceMounts\s*=\s*AtomicLong\s*\(")
        self.assertRegex(self.helper, r"internal\s+fun\s+nextChatBodyTraceMount\s*\(")
        self.assertIn("chatBodyTraceMounts.incrementAndGet()", self.helper)
        self.assertIn('"chat.body.$kind m=$mountId"', self.helper_text)

    def test_declared_kinds_cover_the_entry_points_and_parse_phases(self):
        for kind in ("thinking", "tool", "md", "md.doc",
                     "md.phase.loading", "md.phase.error", "md.phase.success"):
            self.assertIn(f'"{kind}"', self.helper_text)
        self.assertIn("CHAT_BODY_TRACE_KINDS", self.helper)

    def test_every_call_site_is_a_side_effect_point_marker(self):
        calls = CALL.findall(self.items_text)
        self.assertGreaterEqual(len(calls), 5)
        self.assertEqual(len(calls), len(SIDE_EFFECT_CALL.findall(self.items)))
        self.assertGreaterEqual(len(MOUNT.findall(self.items)), 1)
        for kind in calls:
            self.assertRegex(kind, r"^[a-z]+(\.[a-z]+)*$")

    def test_thinking_and_markdown_entries_mark_their_own_body(self):
        for name, kind in (("ThinkingRow", "thinking"), ("StableMarkdown", "md")):
            body = self.item_body(name)
            self.assertRegex(body, r"\btraceChatBodyRun\s*\(")
            self.assertRegex(body, MOUNT)
            self.assertRegex(self.item_body(name, text=True),
                             rf'traceChatBodyRun\s*\(\s*"{re.escape(kind)}"')
        self.assertRegex((ROOT.parent / 'markdown/DocumentStreamingMarkdown.kt').read_text(),
                         r'"md\.phase\.')

    def test_tool_and_document_entries_mark_their_own_body_when_defined_here(self):
        for name, kind in (("ToolActivityInline", "tool"), ("ChatMarkdownDocument", "md.doc")):
            if has_function(self.items, name):
                self.assertRegex(self.item_body(name),
                                 r"\btraceChatBodyRun\s*\(")
                self.assertRegex(self.item_body(name, text=True),
                                 rf'traceChatBodyRun\s*\(\s*"{re.escape(kind)}"')

    def test_skippability_is_preserved_and_no_row_identity_is_claimed(self):
        self.assertNotIn("@NonSkippable", self.items)
        self.assertNotIn("chat.row.", self.items)
        self.assertNotIn("chatScrollTraceIds", self.items)
        self.assertNotIn("@NonSkippable", self.helper)


if __name__ == "__main__":
    unittest.main()
