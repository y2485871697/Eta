"""Source contract: shared vision/title settings text must not inherit black."""
from pathlib import Path
import re
import unittest


class ModelFeatureSettingsColorsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app = Path(__file__).resolve().parents[3]
        cls.source = (
            app / "src/main/kotlin/io/github/mangi/eta/ui/ModelFeatureSettingsScreen.kt"
        ).read_text(encoding="utf-8")

    def text_call_for(self, resource):
        # Bound assertions to one Text call so a correctly colored sibling
        # cannot hide a regression in the description itself.
        for match in re.finditer(r"\bText\(", self.source):
            depth = 1
            for end in range(match.end(), len(self.source)):
                char = self.source[end]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                if depth == 0:
                    call = self.source[match.start():end + 1]
                    if f"R.string.{resource}" in call:
                        return call
                    break
        self.fail(f"No Text call found for {resource}")

    def test_vision_and_title_descriptions_use_the_page_foreground(self):
        for resource in ("vision_feature_description", "title_feature_description"):
            with self.subTest(resource=resource):
                self.assertRegex(
                    self.text_call_for(resource),
                    r"\bcolor\s*=\s*MaterialTheme\.colorScheme\.onBackground\b",
                )

    def test_vision_off_and_title_default_notes_use_the_card_foreground(self):
        for resource in ("vision_feature_off", "title_feature_default"):
            with self.subTest(resource=resource):
                self.assertRegex(
                    self.text_call_for(resource),
                    r"\bcolor\s*=\s*MaterialTheme\.colorScheme\.onSurface\b",
                )


if __name__ == "__main__":
    unittest.main()
