package com.clinref.app.data.local

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.data.local.entity.FavoriteEntity
import com.clinref.app.data.local.entity.HistoryEntity
import com.clinref.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppDatabaseTest {

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

    @Test
    fun testConversationCrudAndQueries() = runTest {
        val conversationDao = database.conversationDao()

        val conv = ConversationEntity(
            id = "conv-1",
            title = "Asthma Management",
            patientProfile = "Adult, 45yo",
            createdAt = 1000L,
            updatedAt = 1000L,
            promptTokens = 100,
            completionTokens = 200,
            toolTokens = 50,
            tokenLimit = 1000,
            isRead = false,
            isPinned = false
        )

        conversationDao.insert(conv)

        val fetched = conversationDao.getById("conv-1")
        assertNotNull(fetched)
        assertEquals("Asthma Management", fetched?.title)
        assertEquals("Adult, 45yo", fetched?.patientProfile)
        assertEquals(350, fetched?.totalTokens)
        assertFalse(fetched?.isPinned ?: true)
        assertFalse(fetched?.isRead ?: true)

        // Rename
        conversationDao.rename("conv-1", "Severe Asthma Care", now = 2000L)
        val renamed = conversationDao.getById("conv-1")
        assertEquals("Severe Asthma Care", renamed?.title)
        assertEquals(2000L, renamed?.updatedAt)

        // Toggle pin
        conversationDao.togglePin("conv-1")
        val pinned = conversationDao.getById("conv-1")
        assertTrue(pinned?.isPinned == true)

        // Update tokens
        conversationDao.updateTokenCounts(
            id = "conv-1",
            promptDelta = 200,
            completionDelta = 300,
            toolDelta = 200,
            now = 3000L
        )
        val tokenUpdated = conversationDao.getById("conv-1")
        assertEquals(300, tokenUpdated?.promptTokens)
        assertEquals(500, tokenUpdated?.completionTokens)
        assertEquals(250, tokenUpdated?.toolTokens)
        assertEquals(1050, tokenUpdated?.totalTokens)

        // Check token limit (1050 >= 1000)
        assertTrue(conversationDao.isOverTokenLimit("conv-1"))

        // Mark as read
        val unreadInitial = conversationDao.getUnreadCount().first()
        assertEquals(1, unreadInitial)

        conversationDao.markAsRead("conv-1")
        val readUpdated = conversationDao.getById("conv-1")
        assertTrue(readUpdated?.isRead == true)

        val unreadAfter = conversationDao.getUnreadCount().first()
        assertEquals(0, unreadAfter)

        // Update preview
        conversationDao.updatePreview("conv-1", "Inhaled corticosteroids recommended.", now = 4000L)
        val previewUpdated = conversationDao.getById("conv-1")
        assertEquals("Inhaled corticosteroids recommended.", previewUpdated?.lastMessagePreview)

        // Delete
        conversationDao.delete(previewUpdated!!)
        assertNull(conversationDao.getById("conv-1"))
    }

    @Test
    fun testMessageCascadeOnConversationDelete() = runTest {
        val conversationDao = database.conversationDao()
        val messageDao = database.messageDao()

        val conv = ConversationEntity(
            id = "conv-cascade",
            title = "Hypertension",
            patientProfile = "Male, 60yo",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        conversationDao.insert(conv)

        val msg1 = MessageEntity(
            id = "msg-1",
            conversationId = "conv-cascade",
            role = "user",
            content = "What are first-line agents for hypertension?",
            timestamp = 1000L
        )
        val msg2 = MessageEntity(
            id = "msg-2",
            conversationId = "conv-cascade",
            role = "assistant",
            content = "Thiazide diuretics, CCBs, ACE inhibitors, or ARBs.",
            timestamp = 1001L
        )

        messageDao.insertAll(listOf(msg1, msg2))

        val messagesBefore = messageDao.getMessagesList("conv-cascade")
        assertEquals(2, messagesBefore.size)
        assertEquals("msg-1", messagesBefore[0].id)
        assertEquals("msg-2", messagesBefore[1].id)

        val latest = messageDao.getLatestMessage("conv-cascade")
        assertEquals("msg-2", latest?.id)

        // Deleting conversation cascades to messages due to ForeignKey CASCADE
        conversationDao.delete(conv)

        val messagesAfter = messageDao.getMessagesList("conv-cascade")
        assertEquals(0, messagesAfter.size)
    }

    @Test
    fun testHistoryDaoOperations() = runTest {
        val historyDao = database.historyDao()

        val h1 = HistoryEntity(topicId = "topic-547", title = "Asthma Management", timestamp = 1000L)
        val h2 = HistoryEntity(topicId = "topic-543", title = "Asthma Diagnosis", timestamp = 2000L)

        historyDao.insert(h1)
        historyDao.insert(h2)

        val countInitial = historyDao.getCount().first()
        assertEquals(2, countInitial)

        val allItems = historyDao.getAll().first()
        assertEquals(2, allItems.size)
        // Ordered by timestamp DESC
        assertEquals("topic-543", allItems[0].topicId)
        assertEquals("topic-547", allItems[1].topicId)

        historyDao.delete("topic-547")
        val countAfterOneDelete = historyDao.getCount().first()
        assertEquals(1, countAfterOneDelete)

        historyDao.deleteAll()
        val countEmpty = historyDao.getCount().first()
        assertEquals(0, countEmpty)
    }

    @Test
    fun testFavoriteDaoOperations() = runTest {
        val favoriteDao = database.favoriteDao()

        val f1 = FavoriteEntity(topicId = "topic-100", title = "Pneumonia", timestamp = 500L)
        val f2 = FavoriteEntity(topicId = "topic-200", title = "COPD", timestamp = 600L)

        favoriteDao.insert(f1)
        favoriteDao.insert(f2)

        val count = favoriteDao.getCount().first()
        assertEquals(2, count)

        val isPneumoniaFav = favoriteDao.isFavorite("topic-100").first()
        assertTrue(isPneumoniaFav)

        val isHeartFailureFav = favoriteDao.isFavorite("topic-999").first()
        assertFalse(isHeartFailureFav)

        val favoritesList = favoriteDao.getAll().first()
        assertEquals(2, favoritesList.size)
        assertEquals("topic-200", favoritesList[0].topicId) // DESC order

        favoriteDao.delete("topic-100")
        val isPneumoniaFavAfter = favoriteDao.isFavorite("topic-100").first()
        assertFalse(isPneumoniaFavAfter)

        favoriteDao.deleteAll()
        val emptyCount = favoriteDao.getCount().first()
        assertEquals(0, emptyCount)
    }
}
