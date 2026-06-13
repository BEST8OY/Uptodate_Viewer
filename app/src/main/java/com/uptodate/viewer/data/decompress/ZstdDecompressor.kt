package com.uptodate.viewer.data.decompress

import com.squareup.zstd.zstdDecompressor

object ZstdDecompressor {

    private val MAGIC_BYTES = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

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
        val decompressor = zstdDecompressor()
        return try {
            val output = ByteArray(data.size * 4)
            val result = decompressor.decompressStream(
                outputByteArray = output,
                outputEnd = output.size,
                outputStart = 0,
                inputByteArray = data,
                inputEnd = data.size,
                inputStart = 0
            )
            if (result != 0L) return null
            val size = decompressor.outputBytesProcessed
            if (size <= 0) null else output.copyOf(size)
        } catch (e: Exception) {
            null
        } finally {
            decompressor.close()
        }
    }
}
