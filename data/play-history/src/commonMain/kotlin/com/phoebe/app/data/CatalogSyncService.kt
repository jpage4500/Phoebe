package com.phoebe.app.data

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.platform.PhoebeLog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class CatalogSyncService(
    private val catalogRepository: CatalogRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val plexPlayHistorySyncer: PlexPlayHistorySyncer,
    private val jellyfinPlayHistorySyncer: JellyfinPlayHistorySyncer,
    private val navidromePlayHistorySyncer: NavidromePlayHistorySyncer,
    private val libraryChangeDetector: LibraryChangeDetector,
) {
    /**
     * Rebuilds the catalog from the server. A background refresh over a cached catalog (the
     * launch refresh) first asks the server whether its library changed since the last completed
     * sync; if not, it runs only the lightweight sync (stars, playlists, first page) instead.
     * A manual refresh always does the full work.
     */
    suspend fun refreshAggregatedCatalog(
        session: PlexSession?,
        backgroundIfCached: Boolean,
    ) {
        val signal = session?.takeIf { it.selectedServer != null && it.selectedLibrary != null }
            ?.let { libraryChangeDetector.currentSignal(it) }
        if (session != null && signal != null && backgroundIfCached &&
            catalogRepository.canSkipUnchangedRemoteRefresh(session) &&
            libraryChangeDetector.isUnchangedSinceLastSync(session, signal)
        ) {
            PhoebeLog.d("CatalogSyncService") {
                "library unchanged since last sync (${signal.fingerprint}); running lightweight sync only"
            }
            catalogRepository.syncLightweightRemoteState(session)
            ensureLikedSongsPlaylistIfPossible(session)
            return
        }
        catalogRepository.refreshAggregated(session, backgroundIfCached = backgroundIfCached)
        // Saved after the refresh, with the signal read before it, so a change made mid-sync
        // still shows up as a change next time.
        if (session != null && signal != null) libraryChangeDetector.saveSyncedSignal(session, signal)
        ensureLikedSongsPlaylistIfPossible(session)
    }

    /** Makes the next sync download everything, e.g. when switching to Full sync. */
    suspend fun forgetSavedSyncState(session: PlexSession?) {
        if (session == null) return
        libraryChangeDetector.forget(session)
        catalogRepository.forgetAlbumFingerprints(session)
    }

    suspend fun warmPlaylistTracks(session: PlexSession?) {
        if (!session.supportsRemotePlaylists()) return
        catalogRepository.warmPlaylistTracks(session)
    }

    suspend fun cacheDownloadedArtwork(): Int =
        catalogRepository.cacheDownloadedArtwork()

    suspend fun ensureLikedSongsPlaylistIfPossible(session: PlexSession?): Playlist? {
        if (!session.supportsRemotePlaylists()) return null
        return runCatching {
            catalogRepository.ensureLocalLikedSongsPlaylist(session)
        }.onFailure { error ->
            PhoebeLog.d("CatalogSyncService") { "Liked Songs setup failed: ${error.message}" }
        }.getOrNull()
    }

    suspend fun syncLightweightRemoteState(session: PlexSession?) {
        if (session?.selectedLibrary == null || session.selectedServer == null) return
        catalogRepository.syncLightweightRemoteState(session)
        ensureLikedSongsPlaylistIfPossible(session)
    }

    suspend fun syncRemotePlayHistory(
        session: PlexSession?,
        catalog: CatalogSnapshot,
        recentOnly: Boolean,
        reportProgress: Boolean = false,
    ): Any? {
        if (session.isPlex()) {
            if (!recentOnly) {
                runCatching {
                    catalogRepository.warmPlexHistoryTracks(session)
                }.onFailure { error ->
                    PhoebeLog.d("CatalogSyncService") { "Plex history track warm failed: ${error.message}" }
                }
            }
            return if (recentOnly) {
                plexPlayHistorySyncer.syncRecent(session, catalog)
            } else {
                plexPlayHistorySyncer.sync(session, catalog)
            }
        }
        if (session.isNavidrome()) {
            if (!reportProgress) return navidromePlayHistorySyncer.sync(session, catalog)
            return try {
                navidromePlayHistorySyncer.sync(session, catalog) { loaded, total ->
                    catalogRepository.reportPlayHistorySyncProgress(loaded, total)
                }
            } finally {
                catalogRepository.finishPlayHistorySyncProgress()
            }
        }
        if (session.isEmbyFamily()) {
            return jellyfinPlayHistorySyncer.sync(session, catalog)
        }
        return null
    }

    suspend fun warmTracksForMostPlayed(
        session: PlexSession?,
        entries: List<MostPlayedEntry>,
        maxTracks: Int,
    ) {
        catalogRepository.warmTracksForMostPlayed(session, entries, maxTracks)
    }

    suspend fun topPlayHistoryTrackIds(limit: Int): List<String> =
        buildList {
            addAll(playHistoryRepository.queryTopMostPlayed(limit).map { it.trackId })
            addAll(playHistoryRepository.queryTopRecentlyPlayed(limit).map { it.trackId })
        }.distinct()

    suspend fun refreshPlexViewCountsForTrackIds(
        session: PlexSession?,
        trackIds: List<String>,
    ) {
        plexPlayHistorySyncer.refreshViewCountsForTrackIds(session, trackIds)
    }
}
