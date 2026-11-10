package com.clinref.app.util

object UrlUtils {
    /**
     * Decodes a percent-encoded URL string in pure Kotlin (cross-platform).
     */
    fun urlDecode(encoded: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                val hex = encoded.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    result.append(code.toChar())
                    i += 3
                    continue
                }
            } else if (c == '+') {
                result.append(' ')
                i++
                continue
            }
            result.append(c)
            i++
        }
        return result.toString()
    }

    /**
     * URL-encodes a string for query parameters in pure Kotlin (cross-platform).
     */
    fun urlEncode(text: String): String {
        val result = StringBuilder()
        for (ch in text) {
            when (ch) {
                in 'a'..'z', in 'A'..'Z', in '0'..'9', '-', '_', '.', '~' -> result.append(ch)
                ' ' -> result.append('+')
                else -> {
                    val bytes = ch.toString().encodeToByteArray()
                    for (b in bytes) {
                        val hex = (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                        result.append("%$hex")
                    }
                }
            }
        }
        return result.toString()
    }

    /**
     * Cleans an external URL, extracting the target URL if wrapped in an UpToDate redirect.
     */
    fun cleanExternalUrl(url: String): String {
        if ("target_url=" in url) {
            val extracted = url.substringAfter("target_url=").substringBefore("&")
            var decoded = urlDecode(extracted)
            if ("%" in decoded) {
                decoded = urlDecode(decoded)
            }
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return url
    }
}
