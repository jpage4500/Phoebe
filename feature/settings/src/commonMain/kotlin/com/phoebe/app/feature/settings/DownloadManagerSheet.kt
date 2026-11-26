package com.phoebe.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadMoveState
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.PillTextField
import com.phoebe.app.ui.TrackArtworkImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which slice of the download manager a [DownloadManagerSheet] shows. */
internal enum class DownloadSheetKind(val title: String) {
    Active("Downloading"),
    Downloaded("Downloaded"),
    Failed("Failed"),
    ;

    fun includes(item: DownloadItem): Boolean = when (this) {
        Active -> item.state == DownloadState.Queued || item.state == DownloadState.Downloading
        Downloaded -> item.state == DownloadState.Complete
        Failed -> item.state == DownloadState.Failed
    }
}

/**
 * App services the Downloads page needs beyond its route state: artwork lookup, size backfill, and moving
 * songs after the download folder changes. Provided by the app root; the defaults do nothing.
 */
class DownloadManagerServices(
    val lookupTracks: suspend (Set<String>) -> Map<String, Track> = { emptyMap() },
    val backfillSizes: suspend () -> Unit = {},
    val moveState: StateFlow<DownloadMoveState?> = MutableStateFlow(null),
    val onMoveConfirm: () -> Unit = {},
    val onMoveStop: () -> Unit = {},
    val onMoveDismiss: () -> Unit = {},
)

val LocalDownloadManagerServices = staticCompositionLocalOf { DownloadManagerServices() }

/** Alert red shared with the settings hub's failure badge. */
internal val DownloadFailedTint = Color(0xFFEF4444)

private const val UnknownFailureReason = "Unknown error"

/**
 * Large sheet listing one download status. Downloaded supports search and multi-select delete,
 * Failed is grouped by error with multi-select retry/remove, and Active shows progress with cancel.
 */
