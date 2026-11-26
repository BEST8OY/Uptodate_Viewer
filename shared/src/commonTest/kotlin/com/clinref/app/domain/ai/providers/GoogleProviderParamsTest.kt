package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.ProviderSettings
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleProviderParamsTest {

    private val httpClientFactory: KoogHttpClient.Factory = mockk(relaxed = true)
    private val provider = GoogleProvider(httpClientFactory)

    @Test
    fun thinkingBudgetWithMediumLevelUsesStandardThinkingConfig() {
        val config = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = ProviderSettings.Google(
                includeThoughts = true,
                thinkingBudget = 4096,
                thinkingLevel = "medium"
            )
        )

        val params = provider.createParams(config) as GoogleParams
        assertNotNull("thinkingConfig must not be null when budget is provided", params.thinkingConfig)
        assertEquals(4096, params.thinkingConfig?.thinkingBudget)
        assertNull("thinkingLevel must be null when budget is set", params.thinkingConfig?.thinkingLevel)
        assertNull("additionalProperties must not have thinkingConfig when budget is set", params.additionalProperties?.get("thinkingConfig"))
    }

    @Test
    fun mediumThinkingLevelWithoutBudgetUsesAdditionalPropertiesWorkaround() {
        val config = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = ProviderSettings.Google(
                includeThoughts = true,
                thinkingBudget = null,
                thinkingLevel = "medium"
            )
        )

        val params = provider.createParams(config) as GoogleParams
        assertNull("thinkingConfig must be null when using MEDIUM additionalProperties workaround", params.thinkingConfig)
        val thinkingJson = params.additionalProperties?.get("thinkingConfig") as? JsonObject
        assertNotNull("additionalProperties must inject thinkingConfig JSON", thinkingJson)
        assertEquals("MEDIUM", (thinkingJson?.get("thinkingLevel") as? JsonPrimitive)?.content)
    }

    @Test
    fun highThinkingLevelWithoutBudgetUsesStandardGoogleThinkingLevel() {
        val config = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = ProviderSettings.Google(
                includeThoughts = true,
                thinkingBudget = null,
                thinkingLevel = "high"
            )
        )

        val params = provider.createParams(config) as GoogleParams
        assertNotNull("thinkingConfig must not be null for standard high level", params.thinkingConfig)
        assertEquals(GoogleThinkingLevel.HIGH, params.thinkingConfig?.thinkingLevel)
        assertNull("additionalProperties must not contain thinkingConfig", params.additionalProperties?.get("thinkingConfig"))
    }
}
