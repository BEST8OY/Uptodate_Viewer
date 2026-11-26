package com.clinref.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal fun topLevelRouteSaver(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>
): Saver<MutableState<NavKey>, String> = Saver(
    save = { state ->
        when (val route = state.value) {
            is TopLevelRoute -> route.routeId
            else -> route.toString()
        }
    },
    restore = { savedName ->
        val restored = topLevelRoutes.firstOrNull { route ->
            when (route) {
                is TopLevelRoute -> route.routeId == savedName ||
                    route::class.simpleName == savedName ||
                    route.title == savedName ||
                    route.toString() == savedName
                else -> route.toString() == savedName
            }
        } ?: startRoute
        mutableStateOf(restored)
    }
)

@Composable
fun rememberNavigationState(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>
): NavigationState {
    val topLevelRoute = rememberSaveable(
        startRoute, topLevelRoutes,
        saver = remember(startRoute, topLevelRoutes) { topLevelRouteSaver(startRoute, topLevelRoutes) }
    ) {
        mutableStateOf(startRoute)
    }

    val backStacks = topLevelRoutes.associateWith { routeKey ->
        key(routeKey) {
            rememberNavBackStack(navSavedStateConfig, routeKey)
        }
    }

    return remember(startRoute, topLevelRoutes, backStacks, topLevelRoute) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks
        )
    }
}

class NavigationState(
    val startRoute: NavKey,
    topLevelRoute: MutableState<NavKey>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>
) {
    init {
        require(startRoute in backStacks) {
            "startRoute ($startRoute) must be present in topLevelRoutes: ${backStacks.keys}"
        }
    }

    var topLevelRoute: NavKey by topLevelRoute
        internal set

    val currentStack: NavBackStack<NavKey>?
        get() = backStacks[topLevelRoute]

    val isOnSubRoute: Boolean by derivedStateOf {
        (currentStack?.size ?: 0) > 1
    }

    @Composable
    fun toDecoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>
    ): List<NavEntry<NavKey>> {
        val decoratedEntries = backStacks.mapValues { (routeKey, stack) ->
            key(routeKey) {
                val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
                val viewModelStoreOwner = rememberViewModelStoreOwner()
                val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>(
                    viewModelStoreOwner = viewModelStoreOwner
                )
                val entryDecorators = remember(saveableDecorator, viewModelDecorator) {
                    listOf(saveableDecorator, viewModelDecorator)
                }
                rememberDecoratedNavEntries(
                    backStack = stack,
                    entryDecorators = entryDecorators,
                    entryProvider = entryProvider
                )
            }
        }

        // ONLY expose active route's stack to NavDisplay (Independent Peer Roots pattern)
        return decoratedEntries[topLevelRoute] ?: emptyList()
    }
}

class Navigator(val state: NavigationState) {
    private val _reselectEvents = MutableSharedFlow<NavKey>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val reselectEvents: SharedFlow<NavKey> = _reselectEvents.asSharedFlow()

    private fun onReselect(route: NavKey) {
        val stack = state.backStacks[route]
        if (stack != null && stack.size > 1) {
            // Re-selecting active top-level route while in sub-route pops sub-stack to root
            while (stack.size > 1) {
                stack.removeLastOrNull()
            }
        } else {
            // Re-selecting when already at root emits reselect event (e.g. scroll to top)
            _reselectEvents.tryEmit(route)
        }
    }

    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            if (route == state.topLevelRoute) {
                onReselect(route)
            } else {
                // Switching top-level tab preserves sub-backstack state
                state.topLevelRoute = route
            }
        } else {
            val currentStack = state.backStacks[state.topLevelRoute] ?: return
            if (currentStack.lastOrNull() == route) return
            if (route in currentStack) {
                currentStack.remove(route)
            }
            currentStack.add(route)
        }
    }

    fun navigateToHome() {
        state.topLevelRoute = state.startRoute
        val startStack = state.backStacks[state.startRoute] ?: return
        while (startStack.size > 1) {
            startStack.removeLastOrNull()
        }
    }

    fun popSubRoute(expected: NavKey? = null): Boolean {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return false
        if (currentStack.size <= 1) return false
        if (expected != null && currentStack.lastOrNull() != expected) return false
        currentStack.removeLastOrNull()
        return true
    }

    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return
        val currentRoute = currentStack.lastOrNull() ?: return

        if (currentRoute == state.topLevelRoute) {
            if (state.topLevelRoute != state.startRoute) {
                // At non-start top-level root — pop back to start route (Exit Through Home pattern)
                state.topLevelRoute = state.startRoute
            }
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