@Composable
internal fun DownloadManagerSheet(
    kind: DownloadSheetKind,
    downloadItems: List<DownloadItem>,
    onDismiss: () -> Unit,
    onRetryDownloads: (Set<String>) -> Unit,
    onRetryAllFailed: () -> Unit,
    onClearAllFailed: () -> Unit,
    onCancelDownloads: (Set<String>) -> Unit,
    onDeleteDownloads: (Set<String>) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val desktop = remember { isDesktopPlatform() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val radius = PhoebeUi.shapes.sheetTopRadius
        val shape = if (desktop) RoundedCornerShape(radius) else RoundedCornerShape(topStart = radius, topEnd = radius)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = if (desktop) Alignment.Center else Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(if (desktop) 0.85f else 0.94f)
                    .clip(shape)
                    .background(PhoebeUi.modalSurface)
                    .border(BorderStroke(1.dp, PhoebeUi.border), shape)
                    // Swallow taps so they don't reach the dismiss scrim behind the sheet.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                if (!desktop) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PhoebeUi.progressTrack),
                    )
                }
                val items = remember(downloadItems, kind) {
                    downloadItems
                        .filter(kind::includes)
                        .sortedWith(
                            compareBy<DownloadItem> { if (it.state == DownloadState.Downloading) 0 else 1 }
                                .thenByDescending { it.updatedAtMs }
                                .thenBy { it.title.lowercase() },
                        )
                }
                when (kind) {
                    DownloadSheetKind.Downloaded -> DownloadedSheetContent(
                        items = items,
                        onDismiss = onDismiss,
                        onDeleteDownloads = onDeleteDownloads,
                        onDeleteAll = onDeleteAll,
                    )
                    DownloadSheetKind.Failed -> FailedSheetContent(
                        items = items,
                        onDismiss = onDismiss,
                        onRetryDownloads = onRetryDownloads,
                        onRetryAllFailed = onRetryAllFailed,
                        onRemoveDownloads = onDeleteDownloads,
                        onClearAllFailed = onClearAllFailed,
                    )
                    DownloadSheetKind.Active -> ActiveSheetContent(
                        items = items,
                        onDismiss = onDismiss,
                        onCancelDownloads = onCancelDownloads,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DownloadedSheetContent(
    items: List<DownloadItem>,
    onDismiss: () -> Unit,
    onDeleteDownloads: (Set<String>) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lookupTracks = LocalDownloadManagerServices.current.lookupTracks
    var tracksById by remember { mutableStateOf(emptyMap<String, Track>()) }
    val itemIds = remember(items) { items.mapTo(HashSet()) { it.trackId } }
    LaunchedEffect(itemIds) {
        val missing = itemIds - tracksById.keys
        if (missing.isNotEmpty()) tracksById = tracksById + lookupTracks(missing)
    }
    val visible = remember(items, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            items
        } else {
            items.filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }
        }
    }
    // Drop selections for songs that were deleted or disappeared while the sheet was open.
    val liveSelected = remember(selected, items) { selected.intersect(items.mapTo(HashSet()) { it.trackId }) }
    val visibleIds = visible.map { it.trackId }
    val allVisibleSelected = visibleIds.isNotEmpty() && liveSelected.containsAll(visibleIds)
    val totalBytes = items.sumOf { it.sizeBytes() }

    if (selecting) {
        SheetHeader(
            title = if (liveSelected.isEmpty()) "Select songs" else "${liveSelected.size} selected",
            subtitle = null,
            leadingClose = true,
            actionLabel = if (allVisibleSelected) "Clear" else "Select all",
            actionEnabled = visibleIds.isNotEmpty(),
            onAction = { selected = if (allVisibleSelected) liveSelected - visibleIds.toSet() else liveSelected + visibleIds },
            onClose = {
                selecting = false
                selected = emptySet()
            },
        )
    } else {
        SheetHeader(
            title = DownloadSheetKind.Downloaded.title,
            subtitle = downloadCountLabel(items.size, totalBytes),
            actionLabel = "Select",
            actionEnabled = items.isNotEmpty(),
            onAction = { selecting = true },
            onClose = onDismiss,
        )
    }
    if (items.isNotEmpty()) {
        PillTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search downloads",
            contentDescription = "Search downloads",
            leadingIcon = PhoebeIcon.Search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            items.isEmpty() -> SheetEmptyState("No downloaded songs yet")
            visible.isEmpty() -> SheetEmptyState("No downloads match \"${query.trim()}\"")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.trackId }) { item ->
                    val checked = item.trackId in liveSelected
                    DownloadSheetRow(
                        item = item,
                        track = tracksById[item.trackId],
                        subtitle = listOfNotNull(
                            item.artist.takeIf { it.isNotBlank() },
                            item.sizeBytes().takeIf { it > 0L }?.let(::formatBytes),
                        ).joinToString(" · "),
                        selecting = selecting,
                        checked = checked,
                        onClick = {
                            if (selecting) selected = if (checked) liveSelected - item.trackId else liveSelected + item.trackId
                        },
                        onLongClick = {
                            selecting = true
                            selected = liveSelected + item.trackId
                        },
                        trailing = if (selecting) {
                            null
                        } else {
                            { DownloadRowMenu(onDelete = { onDeleteDownloads(setOf(item.trackId)) }) }
                        },
                    )
                }
            }
        }
    }
    SheetFooter {
        if (selecting) {
            val freed = items.filter { it.trackId in liveSelected }.sumOf { it.sizeBytes() }
            SheetButton(
                label = when (liveSelected.size) {
                    0 -> "Delete songs"
                    1 -> "Delete 1 song"
                    else -> "Delete ${liveSelected.size} songs"
                },
                icon = null,
                style = SheetButtonStyle.Danger,
                enabled = liveSelected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { confirmDelete = true },
            )
            Text(
                if (liveSelected.isEmpty()) {
                    "Tap songs to select them"
                } else {
                    listOfNotNull(
                        freed.takeIf { it > 0L }?.let { "Frees ${formatBytes(it)}" },
                        "Songs stay in your library",
                    ).joinToString(" · ")
                },
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            SheetButton(
                label = "Delete all downloads",
                icon = null,
                style = SheetButtonStyle.DangerText,
                enabled = items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDeleteAll,
            )
        }
    }
    if (confirmDelete && liveSelected.isNotEmpty()) {
        val count = liveSelected.size
        ConfirmDeleteDialog(
            title = if (count == 1) "Delete 1 download?" else "Delete $count downloads?",
            message = "This removes the offline ${if (count == 1) "file" else "files"} from this device. " +
                "The ${if (count == 1) "song stays" else "songs stay"} in your library and can be downloaded again.",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDeleteDownloads(liveSelected)
                selected = emptySet()
                selecting = false
            },
        )
    }
}

