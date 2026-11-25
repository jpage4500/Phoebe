package com.phoebe.app.player

import com.phoebe.app.domain.Track
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
