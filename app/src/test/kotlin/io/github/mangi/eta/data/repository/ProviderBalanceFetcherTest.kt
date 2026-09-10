package io.github.mangi.eta.data.repository

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
}