@Composable
private fun ColumnScope.FailedSheetContent(
    items: List<DownloadItem>,
    onDismiss: () -> Unit,
    onRetryDownloads: (Set<String>) -> Unit,
    onRetryAllFailed: () -> Unit,
    onRemoveDownloads: (Set<String>) -> Unit,
    onClearAllFailed: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val groups = remember(items) {
        items
            .groupBy { it.failureReason() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<DownloadItem>>> { it.value.size }.thenBy { it.key })
            .map { it.key to it.value }
    }
    val allIds = remember(items) { items.mapTo(LinkedHashSet()) { it.trackId } }
    val liveSelected = remember(selected, allIds) { selected.intersect(allIds) }
    val allSelected = allIds.isNotEmpty() && liveSelected.size == allIds.size

    SheetHeader(
        title = DownloadSheetKind.Failed.title,
        subtitle = when {
            items.isEmpty() -> null
            liveSelected.isEmpty() -> "${songCount(items.size)} · grouped by reason"
            else -> "${liveSelected.size} of ${items.size} selected"
        },
        actionLabel = if (allSelected) "Clear" else "Select all",
        actionEnabled = items.isNotEmpty(),
        onAction = { selected = if (allSelected) emptySet() else allIds },
        onClose = onDismiss,
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (items.isEmpty()) {
            SheetEmptyState("No failed downloads")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (reason, groupItems) ->
                    val groupIds = groupItems.map { it.trackId }
                    val groupChecked = liveSelected.containsAll(groupIds)
                    item(key = "group:$reason") {
                        FailureGroupHeader(
                            reason = reason,
                            count = groupItems.size,
                            checked = groupChecked,
                            onToggle = {
                                selected = if (groupChecked) liveSelected - groupIds.toSet() else liveSelected + groupIds
                            },
                        )
                    }
                    items(groupItems, key = { it.trackId }) { item ->
                        val checked = item.trackId in liveSelected
                        DownloadSheetRow(
                            item = item,
                            subtitle = item.artist,
                            selecting = true,
                            checked = checked,
                            onClick = { selected = if (checked) liveSelected - item.trackId else liveSelected + item.trackId },
                            onLongClick = null,
                            trailing = null,
                        )
                    }
                }
            }
        }
    }
    SheetFooter {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(
                label = if (liveSelected.isEmpty()) "Remove all" else "Remove ${liveSelected.size}",
                icon = null,
                style = SheetButtonStyle.Outline,
                enabled = items.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (liveSelected.isEmpty()) onClearAllFailed() else onRemoveDownloads(liveSelected)
                    selected = emptySet()
                },
            )
            SheetButton(
                label = if (liveSelected.isEmpty()) "Retry all" else "Retry ${liveSelected.size}",
                icon = PhoebeIcon.Repeat,
                style = SheetButtonStyle.Primary,
                enabled = items.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (liveSelected.isEmpty()) {
                        onRetryAllFailed()
                    } else {
                        val retryable = items.filter { it.trackId in liveSelected && it.downloadUrl.isNotBlank() }
                            .mapTo(LinkedHashSet()) { it.trackId }
                        if (retryable.isNotEmpty()) onRetryDownloads(retryable)
                    }
                    selected = emptySet()
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.ActiveSheetContent(
    items: List<DownloadItem>,
    onDismiss: () -> Unit,
    onCancelDownloads: (Set<String>) -> Unit,
) {
    val downloading = items.count { it.state == DownloadState.Downloading }
    val waiting = items.size - downloading
    SheetHeader(
        title = DownloadSheetKind.Active.title,
        subtitle = if (items.isEmpty()) null else "$downloading active · $waiting waiting",
        actionLabel = null,
        actionEnabled = false,
        onAction = {},
        onClose = onDismiss,
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (items.isEmpty()) {
            SheetEmptyState("Nothing is downloading right now")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.trackId }) { item ->
                    ActiveDownloadRow(item = item, onCancel = { onCancelDownloads(setOf(item.trackId)) })
                }
            }
        }
    }
    SheetFooter {
        SheetButton(
            label = "Cancel all",
            icon = null,
            style = SheetButtonStyle.Outline,
            enabled = items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onCancelDownloads(items.mapTo(LinkedHashSet()) { it.trackId }) },
        )
    }
}

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String?,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onClose: () -> Unit,
    leadingClose: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (leadingClose) 6.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingClose) SheetIconButton(PhoebeIcon.Close, "Exit selection", PhoebeUi.primaryText, onClose)
        Column(Modifier.weight(1f)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (actionLabel != null) {
            Text(
                actionLabel,
                color = if (actionEnabled) PhoebeUi.accentLight else PhoebeUi.mutedText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        if (!leadingClose) SheetIconButton(PhoebeIcon.Close, "Close", PhoebeUi.secondaryText, onClose)
    }
}

@Composable
private fun SheetIconButton(icon: PhoebeIcon, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadSheetRow(
    item: DownloadItem,
    subtitle: String,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    track: Track? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (checked) PhoebeUi.accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 56.dp)
            .padding(start = if (selecting) 8.dp else 16.dp, end = if (trailing != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selecting) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                colors = sheetCheckboxColors(),
            )
        } else {
            if (track != null) {
                TrackArtworkImage(track = track, modifier = Modifier.size(40.dp), radius = 6.dp, elevated = false)
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PhoebeUi.elevatedFill),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(PhoebeIcon.Music, tint = PhoebeUi.secondaryText, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                item.title,
                color = PhoebeUi.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun DownloadRowMenu(onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SheetIconButton(PhoebeIcon.More, "More options", PhoebeUi.secondaryText) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete download") },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun FailureGroupHeader(
    reason: String,
    count: Int,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PhoebeUi.subtleFill)
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = sheetCheckboxColors())
        Text(
            reason,
            color = DownloadFailedTint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), color = PhoebeUi.secondaryText, fontSize = 13.sp)
    }
}

