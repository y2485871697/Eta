package io.github.mangi.eta.ui.components

import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderBrandingTest {
    @Test
    fun resolvesKnownModelFamiliesFromModelIds() {
        val cases = mapOf(
            "gpt-5.6-sol" to R.drawable.provider_logo_openai,
            "openai/o3" to R.drawable.provider_logo_openai,
            "vendor/codex-mini" to R.drawable.provider_logo_openai,
            "claude-sonnet-5" to R.drawable.model_logo_claude,
            "kimi-k2.6" to R.drawable.provider_logo_kimi,
            "kimi/kimi-k3" to R.drawable.provider_logo_kimi,
            "moonshot-v1-128k" to R.drawable.provider_logo_kimi,
            "qwen3.7-plus" to R.drawable.model_logo_qwen,
            "QWQ-32B" to R.drawable.model_logo_qwen,
            "deepseek/deepseek-v4-pro" to R.drawable.provider_logo_deepseek,
            "mimo-v2.5-pro" to R.drawable.provider_logo_mimo,
            "MiniMax-M3" to R.drawable.provider_logo_minimax,
            "step-3.7-flash" to R.drawable.provider_logo_stepfun,
            "glm-5.2" to R.drawable.model_logo_zai,
            "vendor/glm-4.7" to R.drawable.model_logo_zai,
            "chatglm3" to R.drawable.model_logo_chatglm,
            "glm-3-turbo" to R.drawable.model_logo_chatglm,
            "google/gemini-3.6-flash" to R.drawable.model_logo_gemini,
            "google/gemma-4" to R.drawable.model_logo_gemma,
            "x-ai/grok-5" to R.drawable.model_logo_grok,
            "meta-llama/llama-5" to R.drawable.model_logo_meta,
            "mistralai/mixtral-8x22b" to R.drawable.model_logo_mistral,
            "doubao-2.0-pro" to R.drawable.model_logo_doubao,
            "tencent/hunyuan-turbo" to R.drawable.model_logo_hunyuan,
            "01-ai/yi-large" to R.drawable.model_logo_yi,
        )

        cases.forEach { (modelId, expectedLogo) ->
            assertEquals(modelId, expectedLogo, modelBrandLogoRes(modelId))
        }
    }

    @Test
    fun avoidsShortNameAndOpaqueDeploymentFalsePositives() {
        listOf(
            "",
            "example/chat-model",
            "yield-model",
            "mimosa-v1",
            "step-by-step-model",
            "ep-20260817",
            "glimmer-2",
            "codexify-v1",
            "gptx-v1",
        ).forEach { modelId ->
            assertNull(modelId, modelBrandLogoRes(modelId))
        }
    }

    @Test
    fun prefersModelBrandAndFallsBackToProviderBrand() {
        assertEquals(
            R.drawable.provider_logo_kimi,
            modelOrProviderBrandLogoRes("kimi-k2.6", ProviderSourceTypes.BAILIAN),
        )
        assertEquals(
            R.drawable.model_logo_qwen,
            modelOrProviderBrandLogoRes("qwen3.7-plus", ProviderSourceTypes.OPENROUTER),
        )
        assertEquals(
            R.drawable.provider_logo_bailian,
            modelOrProviderBrandLogoRes("vendor/unknown-chat", ProviderSourceTypes.BAILIAN),
        )
        assertNull(modelOrProviderBrandLogoRes("vendor/unknown-chat", ProviderSourceTypes.CUSTOM))
    }
}
