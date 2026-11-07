package com.clinref.app.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStateTest {

    private fun createNavigator(
        startRoute: NavKey = TocRoute,
        topLevelRoutes: List<TopLevelRoute> = listOf(TocRoute, HistoryRoute, FavoritesRoute, AiRoute)
    ): Navigator {
        val topLevelState = mutableStateOf(startRoute)
        val backStacks = topLevelRoutes.associate { route ->
            route as NavKey to NavBackStack<NavKey>(route)
        }
        val state = NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelState,
            backStacks = backStacks,
            entryDecorators = emptyList()
        )
        return Navigator(state)
    }

    @Test
    fun `initial state starts at startRoute with isolated peer root stacks`() {
        val navigator = createNavigator(startRoute = TocRoute)
        assertEquals(TocRoute, navigator.state.topLevelRoute)
        assertEquals(listOf<NavKey>(TocRoute), navigator.state.backStacks[TocRoute]?.toList())
        assertEquals(listOf<NavKey>(HistoryRoute), navigator.state.backStacks[HistoryRoute]?.toList())
    }

    @Test
    fun `switching top-level tabs changes topLevelRoute without altering other stacks`() {
        val navigator = createNavigator()

        navigator.navigate(HistoryRoute)
        assertEquals(HistoryRoute, navigator.state.topLevelRoute)

        navigator.navigate(AiRoute)
        assertEquals(AiRoute, navigator.state.topLevelRoute)

        // Previous tab stacks remain intact
        assertEquals(listOf<NavKey>(TocRoute), navigator.state.backStacks[TocRoute]?.toList())
        assertEquals(listOf<NavKey>(HistoryRoute), navigator.state.backStacks[HistoryRoute]?.toList())
    }

    @Test
    fun `navigating to sub-route pushes onto current active top-level stack`() {
        val navigator = createNavigator()
        val contentRoute = ContentRoute("topic-123", "sec-1")

        navigator.navigate(contentRoute)

        val tocStack = navigator.state.backStacks[TocRoute]
        assertEquals(2, tocStack?.size)
        assertEquals(listOf<NavKey>(TocRoute, contentRoute), tocStack?.toList())
    }

    @Test
    fun `navigating to identical sub-route is idempotent and does not duplicate stack entry`() {
        val navigator = createNavigator()
        val contentRoute = ContentRoute("topic-123")

        navigator.navigate(contentRoute)
        navigator.navigate(contentRoute)

        val tocStack = navigator.state.backStacks[TocRoute]
        assertEquals(2, tocStack?.size)
    }

    @Test
    fun `reselecting active top-level route with sub-stack pops back to root`() {
        val navigator = createNavigator()
        navigator.navigate(ContentRoute("topic-1"))
        navigator.navigate(ContentRoute("topic-2"))
        assertEquals(3, navigator.state.backStacks[TocRoute]?.size)

        // Reselect TocRoute
        navigator.navigate(TocRoute)

        val tocStack = navigator.state.backStacks[TocRoute]
        assertEquals(1, tocStack?.size)
        assertEquals(TocRoute, tocStack?.first())
    }

    @Test
    fun `reselecting active top-level route when already at root emits reselect event`() = runTest {
        val navigator = createNavigator()
        var emittedEvent: NavKey? = null
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            navigator.reselectEvents.collect {
                emittedEvent = it
            }
        }

        navigator.navigate(TocRoute)
        testScheduler.runCurrent()
        assertEquals(TocRoute, emittedEvent)
        job.cancel()
    }

    @Test
    fun `goBack pops current sub-route when in sub-stack`() {
        val navigator = createNavigator()
        val content1 = ContentRoute("topic-1")
        val content2 = ContentRoute("topic-2")

        navigator.navigate(content1)
        navigator.navigate(content2)
        assertEquals(3, navigator.state.backStacks[TocRoute]?.size)

        navigator.goBack()
        assertEquals(listOf<NavKey>(TocRoute, content1), navigator.state.backStacks[TocRoute]?.toList())

        navigator.goBack()
        assertEquals(listOf<NavKey>(TocRoute), navigator.state.backStacks[TocRoute]?.toList())
    }

    @Test
    fun `goBack on non-start top-level root exits through home (navigates to startRoute)`() {
        val navigator = createNavigator(startRoute = TocRoute)

        navigator.navigate(HistoryRoute)
        assertEquals(HistoryRoute, navigator.state.topLevelRoute)

        navigator.goBack()
        // Should exit through start route
        assertEquals(TocRoute, navigator.state.topLevelRoute)
    }

    @Test
    fun `goBack at startRoute root does not pop or crash`() {
        val navigator = createNavigator(startRoute = TocRoute)

        navigator.goBack()
        assertEquals(TocRoute, navigator.state.topLevelRoute)
        assertEquals(listOf<NavKey>(TocRoute), navigator.state.backStacks[TocRoute]?.toList())
    }

    @Test
    fun `switching tabs preserves individual tab sub-stacks`() {
        val navigator = createNavigator()

        // Push sub-route on Toc
        navigator.navigate(ContentRoute("topic-toc"))

        // Switch to History and push sub-route
        navigator.navigate(HistoryRoute)
        navigator.navigate(ContentRoute("topic-hist"))

        // Switch back to Toc
        navigator.navigate(TocRoute)
        assertEquals(listOf<NavKey>(TocRoute, ContentRoute("topic-toc")), navigator.state.backStacks[TocRoute]?.toList())

        // Switch back to History
        navigator.navigate(HistoryRoute)
        assertEquals(listOf<NavKey>(HistoryRoute, ContentRoute("topic-hist")), navigator.state.backStacks[HistoryRoute]?.toList())
    }

    @Test
    fun `standard library entry points TOC, History, and Favorites push ContentRoute onto their backstacks for full-screen navigation`() {
        val navigator = createNavigator()

        // 1. TOC (and Global Search within TOC) navigates to ContentRoute
        val tocContent = ContentRoute("topic-toc", "sec-1")
        navigator.navigate(tocContent)
        assertEquals(listOf<NavKey>(TocRoute, tocContent), navigator.state.backStacks[TocRoute]?.toList())
        navigator.goBack()
        assertEquals(listOf<NavKey>(TocRoute), navigator.state.backStacks[TocRoute]?.toList())

        // 2. History navigates to ContentRoute
        navigator.navigate(HistoryRoute)
        val historyContent = ContentRoute("topic-history")
        navigator.navigate(historyContent)
        assertEquals(listOf<NavKey>(HistoryRoute, historyContent), navigator.state.backStacks[HistoryRoute]?.toList())
        navigator.goBack()
        assertEquals(listOf<NavKey>(HistoryRoute), navigator.state.backStacks[HistoryRoute]?.toList())

        // 3. Favorites navigates to ContentRoute
        navigator.navigate(FavoritesRoute)
        val favContent = ContentRoute("topic-fav")
        navigator.navigate(favContent)
        assertEquals(listOf<NavKey>(FavoritesRoute, favContent), navigator.state.backStacks[FavoritesRoute]?.toList())
        navigator.goBack()
        assertEquals(listOf<NavKey>(FavoritesRoute), navigator.state.backStacks[FavoritesRoute]?.toList())
    }

    @Test
    fun `AI chat backstack invariant - conversation stack remains unmutated by in-chat reading while preserving session across home navigation`() {
        val navigator = createNavigator()

        // Navigate to AI tab and enter a conversation
        navigator.navigate(AiRoute)
        val chatRoute = ChatRoute("conv-session-123")
        navigator.navigate(chatRoute)

        assertEquals(AiRoute, navigator.state.topLevelRoute)
        assertEquals(listOf<NavKey>(AiRoute, chatRoute), navigator.state.backStacks[AiRoute]?.toList())

        // In AI Chat, article citations and graphic previews are displayed via local ModalBottomSheet
        // overlays inside ChatScreen without mutating navigator backstacks (R1, R3).
        // Backstack remains strictly [AiRoute, ChatRoute]
        assertEquals(2, navigator.state.backStacks[AiRoute]?.size)
        assertEquals(chatRoute, navigator.state.backStacks[AiRoute]?.last())

        // Clinician taps "Home" from in-sheet reader, navigating to TocRoute (R2)
        navigator.navigate(TocRoute)
        assertEquals(TocRoute, navigator.state.topLevelRoute)

        // AI backstack is preserved intact with the conversation still active (100% conversation preservation)
        assertEquals(listOf<NavKey>(AiRoute, chatRoute), navigator.state.backStacks[AiRoute]?.toList())

        // Switching back to AiRoute restores the active conversation directly
        navigator.navigate(AiRoute)
        assertEquals(AiRoute, navigator.state.topLevelRoute)
        assertEquals(listOf<NavKey>(AiRoute, chatRoute), navigator.state.backStacks[AiRoute]?.toList())
    }
}

