package com.phoebe.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Horizontal gap between library grid cells (albums and artists). */
val LibraryGridHorizontalSpacing: Dp = 10.dp

private const val LibraryGridWidthBucketDp = 64f

/** Snaps a measured grid width to a coarse bucket so tiny resizes don't reflow the column count. */
fun bucketedLibraryGridWidth(width: Dp): Dp {
    val bucket = (width.value / LibraryGridWidthBucketDp).roundToInt().coerceAtLeast(1)
    return (bucket * LibraryGridWidthBucketDp).dp
}

fun libraryAdaptiveColumnCount(
    availableWidth: Dp,
    itemSizeDp: Int,
    gap: Dp = LibraryGridHorizontalSpacing,
): Int {
    val itemWidth = itemSizeDp.dp
    if (availableWidth <= 0.dp) return 1
    return ((availableWidth + gap) / (itemWidth + gap)).toInt().coerceAtLeast(1)
}

/**
 * Last settled width of the library artist/album grid, so settings can show how many columns a
 * given artwork size produces. Null until the library grid has been shown this session.
 */
object LibraryGridWidthTracker {
    var lastWidth: Dp? by mutableStateOf(null)
}
