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

    @Test
    fun `handleAction cross-topic link forwards sectionId and bypasses stale saved position`() = runTest(testDispatcher) {
        val currentTopic = "asthma-1"
        val targetTopic = "copd-2"
        val targetSection = "H55268797"

        every { contentRepository.getTopicContent(currentTopic) } returns ContentRepository.TopicContent(
            bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;$targetSection&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;$targetTopic&quot;,&quot;section&quot;:&quot;$targetSection&quot;}]});">See COPD</a>""",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(currentTopic) } returns "Asthma"
        coEvery { readingPositionRepository.get(currentTopic) } returns null

        every { contentRepository.getTopicContent(targetTopic) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>COPD content</p>",
            title = "COPD"
        )
        every { contentRepository.getTopicTitle(targetTopic) } returns "COPD"
        // Target topic has a stale saved reading position from a previous session
        coEvery { readingPositionRepository.get(targetTopic) } returns ReadingPosition(
            anchor = "b:5:0.1000",
            progress = 0.20f,
            sectionId = "H1",
            contentRev = "oldRev"
        )

        val vm = createViewModel()
        vm.loadTopic(currentTopic)
        advanceUntilIdle()

        // Clicking the rewritten appaction link
        vm.handleAction("action_0")
        advanceUntilIdle()

        // Target topic must be loaded directly at targetSection, NOT resuming at stale anchor
        assertEquals(targetTopic, vm.currentTopicId.value)
        val doc = vm.document.value
        assertNotNull(doc)
        assertEquals(StartTarget.Section(targetSection), doc?.start)
    }

    @Test
    fun `handleAction intra-topic link triggers scrollToSection`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val sameTopicSection = "H7"

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;$topicId&quot;,&quot;section&quot;:&quot;$sameTopicSection&quot;}]});">Dosing</a>""",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val jumps = mutableListOf<String>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.sectionJumps.collect { jumps.add(it) }
        }

        vm.handleAction("action_0")
        advanceUntilIdle()

        assertEquals(listOf(sameTopicSection), jumps)
        job.cancel()
    }

    @Test
    fun `handleAction graphic link updates onNavigateToGraphic`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val graphicId = "116392"

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;$graphicId&quot;,&quot;type&quot;:&quot;graphic&quot;}]});">Table 1</a>""",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        vm.handleAction("action_0")

        assertEquals(graphicId, vm.onNavigateToGraphic.value)
    }

    @Test
    fun `handleAction external link updates onOpenExternalUrl`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val externalUrl = "https://www.uptodate.com/external-redirect"

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{},&quot;data&quot;:[{&quot;type&quot;:&quot;external&quot;,&quot;url&quot;:&quot;$externalUrl&quot;}]});">External Link</a>""",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        vm.handleAction("action_0")

        assertEquals(externalUrl, vm.onOpenExternalUrl.value)
    }

    @Test
    fun `handleAction with registered actionId triggers section jump`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        val bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;H99&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;$topicId&quot;}]});">Jump to Section</a>"""
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        val jumps = mutableListOf<String>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.sectionJumps.collect { jumps.add(it) }
        }

        vm.handleAction("action_0")
        advanceUntilIdle()

        assertEquals(listOf("H99"), jumps)
        job.cancel()
    }

    @Test
    fun `handleAction with unknown actionId returns safely`() = runTest(testDispatcher) {
        val topicId = "asthma-1"
        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Asthma"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        // Should return early and not crash
        vm.handleAction("nonexistent_action")
        advanceUntilIdle()
    }

    @Test
    fun `handleOutlineAction unescapes entities and navigates properly`() = runTest(testDispatcher) {
        val currentTopic = "asthma-1"
        val targetTopic = "steroids-3"
        val targetSection = "H12"

        every { contentRepository.getTopicContent(currentTopic) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Asthma</p>",
            title = "Asthma"
        )
        every { contentRepository.getTopicTitle(currentTopic) } returns "Asthma"
        coEvery { readingPositionRepository.get(currentTopic) } returns null

        every { contentRepository.getTopicContent(targetTopic) } returns ContentRepository.TopicContent(
            bodyHtml = "<p>Steroids content</p>",
            title = "Steroids"
        )
        every { contentRepository.getTopicTitle(targetTopic) } returns "Steroids"
        coEvery { readingPositionRepository.get(targetTopic) } returns null

        val vm = createViewModel()
        vm.loadTopic(currentTopic)
        advanceUntilIdle()

        // Outline JSON often has escaped &quot;
        val outlineJson = """{&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;$targetSection&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;$targetTopic&quot;}]}"""
        vm.handleOutlineAction(outlineJson)
        advanceUntilIdle()

        assertEquals(targetTopic, vm.currentTopicId.value)
        val doc = vm.document.value
        assertEquals(StartTarget.Section(targetSection), doc?.start)
    }

    @Test
    fun `handleAction inline reference opens referenceDialog and does NOT load topic or show error`() = runTest(testDispatcher) {
        val topicId = "topic-100"
        val bodyHtml = """
            <p>Risk factors described in [<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;,&quot;topicId&quot;:&quot;$topicId&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;118701&quot;,&quot;type&quot;:&quot;abstract&quot;,&quot;number&quot;:&quot;60&quot;}]});">60</a>].</p>
            <div id="references">
                <h1>REFERENCES</h1>
                <ol id="reference">
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;118701&quot;,&quot;number&quot;:&quot;60&quot;}]});">Sibai BM. Preeclampsia research. Am J Obstet 1995; 172:642.</a></li>
                </ol>
            </div>
        """.trimIndent()

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Preeclampsia"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Preeclampsia"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        // Click the inline reference action_0 (which is the link for reference 60)
        vm.handleAction("action_0")
        advanceUntilIdle()

        // It must NOT have attempted to load topic "60" or set error "Content not found"
        assertEquals(topicId, vm.currentTopicId.value)
        assertEquals(null, vm.error.value)

        // It must have opened referenceDialog with reference 60
        val dialogData = vm.referenceDialog.value
        assertNotNull(dialogData)
        assertEquals(1, dialogData?.references?.size)
        assertEquals(60, dialogData?.references?.first()?.number)
        assertEquals("Sibai BM. Preeclampsia research. Am J Obstet 1995; 172:642.", dialogData?.references?.first()?.citation)
    }

    @Test
    fun `handleAction bottom reference opens referenceDialog with citation details`() = runTest(testDispatcher) {
        val topicId = "topic-100"
        val bodyHtml = """
            <div id="references">
                <h1>REFERENCES</h1>
                <ol id="reference">
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;50&quot;,&quot;number&quot;:&quot;1&quot;}]});">Greer BE. Supraumbilical incision. Obstet Gynecol 1990; 76:471.</a></li>
                </ol>
            </div>
        """.trimIndent()

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Surgery"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Surgery"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        // action_0 is the reference item in the references list
        vm.handleAction("action_0")
        advanceUntilIdle()

        assertEquals(null, vm.error.value)
        val dialogData = vm.referenceDialog.value
        assertNotNull(dialogData)
        assertEquals(1, dialogData?.references?.first()?.number)
        assertEquals("50", dialogData?.references?.first()?.abstractId)
    }

    @Test
    fun `jumpToReference emits ref anchor to sectionJumps and dismisses dialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val jumps = mutableListOf<String>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.sectionJumps.collect { jumps.add(it) }
        }

        vm.jumpToReference(60)
        advanceUntilIdle()

        assertEquals(listOf("ref_60"), jumps)
        assertEquals(null, vm.referenceDialog.value)
        job.cancel()
    }

    @Test
    fun `handleAction grade resource opens gradeDialog and does not load topic`() = runTest(testDispatcher) {
        val topicId = "topic-100"
        val bodyHtml = """
            <p>Recommendation [<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;resource&quot;,&quot;gradeId&quot;:&quot;2&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;grades2.html&quot;,&quot;type&quot;:&quot;grade&quot;}]});">Grade 1B</a>].</p>
        """.trimIndent()

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Guideline"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Guideline"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        vm.handleAction("action_0")
        advanceUntilIdle()

        assertEquals(null, vm.error.value)
        assertEquals(topicId, vm.currentTopicId.value)
        val gradeData = vm.gradeDialog.value
        assertNotNull(gradeData)
        assertEquals("Grade 1B", gradeData?.grade)
        assertEquals("Strong recommendation", gradeData?.recommendationStrength)
    }

    @Test
    fun `handleAction editorial policy opens policyDialog and does not load topic`() = runTest(testDispatcher) {
        val topicId = "topic-100"
        val bodyHtml = """
            <p><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;resource&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;editorial-policy.html&quot;,&quot;type&quot;:&quot;legal&quot;}]});">Editorial Policy</a></p>
        """.trimIndent()

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = bodyHtml,
            title = "Topic"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Topic"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        vm.handleAction("action_0")
        advanceUntilIdle()

        assertEquals(null, vm.error.value)
        assertEquals(topicId, vm.currentTopicId.value)
        val policyData = vm.policyDialog.value
        assertNotNull(policyData)
        assertEquals("UpToDate Editorial Policy", policyData?.title)
    }

    @Test
    fun `handleAction external link decodes target_url redirect correctly`() = runTest(testDispatcher) {
        val topicId = "topic-100"
        val wrappedUrl = "http://www.uptodate.com/external-redirect?target_url=https%253A%252F%252Fwww.ameriburn.org%252Fburn-care&token=xyz"

        every { contentRepository.getTopicContent(topicId) } returns ContentRepository.TopicContent(
            bodyHtml = """<a href="javascript:appAction({&quot;meta&quot;:{},&quot;data&quot;:[{&quot;type&quot;:&quot;external&quot;,&quot;url&quot;:&quot;$wrappedUrl&quot;}]});">Burn Guidelines</a>""",
            title = "Burns"
        )
        every { contentRepository.getTopicTitle(topicId) } returns "Burns"
        coEvery { readingPositionRepository.get(topicId) } returns null

        val vm = createViewModel()
        vm.loadTopic(topicId)
        advanceUntilIdle()

        vm.handleAction("action_0")

        assertEquals("https://www.ameriburn.org/burn-care", vm.onOpenExternalUrl.value)
    }
}
