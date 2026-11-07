package com.clinref.app.ui.content

import androidx.lifecycle.SavedStateHandle
import com.clinref.app.domain.ReadingPosition
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.FavoriteRepository
import com.clinref.app.repository.HistoryRepository
import com.clinref.app.repository.ReadingPositionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val contentRepository: ContentRepository = mockk(relaxed = true)
    private val favoriteRepository: FavoriteRepository = mockk(relaxed = true)
    private val historyRepository: HistoryRepository = mockk(relaxed = true)
    private val readingPositionRepository: ReadingPositionRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        ContentViewModel(
            contentRepository = contentRepository,
            favoriteRepository = favoriteRepository,
            historyRepository = historyRepository,
            readingPositionRepository = readingPositionRepository,
            savedStateHandle = savedStateHandle,
            ioDispatcher = testDispatcher
        )

    @Test
    fun `open loads topic and emits ArticleDocument`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val bodyHtml = "<p>Asthma management content</p>"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.open(topicId)
        advanceUntilIdle()

        val doc = vm.document.value
        assertNotNull(doc)
        assertEquals(topicId, doc?.topicId)
        assertEquals(StartTarget.Top, doc?.start)
        assertTrue(doc?.html?.contains("Asthma management content") == true)
        coVerify { historyRepository.addOrPromote(topicId, "Asthma", any()) }
    }

    @Test
    fun `open is idempotent and does not reload on re-entry`() = runTest(testDispatcher) {
        val topic1 = "asthma-1"
        val topic2 = "hypertension-2"
        every { contentRepository.getTopicContent(any()) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Content</p>",
            title = "Title"
        )
        every { contentRepository.getTopicTitle(any()) } returns "Title"
        coEvery { readingPositionRepository.get(any()) } returns null

        val vm = createViewModel()
        vm.open(topic1)
        advanceUntilIdle()
        assertEquals(topic1, vm.currentTopicId.value)

        // Re-entry with a different route topicId must NOT overwrite current state
        vm.open(topic2)
        advanceUntilIdle()
        assertEquals(topic1, vm.currentTopicId.value)
    }

    @Test
    fun `loadTopic restores saved reading position when no section specified`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val savedPos = ReadingPosition(
            anchor = "b:10:0.5000",
            progress = 0.45f,
            sectionId = "sec-treatment",
            contentRev = "rev1"
        )
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns savedPos

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val doc = vm.document.value
        assertNotNull(doc)
        assertEquals(StartTarget.Resume(savedPos), doc?.start)
    }

    @Test
    fun `loadTopic with explicit sectionId prioritizes section over saved position`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val savedPos = ReadingPosition(
            anchor = "b:10:0.5000",
            progress = 0.45f,
            sectionId = "sec-treatment",
            contentRev = "rev1"
        )
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns savedPos

        val vm = createViewModel()
        vm.loadTopic(topicId, sectionId = "sec-diagnosis")
        advanceUntilIdle()

        val doc = vm.document.value
        assertNotNull(doc)
        assertEquals(StartTarget.Section("sec-diagnosis"), doc?.start)
    }

    @Test
    fun `loadTopic normalizes FULL section sentinel`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId, sectionId = "FULL")
        advanceUntilIdle()

        val doc = vm.document.value
        assertNotNull(doc)
        // FULL must normalize to Top
        assertEquals(StartTarget.Top, doc?.start)
    }

    @Test
    fun `onPositionChanged saves to repository and updates active section`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val currentDoc = vm.document.value
        assertNotNull(currentDoc)
        val pos = ReadingPosition(
            anchor = "b:7:0.2500",
            progress = 0.35f,
            sectionId = "sec-epidemiology",
            contentRev = currentDoc!!.contentRev
        )

        vm.onPositionChanged(pos)

        coVerify { readingPositionRepository.save(topicId, pos) }
        assertEquals("sec-epidemiology", vm.activeSectionId.value)
    }

    @Test
    fun `theme change keeps live reading position`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val initialDoc = vm.document.value
        assertNotNull(initialDoc)
        val pos = ReadingPosition(
            anchor = "b:12:0.8000",
            progress = 0.60f,
            sectionId = "sec-management",
            contentRev = initialDoc!!.contentRev
        )
        vm.onPositionChanged(pos)

        // Switch to dark theme
        vm.setThemeColors(ThemeColors.dark())

        val reThemedDoc = vm.document.value
        assertNotNull(reThemedDoc)
        assertTrue(reThemedDoc!!.revision > initialDoc.revision)
        assertEquals(StartTarget.Resume(pos), reThemedDoc.start)
    }

    @Test
    fun `resolveStart supplies latest position for view recreation`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma content</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val doc = vm.document.value
        assertNotNull(doc)
        assertEquals(StartTarget.Top, vm.resolveStart(doc!!))

        // User scrolled further
        val pos = ReadingPosition(
            anchor = "b:20:0.1000",
            progress = 0.75f,
            sectionId = "sec-followup",
            contentRev = doc.contentRev
        )
        vm.onPositionChanged(pos)

        // Re-resolving start on the same document (e.g. AndroidView recreated after tab switch)
        // returns the newly scrolled position!
        assertEquals(StartTarget.Resume(pos), vm.resolveStart(doc))
        // The document itself is immutable: it still records where it was born.
        assertEquals(StartTarget.Top, vm.document.value!!.start)
    }

    @Test
    fun `scrollToSection emits to sectionJumps flow`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val jumps = mutableListOf<String>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.sectionJumps.collect { jumps.add(it) }
        }

        vm.scrollToSection("sec-prognosis")

        assertEquals(listOf("sec-prognosis"), jumps)
        job.cancel()
    }
}
