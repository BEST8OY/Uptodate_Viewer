package com.clinref.app.util

import org.meshtastic.kzstd.Zstd

object ZstdUtil {
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    fun decodePayload(data: ByteArray): String {
        if (data.size >= 4 && ZSTD_MAGIC.indices.all { data[it] == ZSTD_MAGIC[it] }) {
            return try {
                String(Zstd.decompress(data, MAX_PAYLOAD_BYTES), Charsets.UTF_8)
            } catch (_: Exception) {
                String(data, Charsets.UTF_8)
            }
        }
        return String(data, Charsets.UTF_8)
    }
}
