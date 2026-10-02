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
}
