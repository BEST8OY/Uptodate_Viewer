package com.clinref.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val _configuration = MutableStateFlow(loadConfiguration())
    val configuration: StateFlow<AiConfiguration> = _configuration

    fun saveApiKey(provider: AiProvider, key: String) {
        encryptedPrefs.edit().putString(KEY_PREFIX + provider.name, key).apply()
    }

    fun getApiKey(provider: AiProvider): String {
        return encryptedPrefs.getString(KEY_PREFIX + provider.name, "") ?: ""
    }

    fun saveConfiguration(config: AiConfiguration) {
        encryptedPrefs.edit().putString(KEY_CONFIGURATION, json.encodeToString(config)).apply()
        _configuration.value = config
    }

    fun loadConfiguration(): AiConfiguration {
        val raw = encryptedPrefs.getString(KEY_CONFIGURATION, null) ?: return AiConfiguration()
        return try {
            json.decodeFromString<AiConfiguration>(raw)
        } catch (_: Exception) {
            AiConfiguration()
        }
    }

    companion object {
        private const val PREFS_NAME = "clinref_secure_prefs"
        private const val KEY_PREFIX = "api_key_"
        private const val KEY_CONFIGURATION = "ai_configuration"
    }
}
