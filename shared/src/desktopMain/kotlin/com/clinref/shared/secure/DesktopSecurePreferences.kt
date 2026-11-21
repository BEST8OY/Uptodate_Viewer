package com.clinref.shared.secure

import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop (JVM) implementation of [SecurePreferences] using AES-256-GCM encrypted
 * local storage in the user's home directory (`~/.clinref/clinref_secure.json`).
 */
class DesktopSecurePreferences(
    baseDir: File = File(System.getProperty("user.home"), ".clinref")
) : SecurePreferences {

    private val json = Json { ignoreUnknownKeys = true }
    private val configFile = File(baseDir, "clinref_secure.json")
    private val keyFile = File(baseDir, ".master.key")

    private val secretKey: SecretKey by lazy { getOrCreateMasterKey() }
    private val inMemoryStore = mutableMapOf<String, String>()

    private val _configuration = MutableStateFlow(loadConfiguration())
    override val configuration: StateFlow<AiConfiguration> = _configuration.asStateFlow()

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        loadFromDisk()
    }

    override fun saveApiKey(provider: AiProvider, key: String) {
        synchronized(inMemoryStore) {
            inMemoryStore[KEY_PREFIX + provider.name] = key
            saveToDisk()
        }
    }

    override fun getApiKey(provider: AiProvider): String {
        return synchronized(inMemoryStore) {
            inMemoryStore[KEY_PREFIX + provider.name] ?: ""
        }
    }

    override fun saveConfiguration(config: AiConfiguration) {
        synchronized(inMemoryStore) {
            inMemoryStore[KEY_CONFIGURATION] = json.encodeToString(config)
            saveToDisk()
        }
        _configuration.value = config
    }

    override fun loadConfiguration(): AiConfiguration {
        val raw = synchronized(inMemoryStore) { inMemoryStore[KEY_CONFIGURATION] } ?: return AiConfiguration()
        return try {
            json.decodeFromString<AiConfiguration>(raw)
        } catch (_: Exception) {
            AiConfiguration()
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (keyFile.exists()) {
            val keyBytes = keyFile.readBytes()
            if (keyBytes.size == 32) {
                return SecretKeySpec(keyBytes, "AES")
            }
        }
        val random = SecureRandom()
        val keyBytes = ByteArray(32)
        random.nextBytes(keyBytes)
        keyFile.writeBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(plainText: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encryptedBase64: String): String {
        val combined = Base64.getDecoder().decode(encryptedBase64)
        val iv = ByteArray(12)
        System.arraycopy(combined, 0, iv, 0, 12)
        val cipherText = ByteArray(combined.size - 12)
        System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherText)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    private fun saveToDisk() {
        try {
            val rawJson = json.encodeToString(inMemoryStore)
            val encrypted = encrypt(rawJson)
            configFile.writeText(encrypted)
        } catch (_: Exception) {}
    }

    private fun loadFromDisk() {
        try {
            if (configFile.exists()) {
                val encrypted = configFile.readText().trim()
                if (encrypted.isNotEmpty()) {
                    val decrypted = decrypt(encrypted)
                    val map = json.decodeFromString<Map<String, String>>(decrypted)
                    inMemoryStore.clear()
                    inMemoryStore.putAll(map)
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val KEY_PREFIX = "api_key_"
        private const val KEY_CONFIGURATION = "ai_configuration"
    }
}
