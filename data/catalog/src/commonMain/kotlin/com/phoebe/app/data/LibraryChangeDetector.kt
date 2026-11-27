package com.phoebe.app.data

import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import dev.zacsweers.metro.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a server said about its library just before a sync started. Saved once the sync
 * completes, and compared on the next launch to skip the full download if nothing changed.
 */
data class LibraryChangeSignal(
    val fingerprint: String,
    val checkedAtMs: Long,
)

/**
 * Asks the server one cheap question before a launch refresh: has the library changed since
 * the last completed sync?
 * - Subsonic (Navidrome): getScanStatus last scan time and file counts.
 * - Plex: the library section's content-changed / scanned / updated times.
 * - Jellyfin / Emby: the song + album count, plus how many were saved since the last sync.
 *
 * This only covers the library itself. Stars, ratings and playlists aren't part of it, so a
 * skipped refresh still runs the lightweight sync for those.
 */
@Inject
class LibraryChangeDetector(
    private val plexClient: PlexClient,
    private val subsonicClient: SubsonicClient,
    private val jellyfinClient: JellyfinClient,
    private val embyClient: EmbyClient,
    private val store: RemoteSyncStateStore,
) {
    /** The server's current signal, or null when it has no cheap one or the request failed. */
    suspend fun currentSignal(session: PlexSession): LibraryChangeSignal? {
        val checkedAtMs = currentTimeMs()
        val fingerprint = try {
            fingerprint(session)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            PhoebeLog.d(Tag) { "library change check failed: ${error.message}" }
            null
        } ?: return null
        return LibraryChangeSignal("mode=${session.jellyfinSyncMode.name};$fingerprint", checkedAtMs)
    }

    /** True only when [signal] matches the one saved by the last completed sync. */
    suspend fun isUnchangedSinceLastSync(session: PlexSession, signal: LibraryChangeSignal): Boolean {
        val scope = RemoteSyncStateStore.libraryScope(session) ?: return false
        val saved = store.values(scope)
        if (saved[FingerprintKey] != signal.fingerprint) return false
        if (!session.isEmbyFamily()) return true
        // Jellyfin's count only catches added or removed items; edits need a "saved since" query.
        val lastSyncMs = saved[CheckedAtKey]?.toLongOrNull() ?: return false
        val changed = try {
            embyFamilyItemCount(session, savedSinceMs = (lastSyncMs - ClockSkewMarginMs).coerceAtLeast(0L))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            PhoebeLog.d(Tag) { "library changed-since check failed: ${error.message}" }
            null
        }
        return changed == 0
    }

    suspend fun saveSyncedSignal(session: PlexSession, signal: LibraryChangeSignal) {
        val scope = RemoteSyncStateStore.libraryScope(session) ?: return
        store.put(
            scope,
            mapOf(
                FingerprintKey to signal.fingerprint,
                CheckedAtKey to signal.checkedAtMs.toString(),
            ),
        )
    }

    /** Makes the next launch refresh download everything, e.g. after the sync mode changes. */
    suspend fun forget(session: PlexSession) {
        val scope = RemoteSyncStateStore.libraryScope(session) ?: return
        store.clearScope(scope)
    }

    private suspend fun fingerprint(session: PlexSession): String? {
        val server = session.selectedServer ?: return null
        val library = session.selectedLibrary ?: return null
        return when {
            session.isNavidrome() -> {
                if (session.userName.isBlank() || session.token.isBlank()) return null
                subsonicClient.libraryScanFingerprint(server, session.userName, session.token)
            }
            session.isPlex() -> {
                val token = session.serverAuthToken() ?: return null
                plexClient.librarySectionFingerprint(server, token, library)
            }
            session.isEmbyFamily() -> embyFamilyItemCount(session)?.let { "items=$it" }
            else -> null
        }
    }

    private suspend fun embyFamilyItemCount(session: PlexSession, savedSinceMs: Long? = null): Int? {
        val server = session.selectedServer ?: return null
        val library = session.selectedLibrary ?: return null
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: return null
        val token = session.token.takeIf { it.isNotBlank() } ?: return null
        val client = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
        return client.libraryItemCount(server, library, token, userId, savedSinceMs)
    }

    private companion object {
        const val Tag = "LibraryChangeDetector"
        const val FingerprintKey = "fingerprint"
        const val CheckedAtKey = "checkedAtMs"

        /** Jellyfin compares against the server's clock; allow for the device's clock running ahead. */
        const val ClockSkewMarginMs = 60L * 60L * 1000L
    }
}