@Composable
private fun ActiveDownloadRow(item: DownloadItem, onCancel: () -> Unit) {
    val downloading = item.state == DownloadState.Downloading
    val progress = item.progress.coerceIn(0f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (downloading) "${(progress * 100).toInt()}%" else "Waiting",
                    color = if (downloading) PhoebeUi.accentLight else PhoebeUi.mutedText,
                    fontSize = 12.sp,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(PhoebeUi.progressTrack),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (downloading) progress else 0f)
                        .height(3.dp)
                        .background(PhoebeUi.accentLight),
                )
            }
            val detail = listOfNotNull(
                item.artist.takeIf { it.isNotBlank() },
                item.totalBytes?.takeIf { it > 0L && downloading }?.let { "${formatBytes(item.downloadedBytes)} / ${formatBytes(it)}" },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SheetIconButton(PhoebeIcon.Close, "Cancel ${item.title}", PhoebeUi.secondaryText, onCancel)
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogShape = RoundedCornerShape(PhoebeUi.shapes.sheetTopRadius)
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = 300.dp, max = 420.dp)
                .clip(dialogShape)
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.18f)), dialogShape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(message, color = PhoebeUi.secondaryText, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PhoebeUi.secondaryText)
                }
                TextButton(onClick = onConfirm) {
                    Text("Delete", color = DownloadFailedTint, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetEmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PhoebeUi.mutedText, fontSize = 14.sp)
    }
}

