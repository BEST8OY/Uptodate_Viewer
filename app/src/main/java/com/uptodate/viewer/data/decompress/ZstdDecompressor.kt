package com.uptodate.viewer.data.decompress

import io.airlift.compress.zstd.ZstdDecompressor

object ZstdDecompressor {

    private val MAGIC_BYTES = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private val decompressor = ZstdDecompressor()

    fun isCompressed(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == MAGIC_BYTES[0] &&
                data[1] == MAGIC_BYTES[1] &&
                data[2] == MAGIC_BYTES[2] &&
                data[3] == MAGIC_BYTES[3]
    }

    fun decompress(data: ByteArray): String? {
        return try {
            val decompressed = decompressToBytes(data) ?: return null
            String(decompressed, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun decompressToBytes(data: ByteArray): ByteArray? {
        return try {
            val maxSize = decompressor.getDecompressedLength(data, 0, data.size)
            if (maxSize <= 0) return null
            val output = ByteArray(maxSize)
            val actualSize = decompressor.decompress(data, 0, data.size, output, 0, output.size)
            output.copyOf(actualSize)
        } catch (e: Exception) {
            null
        }
    }
}
