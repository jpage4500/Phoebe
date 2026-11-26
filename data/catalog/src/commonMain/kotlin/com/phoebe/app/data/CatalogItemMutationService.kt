package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.domain.providerLabel
import com.phoebe.app.domain.supportsRemoteRatings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class CatalogItemMutationService(
    private val catalogRepository: CatalogRepository,
    private val radioRepository: RadioRepository,
) {
    suspend fun updateTrackMetadata(session: PlexSession?, update: TrackMetadataUpdate): String {
        val result = catalogRepository.updateTrackMetadata(session, update)
        val provider = session.providerLabel()
        return when {
            !result.savedLocally -> "Couldn't find that song in the library."
            result.plexAttempted && result.plexSynced -> "Metadata saved and synced to $provider."
            result.plexAttempted -> "Metadata saved locally, but $provider sync failed."
            else -> "Metadata saved."
        }
    }

    suspend fun rateTrack(session: PlexSession?, track: Track, rating: Float?): String =
        ratingMessage(session, catalogRepository.rateTrack(session, track, rating))

    suspend fun rateArtist(session: PlexSession?, artist: Artist, rating: Float?): String =
        ratingMessage(session, catalogRepository.rateArtist(session, artist, rating))

    suspend fun rateAlbum(session: PlexSession?, album: Album, rating: Float?): String =
        ratingMessage(session, catalogRepository.rateAlbum(session, album, rating))

    suspend fun ratePlaylist(session: PlexSession?, playlist: Playlist, rating: Float?): String =
        ratingMessage(session, catalogRepository.ratePlaylist(session, playlist, rating))

    suspend fun toggleFavoriteArtist(session: PlexSession?, artist: Artist): String =
        favoriteMessage("Artist", session, catalogRepository.toggleFavoriteArtist(session, artist))

    suspend fun toggleFavoriteAlbum(session: PlexSession?, album: Album): String =
        favoriteMessage("Album", session, catalogRepository.toggleFavoriteAlbum(session, album))

    suspend fun toggleFavoritePlaylist(session: PlexSession?, playlist: Playlist): String =
        favoriteMessage("Playlist", session, catalogRepository.toggleFavoritePlaylist(session, playlist))

    /** The favorite playlists as an export file, or null when there are none to export. */
    suspend fun favoritePlaylistsExport(): TextExport? {
        val export = catalogRepository.favoritePlaylistsExport()
        if (export.playlists.isEmpty()) return null
        return TextExport(
            text = PlexClient.PlexJson.encodeToString(FavoritePlaylistsExport.serializer(), export),
            itemCount = export.playlists.size,
        )
    }

    suspend fun importFavoritePlaylists(content: String): String {
        if (content.isBlank()) return "That file is empty."
        val export = runCatching {
            PlexClient.PlexJson.decodeFromString(FavoritePlaylistsExport.serializer(), content)
        }.getOrElse { return "That file isn't a favorite playlists export." }
        return runCatching { catalogRepository.importFavoritePlaylists(export) }.fold(
            onSuccess = { imported ->
                if (imported > 0) {
                    "Imported $imported favorite playlists."
                } else {
                    "No matching playlists found to import."
                }
            },
            onFailure = { it.message ?: "Couldn't import favorite playlists." },
        )
    }

    /** The manually added radio stations as an export file, or null when there are none to export. */
    suspend fun radioStationsExport(): TextExport? {
        val export = radioRepository.exportRadioStations()
        if (export.stations.isEmpty()) return null
        return TextExport(
            text = PlexClient.PlexJson.encodeToString(RadioStationsExport.serializer(), export),
            itemCount = export.stations.size,
        )
    }

    suspend fun importRadioStations(content: String): String {
        if (content.isBlank()) return "That file is empty."
        val export = runCatching {
            PlexClient.PlexJson.decodeFromString(RadioStationsExport.serializer(), content)
        }.getOrElse { return "That file isn't a radio stations export." }
        return runCatching { radioRepository.importRadioStations(export) }.fold(
            onSuccess = { imported ->
                if (imported > 0) {
                    "Imported $imported radio stations."
                } else {
                    "No new radio stations to import."
                }
            },
            onFailure = { it.message ?: "Couldn't import radio stations." },
        )
    }

    private fun favoriteMessage(label: String, session: PlexSession?, result: FavoriteSyncResult): String {
        val provider = session.providerLabel()
        return when (result.favorite) {
            null -> "Couldn't find that item in the library."
            true -> when {
                result.plexAttempted && result.plexSynced -> "$label added to favorites and synced to $provider."
                result.plexAttempted -> "$label added to favorites, but $provider sync failed."
                else -> "$label added to favorites."
            }
            false -> when {
                result.plexAttempted && result.plexSynced -> "$label removed from favorites and synced to $provider."
                result.plexAttempted -> "$label removed from favorites, but $provider sync failed."
                else -> "$label removed from favorites."
            }
        }
    }

    private fun ratingMessage(session: PlexSession?, result: RatingSyncResult): String =
        when {
            !result.savedLocally -> "Couldn't find that item in the library."
            result.plexAttempted && result.plexSynced -> "Rating saved and synced to ${session.providerLabel()}."
            result.plexAttempted -> "Rating saved locally, but ${session.providerLabel()} sync failed."
            session.supportsRemoteRatings() -> "Rating saved."
            else -> "Rating saved locally."
        }
}

/** An export ready to hand to a save dialog, with how many items it holds for the confirmation message. */
data class TextExport(
    val text: String,
    val itemCount: Int,
)
