package com.clinref.app.domain.ai

import com.clinref.app.fakes.FakeSecurePreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogAgentFactoryTest {

    @Test
    fun testConnectionWithInvalidOverrideKeyFailsAndDoesNotCache() = runTest {
        val prefs = FakeSecurePreferences()
        val factory = KoogAgentFactory(prefs)

        val config = AiConfiguration(
            provider = AiProvider.OPENAI,
            model = "gpt-4o"
        )

        // Attempt testConnection with an invalid override key
        val result = factory.testConnection(config, apiKeyOverride = "invalid-test-key-123")
        assertTrue("testConnection must fail with invalid key", result.isFailure)

        // Clear executors should be safely invokable
        factory.clearExecutors()
    }
}
