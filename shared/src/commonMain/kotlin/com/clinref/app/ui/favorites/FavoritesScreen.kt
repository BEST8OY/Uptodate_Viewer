package com.clinref.app.ui.favorites

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.clinref.app.ui.common.TopicCollectionEntry
import com.clinref.app.ui.common.TopicCollectionScreen
import com.clinref.app.ui.common.TopicCollectionStrings
import com.clinref.app.ui.navigation.FavoritesRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.compose.viewmodel.koinViewModel

private val FavoritesStrings = TopicCollectionStrings(
    title = "Favorites",
    subtitle = { count -> "$count saved articles" },
    clearAllDialogTitle = "Clear all favorites?",
    clearAllDialogText = "This will remove all items from your favorites.",
    clearAllActionDescription = "Clear all favorites",
    emptyStateText = "No favorites yet",
    emptyStateIcon = Icons.Default.FavoriteBorder,
    itemLeadingIcon = Icons.Default.Favorite,
    removedSnackbarMessage = { count -> if (count == 1) "Favorite removed" else "$count favorites removed" },
    removeAccessibilityLabel = "Remove from favorites",
)

@Composable
fun FavoritesScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    reselectEvents: Flow<NavKey> = emptyFlow(),
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val entries = remember(favorites) {
        favorites.map { TopicCollectionEntry(it.topicId, it.title, it.timestamp) }
    }

    TopicCollectionScreen(
        entries = entries,
        strings = FavoritesStrings,
        onTopicSelected = onTopicSelected,
        onGraphicSelected = onGraphicSelected,
        onRemoveEntry = { viewModel.removeFavorite(it) },
        onClearAll = { viewModel.clearFavorites() },
        onRestoreEntries = { restored ->
            restored.forEach { viewModel.addFavorite(it.topicId, it.title, it.timestamp) }
        },
        reselectKey = FavoritesRoute,
        modifier = modifier,
        reselectEvents = reselectEvents,
    )
}
