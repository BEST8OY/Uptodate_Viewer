package com.uptodate.viewer.data.decompress

import com.github.luben.zstd.Zstd
import com.uptodate.viewer.util.Zstd

object ZstdDecompressor {

    fun isCompressed(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == Zstd.MAGIC_BYTES[0] &&
                data[1] == Zstd.MAGIC_BYTES[1] &&
                data[2] == Zstd.MAGIC_BYTES[2] &&
                data[3] == Zstd.MAGIC_BYTES[3]
    }

    fun decompress(data: ByteArray): String? {
        return try {
            val size = Zstd.decompressedSize(data)
            if (size <= 0) return null
            val dst = ByteArray(size.toInt())
            val actualSize = Zstd.decompress(dst, data)
            if (actualSize < 0) return null
            String(dst, 0, actualSize.toInt(), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun decompressToBytes(data: ByteArray): ByteArray? {
        return try {
            val size = Zstd.decompressedSize(data)
            if (size <= 0) return null
            val dst = ByteArray(size.toInt())
            val actualSize = Zstd.decompress(dst, data)
            if (actualSize < 0) return null
            dst.copyOf(actualSize.toInt())
        } catch (e: Exception) {
            null
        }
    }
}
