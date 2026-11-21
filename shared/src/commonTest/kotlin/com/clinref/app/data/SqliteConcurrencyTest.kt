package com.clinref.app.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.shared.db.useExecute
import com.clinref.shared.db.useQuery
import com.clinref.shared.db.useQueryFirstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SqliteConcurrencyTest {

    @Test
    fun testConcurrentQueriesOnSingleConnectionDoNotRaceOrCorrupt() {
        runBlocking(Dispatchers.Default) {
            val driver = BundledSQLiteDriver()
            val connection = driver.open(":memory:")
            try {
                connection.useExecute("CREATE TABLE test (id INTEGER PRIMARY KEY, val TEXT)")
                for (i in 1..20) {
                    connection.useExecute("INSERT INTO test (id, val) VALUES ($i, 'val_$i')")
                }

                // Spawn 50 concurrent coroutines on multiple threads performing simultaneous queries
                val jobs = (1..50).map { idx ->
                    async(Dispatchers.Default) {
                        val targetId = (idx % 20) + 1
                        val res = connection.useQueryFirstOrNull(
                            "SELECT val FROM test WHERE id = ?",
                            bind = { it.bindLong(1, targetId.toLong()) }
                        ) { stmt -> stmt.getText(0) }
                        assertEquals("val_$targetId", res)

                        val all = connection.useQuery("SELECT count(*) FROM test") { stmt -> stmt.getLong(0) }
                        assertEquals(1, all.size)
                        assertEquals(20L, all[0])
                    }
                }
                jobs.awaitAll()
            } finally {
                connection.close()
            }
        }
    }
}