@Composable
private fun SheetFooter(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PhoebeUi.border))
    Column(
        Modifier
            .fillMaxWidth()
            .background(PhoebeUi.panel)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
    ) {
        content()
    }
}

internal enum class SheetButtonStyle { Primary, Outline, Danger, DangerText }

@Composable
internal fun SheetButton(
    label: String,
    icon: PhoebeIcon?,
    style: SheetButtonStyle,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        !enabled && (style == SheetButtonStyle.Primary || style == SheetButtonStyle.Danger) -> PhoebeUi.progressTrack
        style == SheetButtonStyle.Primary -> PhoebeUi.accent
        style == SheetButtonStyle.Danger -> DownloadFailedTint
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> PhoebeUi.mutedText
        style == SheetButtonStyle.Primary || style == SheetButtonStyle.Danger -> Color.White
        style == SheetButtonStyle.DangerText -> DownloadFailedTint
        else -> PhoebeUi.primaryText
    }
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(background)
            .then(if (style == SheetButtonStyle.Outline) Modifier.border(BorderStroke(1.dp, PhoebeUi.border), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PhoebeIconView(icon, tint = content, modifier = Modifier.size(16.dp))
        Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun sheetCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = PhoebeUi.accentLight,
    uncheckedColor = PhoebeUi.secondaryText,
    checkmarkColor = Color.White,
)

private fun DownloadItem.sizeBytes(): Long =
    downloadedBytes.takeIf { it > 0L } ?: totalBytes?.takeIf { it > 0L } ?: 0L

private fun DownloadItem.failureReason(): String =
    error?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: UnknownFailureReason

private fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun downloadCountLabel(count: Int, bytes: Long): String =
    listOfNotNull(songCount(count), bytes.takeIf { it > 0L }?.let(::formatBytes)).joinToString(" · ")

/** Asks whether to move songs into the new download folder, then shows progress and the result. */
@Composable
internal fun DownloadMoveDialog(
    state: DownloadMoveState,
    folderName: String,
    onMove: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moving = state is DownloadMoveState.Moving
    Dialog(
        onDismissRequest = { if (!moving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !moving,
            dismissOnClickOutside = !moving,
        ),
    ) {
        val dialogShape = RoundedCornerShape(PhoebeUi.shapes.sheetTopRadius)
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = 300.dp, max = 420.dp)
                .clip(dialogShape)
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.18f)), dialogShape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state) {
                is DownloadMoveState.Prompt -> {
                    Text("Move downloaded songs?", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${songCount(state.count)} ${if (state.count == 1) "is" else "are"} still in your previous folder. " +
                            "Move ${if (state.count == 1) "it" else "them"} to $folderName? " +
                            "If you keep them where they are, they still play offline.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) {
                            Text("Keep where they are", color = PhoebeUi.secondaryText)
                        }
                        TextButton(onClick = onMove) {
                            Text("Move", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                is DownloadMoveState.Moving -> {
                    Text("Moving songs…", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(PhoebeUi.progressTrack),
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(PhoebeUi.accentLight))
                    }
                    Text(
                        "${state.done} of ${state.total}" + if (state.failed > 0) " · ${state.failed} couldn't be moved" else "",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onStop) {
                            Text("Stop", color = PhoebeUi.secondaryText)
                        }
                    }
                }
                is DownloadMoveState.Finished -> {
                    Text(
                        if (state.stopped) "Move stopped" else "Songs moved",
                        color = PhoebeUi.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            append("Moved ${songCount(state.moved)} to $folderName.")
                            if (state.failed > 0) {
                                append(" ${songCount(state.failed)} couldn't be moved and stayed in the old folder.")
                            }
                            if (state.stopped) append(" Songs not moved yet stay in the old folder.")
                        },
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text("Done", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
