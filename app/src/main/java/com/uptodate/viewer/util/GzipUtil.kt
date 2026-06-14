package com.uptodate.viewer.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

object GzipUtil {
    private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x2b.toByte())

    fun decodePayload(data: ByteArray): String {
        if (data.size >= 2 && data[0] == GZIP_MAGIC[0] && data[1] == GZIP_MAGIC[1]) {
            return try {
                GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                    ByteArrayOutputStream().use { out ->
                        gzip.copyTo(out)
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            } catch (_: Exception) {
                String(data, Charsets.UTF_8)
            }
        }
        return String(data, Charsets.UTF_8)
    }
}
