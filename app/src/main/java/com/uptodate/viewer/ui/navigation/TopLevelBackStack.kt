package com.uptodate.viewer.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

class TopLevelBackStack<T : Any>(
    startKey: T,
    private val isDetailKey: (Any) -> Boolean = { false }
) {

    private var topLevelStacks: LinkedHashMap<T, SnapshotStateList<T>> = linkedMapOf(
        startKey to mutableStateListOf(startKey)
    )

    var topLevelKey by mutableStateOf(startKey)
        private set

    val backStack = mutableStateListOf(startKey)

    private fun updateBackStack() =
        backStack.apply {
            clear()
            addAll(topLevelStacks.flatMap { it.value })
        }

    fun addTopLevel(key: T) {
        if (topLevelStacks[key] == null) {
            topLevelStacks[key] = mutableStateListOf(key)
        } else {
            topLevelStacks.apply {
                remove(key)?.let {
                    put(key, it)
                }
            }
        }
        topLevelKey = key
        clearDetailEntriesFromOtherTabs()
        updateBackStack()
    }

    fun add(key: T) {
        topLevelStacks[topLevelKey]?.add(key)
        updateBackStack()
    }

    fun removeLast() {
        val currentStack = topLevelStacks[topLevelKey] ?: return
        if (currentStack.size <= 1) {
            if (topLevelKey != topLevelStacks.keys.first()) {
                topLevelStacks.remove(topLevelKey)
                topLevelKey = topLevelStacks.keys.last()
            }
        } else {
            currentStack.removeLast()
        }
        updateBackStack()
    }

    private fun clearDetailEntriesFromOtherTabs() {
        topLevelStacks.forEach { (route, stack) ->
            if (route != topLevelKey && stack.size > 1) {
                val root = stack.first()
                stack.clear()
                stack.add(root)
            }
        }
    }
}
