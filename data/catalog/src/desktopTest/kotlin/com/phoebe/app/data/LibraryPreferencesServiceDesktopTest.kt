package com.phoebe.app.data

import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryPreferencesServiceDesktopTest {
    @Test
    fun restoresLegacyDefaultHomeSectionsAsCurrentDefaultOrder() = runTest {
        val storageRoot = Files.createTempDirectory("phoebe-library-preferences-legacy-home")
        val previousRoot = System.getProperty(StorageRootProperty)
        System.setProperty(StorageRootProperty, storageRoot.toAbsolutePath().toString())
        val (database, driver) = newInMemoryPhoebeDatabase()
        try {
            database.libraryPrefsQueries.upsert(
                sortBy = "Name",
                ascending = 1L,
                colYear = 1L,
                colGenre = 1L,
                colFilepath = 1L,
                colAudioCodec = 1L,
                colBitrate = 1L,
                colDuration = 1L,
                colSampleRate = 1L,
                colFileType = 1L,
                colDateAdded = 1L,
                colRating = 1L,
                colFavorite = 1L,
                homeSections = "Mixes,Collections,FavoritePlaylists,FavoriteArtists,FavoriteAlbums,RecentSongs,RecentArtists,RecentAlbums,Played,Random",
                mobileBottomTabs = "Home,Search,Library,Playlists,Radio",
                personalMix = "{\"limit\":50,\"heavyRotationWeight\":25,\"recentWeight\":30,\"mostPlayedWeight\":25,\"similarWeight\":15,\"discoveryWeight\":5}",
                gridColumns = 3L,
                albumGridItemSizeDp = 160L,
                artistGridItemSizeDp = 112L,
            )

            val restored = LibraryUiRepository(database, PlatformStorage()).apply { restore() }

            assertEquals(HomeSection.defaultOrder, restored.preferences.value.homeSections)
        } finally {
            driver.close()
            if (previousRoot == null) {
                System.clearProperty(StorageRootProperty)
            } else {
                System.setProperty(StorageRootProperty, previousRoot)
            }
            storageRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun persistsLibraryPreferenceMutationsThroughRepository() = runTest {
        val storageRoot = Files.createTempDirectory("phoebe-library-preferences-service")
        val previousRoot = System.getProperty(StorageRootProperty)
        System.setProperty(StorageRootProperty, storageRoot.toAbsolutePath().toString())
        val (database, driver) = newInMemoryPhoebeDatabase()
        try {
            val repository = LibraryUiRepository(database, PlatformStorage())
            val service = LibraryPreferencesService(repository)
            val personalMix = PersonalMixPreferences(
                limit = 70,
                heavyRotationWeight = 30,
                recentWeight = 20,
                mostPlayedWeight = 20,
                similarWeight = 15,
                discoveryWeight = 15,
            )

            service.setSortBy("Artists", LibrarySortBy.Name)
            service.setAscending("Artists", true)
            service.setSortBy("Albums", LibrarySortBy.Year)
            service.setAscending("Albums", false)
            service.setViewMode("Albums", "List")
            service.setHomeSections(listOf(HomeSection.RecentSongs, HomeSection.FavoriteAlbums))
            service.setPersonalMix(personalMix)
            service.setAlbumGridItemSize(188)
            service.setArtistGridItemSize(172)

            val restored = LibraryUiRepository(database, PlatformStorage()).apply { restore() }
            val preferences = restored.preferences.value
            val artists = preferences.forTab("Artists")
            assertEquals(LibrarySortBy.Name, artists.sortBy)
            assertTrue(artists.ascending)
            assertEquals("Grid", artists.viewMode)
            val albums = preferences.forTab("Albums")
            assertEquals(LibrarySortBy.Year, albums.sortBy)
            assertFalse(albums.ascending)
            assertEquals("List", albums.viewMode)
            // A tab nobody has touched still uses the shared defaults.
            assertEquals(preferences.sortBy, preferences.forTab("Songs").sortBy)
            assertEquals(listOf(HomeSection.RecentSongs, HomeSection.FavoriteAlbums), preferences.homeSections.take(2))
            assertEquals(personalMix, preferences.personalMix)
            assertEquals(188, preferences.albumGridItemSizeDp)
            assertEquals(172, preferences.artistGridItemSizeDp)
        } finally {
            driver.close()
            if (previousRoot == null) {
                System.clearProperty(StorageRootProperty)
            } else {
                System.setProperty(StorageRootProperty, previousRoot)
            }
            storageRoot.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val StorageRootProperty = "phoebe.storage.root"
    }
}
