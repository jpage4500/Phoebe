package com.phoebe.app.player

import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import kotlin.concurrent.Volatile

/**
 * Looks up the on-device file of a completed download, so a downloaded track plays from disk
 * even when the [Track] handed to the player has no [Track.localUri] (a row rewritten by a
 * server refresh, a queue restored from elsewhere). Without it the player streams instead and
 * fails as soon as the device is offline. Wired to the download table by the app at startup.
 */
fun interface DownloadedLocalUriLookup {
    fun localUriFor(trackId: String): String?
}

object DownloadedLocalUriHolder {
    @Volatile
    var lookup: DownloadedLocalUriLookup? = null
}

fun Track.withDownloadedLocalUri(): Track {
    if (!localUri.isNullOrBlank()) return this
    val lookup = DownloadedLocalUriHolder.lookup ?: return this
    val uri = lookup.localUriFor(id)?.takeIf { it.isNotBlank() } ?: return this
    return copy(localUri = uri)
}

fun List<Track>.withDownloadedLocalUris(): List<Track> {
    if (DownloadedLocalUriHolder.lookup == null || isEmpty()) return this
    var changed = false
    val filled = map { track ->
        track.withDownloadedLocalUri().also { if (it !== track) changed = true }
    }
    return if (changed) filled else this
}

/**
 * Queue index of the next song after [currentIndex] that plays from a file on this device,
 * in play order (a shuffled queue is already stored in play order). Wraps to the start only
 * when the whole queue repeats. Null when every remaining song would have to stream.
 */
internal fun nextDownloadedQueueIndex(queue: List<Track>, currentIndex: Int, repeat: RepeatMode): Int? {
    if (currentIndex !in queue.indices) return null
    val ahead = (currentIndex + 1..queue.lastIndex).asSequence()
    val wrapped = if (repeat == RepeatMode.All) (0 until currentIndex).asSequence() else emptySequence()
    return (ahead + wrapped).firstOrNull { queue[it].isLocalMediaPlayback() }
}
