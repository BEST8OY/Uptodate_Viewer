package com.uptodate.viewer.data.decompress

import com.squareup.zstd.ZSTD_e_continue
import com.squareup.zstd.ZSTD_e_end
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
            var output = ByteArray(data.size * 20)
            var inputOffset = 0
            var outputOffset = 0
            while (inputOffset < data.size) {
                if (outputOffset >= output.size) {
                    output = output.copyOf(output.size * 2)
                }
                val result = decompressor.decompressStream(
                    outputByteArray = output,
                    outputEnd = output.size,
                    outputStart = outputOffset,
                    inputByteArray = data,
                    inputEnd = data.size,
                    inputStart = inputOffset,
                )
                val inputRead = decompressor.inputBytesProcessed
                val outputWritten = decompressor.outputBytesProcessed
                if (inputRead == 0 && outputWritten == 0 && result != ZSTD_e_end.toLong()) return null
                inputOffset += inputRead
                outputOffset += outputWritten
                if (result == ZSTD_e_end.toLong()) break
                if (result != ZSTD_e_continue.toLong()) return null
            }
            if (outputOffset <= 0) null else output.copyOf(outputOffset)
        } catch (e: Exception) {
            null
        } finally {
            decompressor.close()
        }
    }
}
