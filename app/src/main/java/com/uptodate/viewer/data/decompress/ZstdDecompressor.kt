package com.uptodate.viewer.data.decompress

import com.github.luben.zstd.Zstd

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
            val size = Zstd.getFrameContentSize(data)
            if (size <= 0) return null
            val dst = ByteArray(size.toInt())
            val actualSize = Zstd.decompressByteArray(dst, 0, dst.size, data, 0, data.size)
            if (actualSize < 0) return null
            String(dst, 0, actualSize.toInt(), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun decompressToBytes(data: ByteArray): ByteArray? {
        return try {
            val size = Zstd.getFrameContentSize(data)
            if (size <= 0) return null
            val dst = ByteArray(size.toInt())
            val actualSize = Zstd.decompressByteArray(dst, 0, dst.size, data, 0, data.size)
            if (actualSize < 0) return null
            dst.copyOf(actualSize.toInt())
        } catch (e: Exception) {
            null
        }
    }
}
