package com.clinref.app.repository

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.local.AppDatabase
import com.clinref.app.data.local.AppDatabaseConstructor
import com.clinref.app.domain.ReadingPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPositionRepositoryTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            factory = { AppDatabaseConstructor.initialize() }
        )
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun pos(anchor: String = "b:12:0.5", progress: Float = 0.4f) =
        ReadingPosition(anchor = anchor, progress = progress, sectionId = "S1", contentRev = "rev1")

    @Test
    fun `save is visible immediately before it is flushed`() = runTest {
        val repo = ReadingPositionRepository(database.readingPositionDao(), backgroundScope)

        repo.save("123", pos())

        assertEquals(pos(), repo.get("123"))
        assertNull(database.readingPositionDao().get("123"))
    }

    @Test
    fun `trailing flush persists only the latest position`() = runTest {
        val dao = database.readingPositionDao()
        val repo = ReadingPositionRepository(dao, backgroundScope, flushDelayMs = 1_000)

        repo.save("123", pos("b:1:0.0", 0.1f))
        repo.save("123", pos("b:9:0.3", 0.3f))
        advanceTimeBy(1_100)
        // Barrier: waits for the in-flight scheduled flush (Room runs on real IO threads).
        repo.flush()

        val stored = dao.get("123")
        assertNotNull(stored)
        assertEquals("b:9:0.3", stored?.anchor)
        assertEquals(1, dao.count())
    }

    @Test
    fun `get falls back to Room after flush`() = runTest {
        val dao = database.readingPositionDao()
        ReadingPositionRepository(dao, backgroundScope).apply {
            save("123", pos())
            flush()
        }

        // A fresh repository instance models a process restart.
        val restarted = ReadingPositionRepository(dao, backgroundScope)
        assertEquals(pos(), restarted.get("123"))
    }

    @Test
    fun `topic prefix is normalized`() = runTest {
        val repo = ReadingPositionRepository(database.readingPositionDao(), backgroundScope)

        repo.save("topic-123", pos())
        repo.flush()

        assertEquals(pos(), repo.get("123"))
        assertEquals(pos(), repo.get("topic-123"))
    }

    @Test
    fun `finished article resets to top`() = runTest {
        val dao = database.readingPositionDao()
        val repo = ReadingPositionRepository(dao, backgroundScope)

        repo.save("123", pos(progress = 0.5f))
        repo.flush()
        assertNotNull(repo.get("123"))

        repo.save("123", pos(progress = 0.99f))
        assertNull(repo.get("123"))
        repo.flush()
        assertNull(dao.get("123"))
    }

    @Test
    fun `clear removes persisted position`() = runTest {
        val dao = database.readingPositionDao()
        val repo = ReadingPositionRepository(dao, backgroundScope)
        repo.save("123", pos())
        repo.flush()

        repo.clear("123")
        assertNull(repo.get("123"))
        repo.flush()
        assertNull(dao.get("123"))
    }

    @Test
    fun `prune keeps most recent entries`() = runTest {
        val dao = database.readingPositionDao()
        var now = 0L
        val repo = ReadingPositionRepository(dao, backgroundScope, maxEntries = 2, clock = { now++ })

        repo.save("a", pos("b:1:0", 0.1f))
        repo.save("b", pos("b:2:0", 0.2f))
        repo.save("c", pos("b:3:0", 0.3f))
        repo.prune()

        assertEquals(2, dao.count())
        assertNull(dao.get("a"))
        assertNotNull(dao.get("b"))
        assertNotNull(dao.get("c"))
    }
}
