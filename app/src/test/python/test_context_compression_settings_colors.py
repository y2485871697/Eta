"""Source contract: Material content in Miuix compression cards stays themed."""
from pathlib import Path
import re
import unittest


class ContextCompressionSettingsColorsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app = Path(__file__).resolve().parents[3]
        cls.source = (
            app / "src/main/kotlin/io/github/mangi/eta/ui/ContextCompressionSettingsScreen.kt"
        ).read_text(encoding="utf-8")

    def call_for(self, component, marker):
        # Inspect only the matching call: a correctly colored sibling must not
        # mask an uncolored title or an icon that inherits LocalContentColor.
        for match in re.finditer(rf"\b{component}\(", self.source):
            depth = 1
            for end in range(match.end(), len(self.source)):
                char = self.source[end]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                if depth == 0:
                    call = self.source[match.start():end + 1]
                    if marker in call:
                        return call
                    break
        self.fail(f"No {component} call found for {marker}")

    def test_card_titles_use_primary_foreground_and_keep_typography(self):
        for resource in ("ui_auto_compress_context_title", "ui_compress_model_title"):
            with self.subTest(resource=resource):
                call = self.call_for("Text", f"R.string.{resource}")
                self.assertRegex(
                    call,
                    r"\bcolor\s*=\s*MaterialTheme\.colorScheme\.onSurface\b",
                )
                self.assertRegex(
                    call,
                    r"\bstyle\s*=\s*MaterialTheme\.typography\.bodyLarge\b",
                )

    def test_model_chevron_uses_secondary_foreground(self):
        call = self.call_for("Icon", "Icons.Rounded.ChevronRight")
        self.assertRegex(
            call,
            r"\btint\s*=\s*MaterialTheme\.colorScheme\.onSurfaceVariant\b",
        )
        self.assertRegex(call, r"\bcontentDescription\s*=\s*null\b")

    def test_descriptions_keep_secondary_foreground(self):
        # The model summary uses this same call for selected and missing models.
        for resource in ("ui_auto_compress_context_summary", "model_not_selected"):
            with self.subTest(resource=resource):
                self.assertRegex(
                    self.call_for("Text", f"R.string.{resource}"),
                    r"\bcolor\s*=\s*MaterialTheme\.colorScheme\.onSurfaceVariant\b",
                )


if __name__ == "__main__":
    unittest.main()
