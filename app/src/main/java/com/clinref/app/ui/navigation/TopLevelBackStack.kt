package com.clinref.app.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

class TopLevelBackStack<T : Any>(startKey: T) {

    private var topLevelStacks: LinkedHashMap<T, SnapshotStateList<T>> = linkedMapOf(
        startKey to mutableStateListOf(startKey)
    )

    var topLevelKey by mutableStateOf(startKey)
        private set

    val backStack = mutableStateListOf(startKey)

    private fun updateBackStack() =
        backStack.apply {
            clear()
            val startKey = topLevelStacks.keys.first()
            addAll(topLevelStacks[startKey].orEmpty())
            if (topLevelKey != startKey) {
                addAll(topLevelStacks[topLevelKey].orEmpty())
            }
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
                topLevelKey = topLevelStacks.keys.first()
            }
        } else {
            currentStack.removeLast()
        }
        updateBackStack()
    }
}
