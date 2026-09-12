package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBalanceFetcherTest {

    private val deepSeekBody = """
        {
          "is_available": true,
          "balance_infos": [
            {
              "currency": "CNY",
              "total_balance": "128.50",
              "granted_balance": "0.00",
              "topped_up_balance": "128.50"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun extractValueReadsDeepSeekBracketPath() {
        val value = ProviderBalanceFetcher.extractValue(
            deepSeekBody,
            "balance_infos[0].total_balance",
        )
        assertEquals("128.50", value)
    }

    @Test
    fun extractValueReadsDottedArrayIndex() {
        val value = ProviderBalanceFetcher.extractValue(
            deepSeekBody,
            "balance_infos.0.total_balance",
        )
        assertEquals("128.50", value)
    }

    @Test
    fun extractValueEvaluatesSpacedSubtraction() {
        val body = """{"data":{"total_credits":10.5,"total_usage":2.25}}"""
        val value = ProviderBalanceFetcher.extractValue(
            body,
            "data.total_credits - data.total_usage",
        )
        assertEquals("8.25", value)
    }

    @Test
    fun tokenizeJsonPathSplitsBracketsAndDots() {
        val tokens = ProviderBalanceFetcher.tokenizeJsonPath("balance_infos[0].total_balance")
        assertEquals(
            listOf(
                JsonPathSegment.Field("balance_infos"),
                JsonPathSegment.Index(0),
                JsonPathSegment.Field("total_balance"),
            ),
            tokens,
        )
    }

    @Test
    fun extractValueFailsOnMissingArrayKey() {
        val error = runCatching {
            ProviderBalanceFetcher.extractValue(deepSeekBody, "balance_infos[0].missing")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("JSON path not found: missing"))
    }

    @Test
    fun resolveBalanceUrlStripsOpenAiSuffixForNewApi() {
        assertEquals(
            "https://api.example.com/api/user/self",
            ProviderBalanceFetcher.resolveBalanceUrl(
                "https://api.example.com/v1",
                "api/user/self",
                BalanceOption.PRESET_NEW_API,
            ),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/api/user/self",
            ProviderBalanceFetcher.resolveBalanceUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "/api/user/self",
            ),
        )
        assertEquals(
            "https://api.example.com/v1/user/balance",
            ProviderBalanceFetcher.resolveBalanceUrl(
                "https://api.example.com/v1",
                "user/balance",
            ),
        )
    }

    @Test
    fun extractValueConvertsNewApiQuotaUnits() {
        val body = """{"success":true,"data":{"quota":50000000,"used_quota":250000}}"""
        val value = ProviderBalanceFetcher.extractValue(
            body,
            BalanceOption.NEW_API_RESULT_PATH,
        )
        assertEquals("100", value)
    }

    @Test
    fun applyNewApiPresetKeepsPreviousCustomPathsForRestore() {
        val applied = BalanceOption.applyPreset(
            BalanceOption.PRESET_NEW_API,
            BalanceOption(
                enabled = true,
                userId = "114514",
                apiPath = "/usage",
                resultPath = "remaining",
            ),
        )
        assertEquals(BalanceOption.PRESET_NEW_API, applied.preset)
        assertEquals("/usage", applied.apiPath)
        assertEquals("remaining", applied.resultPath)
        assertEquals("114514", applied.userId)
        val resolved = applied.resolved()
        assertEquals(BalanceOption.NEW_API_PATH, resolved.apiPath)
        assertEquals(BalanceOption.NEW_API_RESULT_PATH, resolved.resultPath)
    }

    @Test
    fun applyCustomPresetClearsNewApiTemplatePaths() {
        val custom = BalanceOption.applyPreset(
            BalanceOption.PRESET_CUSTOM,
            BalanceOption(
                enabled = true,
                preset = BalanceOption.PRESET_NEW_API,
                apiPath = BalanceOption.NEW_API_PATH,
                resultPath = BalanceOption.NEW_API_RESULT_PATH,
                accessToken = "token",
            ),
        )
        assertEquals(BalanceOption.PRESET_CUSTOM, custom.preset)
        assertEquals("", custom.apiPath)
        assertEquals("", custom.resultPath)
        assertEquals("token", custom.accessToken)
    }

    @Test
    fun applyCustomPresetRestoresPreviousCustomPaths() {
        val custom = BalanceOption.applyPreset(
            BalanceOption.PRESET_CUSTOM,
            BalanceOption(
                enabled = true,
                preset = BalanceOption.PRESET_NEW_API,
                apiPath = "/usage",
                resultPath = "remaining",
            ),
        )
        assertEquals("/usage", custom.apiPath)
        assertEquals("remaining", custom.resultPath)
    }

    @Test
    fun resolvedFillsBlankNewApiPaths() {
        val resolved = BalanceOption(
            enabled = true,
            preset = BalanceOption.PRESET_NEW_API,
        ).resolved()
        assertEquals(BalanceOption.NEW_API_PATH, resolved.apiPath)
        assertEquals(BalanceOption.NEW_API_RESULT_PATH, resolved.resultPath)
    }

    @Test
    fun canQueryBalanceAcceptsNewApiWithoutStoredPaths() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "fish",
            name = "魚",
            baseUrl = "https://example.com/v1",
            balanceOption = BalanceOption(
                enabled = true,
                preset = BalanceOption.PRESET_NEW_API,
            ),
        )
        assertTrue(provider.canQueryBalance())
    }
}
