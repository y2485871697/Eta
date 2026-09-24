"""Source contract: balance amount and icon keep a neutral theme color."""
from pathlib import Path
import unittest


class BalanceIndicatorStyleTest(unittest.TestCase):
    def test_indicator_uses_neutral_theme_color_without_age_or_error_branch(self):
        app = Path(__file__).resolve().parents[3]
        source = (app / "src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ProviderBalanceComponents.kt").read_text()
        indicator = source.split("internal fun ProviderBalanceIndicator(", 1)[1]
        self.assertIn("val amountColor = MiuixTheme.colorScheme.onSurfaceVariantSummary", indicator)
        self.assertIn("tint = amountColor", indicator)
        self.assertIn("color = amountColor", indicator)
        for forbidden in ("StatusWarning", "state.error", "updatedAtMillis", "produceState", "delay("):
            self.assertNotIn(forbidden, indicator)
        self.assertIn("val amount = state.amount ?: return", indicator)


if __name__ == "__main__":
    unittest.main()
