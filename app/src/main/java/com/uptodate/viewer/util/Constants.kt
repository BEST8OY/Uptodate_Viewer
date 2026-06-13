package com.uptodate.viewer.util

object DbFiles {
    const val UNIDEX = "unidex.en.sqlite"
    const val ASSETS = "utdasset.sqlite"
    const val TOC = "utdtoc.db"
    const val FSEARCH = "fsearch.db"
    const val FCONTENTSEARCH = "fcontentsearch.db"
    const val QF = "utdqf.sqlite"
    val ALL = listOf(UNIDEX, ASSETS, TOC, FSEARCH, FCONTENTSEARCH, QF)
}

object AppAction {
    const val SCHEME = "appaction"
}

object Zstd {
    val MAGIC_BYTES = byteArrayOf(0x28, 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
}

object TopicId {
    val REGEX = Regex("^(?:topic-)?(\\d+)$", RegexOption.IGNORE_CASE)
}

object SearchPref {
    const val ALL = "X"
    const val ADULT = "A"
    const val PEDIATRIC = "P"
    const val PATIENT = "I"
}

object SearchColumns {
    const val D1 = "d1"
    const val D2 = "d2"
    const val D3 = "d3"
}
