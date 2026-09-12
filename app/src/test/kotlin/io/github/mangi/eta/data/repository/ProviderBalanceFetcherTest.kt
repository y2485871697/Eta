package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.BalanceOption
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
    fun applyNewApiPresetFillsSelfEndpointAndQuotaFormula() {
        val applied = BalanceOption.applyPreset(
            BalanceOption.PRESET_NEW_API,
            BalanceOption(enabled = true, userId = "114514"),
        )
        assertEquals(BalanceOption.PRESET_NEW_API, applied.preset)
        assertEquals(BalanceOption.NEW_API_PATH, applied.apiPath)
        assertEquals(BalanceOption.NEW_API_RESULT_PATH, applied.resultPath)
        assertEquals("114514", applied.userId)
    }
}
