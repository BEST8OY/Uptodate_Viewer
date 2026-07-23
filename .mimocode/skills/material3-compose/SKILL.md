---
name: material3-compose
description: Material 3 Compose patterns for this project - SegmentedListItem, SwipeToDismissBox, and selection mode conventions
---

# Material 3 Compose Patterns

## SegmentedListItem

### Selection Mode (Standard Pattern)
Use `SegmentedListItem(checked = ...)` for multi-select scenarios:

```kotlin
if (isSelectionMode) {
    SegmentedListItem(
        checked = isSelected,
        onCheckedChange = { onItemSelected() },
        onLongClick = { onLongPress() },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
        colors = if (isSelected) {
            ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        } else {
            ListItemDefaults.segmentedColors()
        },
        leadingContent = {
            Checkbox(checked = isSelected, onCheckedChange = null)
        },
        content = { Text(text = title) }
    )
} else {
    // Normal mode uses onClick variant
}
```

### Normal Mode
Use `SegmentedListItem(onClick = ...)` for standard click behavior:

```kotlin
SegmentedListItem(
    selected = isHighlighted,
    onClick = { onItemClick() },
    onLongClick = { onLongPress() },
    shapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
    colors = ListItemDefaults.segmentedColors(),
    leadingContent = { /* icon or avatar */ },
    trailingContent = { /* timestamp, badges */ },
    supportingContent = { /* subtitle */ },
    content = { Text(text = title) }
)
```

### Key Rules
1. **Always use `ListItemDefaults.segmentedShapes(index, count)`** for corner radii
2. **Selection mode**: Use `checked` variant with `Checkbox` in `leadingContent`
3. **Normal mode**: Use `onClick` variant with custom `leadingContent`
4. **Selected colors**: Apply via `ListItemDefaults.segmentedColors(containerColor = ...)`

## SwipeToDismissBox

### Standard Background Pattern
```kotlin
SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = !isSelectionMode,
    enableDismissFromEndToStart = !isSelectionMode,
    backgroundContent = {
        val color by animateColorAsState(
            targetValue = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Transparent
            },
            label = "swipeBg"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    },
    content = { /* list item content */ }
)
```

### Key Rules
1. **Padding on Box**, not on background modifier
2. **Use `animateColorAsState`** for smooth color transitions
3. **Disable gestures** during selection mode: `gesturesEnabled = !isSelectionMode`
4. **Use `onDismiss`** callback to handle the dismiss action

## LazyColumn Pattern
```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 80.dp) // Account for nav bar
) {
    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
        // Use SegmentedListItem with itemShapes
    }
}
```

## Consistency
All list screens (History, Favorites, Conversations) should use the same patterns for consistency.
