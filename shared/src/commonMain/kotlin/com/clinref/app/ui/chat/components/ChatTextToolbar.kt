package com.clinref.app.ui.chat.components

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * List-scoped [TextContextMenuProvider] for read-only assistant messages.
 *
 * - Works around Compose Foundation b/519816995: returns empty menu data when
 *   [TextContextMenuKeys.CopyKey] is absent (e.g. during tap-outside disposal),
 *   preventing the transient "Select all"-only toolbar flash.
 * - Exposes [dismiss] to cancel the active toolbar immediately on scroll/overlay/dispose.
 */
class ReadOnlyChatContextMenuProvider(
    private val delegate: TextContextMenuProvider
) : TextContextMenuProvider {

    private var activeSessionJob: Job? = null

    fun dismiss() {
        activeSessionJob?.cancel()
        activeSessionJob = null
    }

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val guardedProvider = object : TextContextMenuDataProvider by dataProvider {
            override fun data(): TextContextMenuData {
                val raw = dataProvider.data()
                val hasCopy = raw.components.any { it.key == TextContextMenuKeys.CopyKey }
                return if (hasCopy) raw else TextContextMenuData(emptyList())
            }
        }

        coroutineScope {
            val job = launch {
                delegate.showTextContextMenu(guardedProvider)
            }
            activeSessionJob = job
            try {
                job.join()
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            } finally {
                if (activeSessionJob === job) {
                    activeSessionJob = null
                }
            }
        }
    }
}

@Composable
fun rememberReadOnlyChatContextMenuProvider(): ReadOnlyChatContextMenuProvider? {
    val platformProvider = LocalTextContextMenuToolbarProvider.current ?: return null
    val provider = remember(platformProvider) {
        if (platformProvider is ReadOnlyChatContextMenuProvider) {
            platformProvider
        } else {
            ReadOnlyChatContextMenuProvider(platformProvider)
        }
    }
    DisposableEffect(provider) {
        onDispose { provider.dismiss() }
    }
    return provider
}
