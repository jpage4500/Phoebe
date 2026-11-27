package com.phoebe.app.data

import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.PersonalMixPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class LibraryPreferencesService(
    private val libraryUiRepository: LibraryUiRepository,
) {
    suspend fun setSortBy(tab: String, sortBy: LibrarySortBy) {
        libraryUiRepository.setSortBy(tab, sortBy)
    }

    suspend fun setAscending(tab: String, ascending: Boolean) {
        libraryUiRepository.setAscending(tab, ascending)
    }

    fun applyColumns(columns: LibraryColumnVisibility) {
        libraryUiRepository.applyColumns(columns)
    }

    suspend fun persistCurrentToDisk() {
        libraryUiRepository.persistCurrentToDisk()
    }

    suspend fun setHomeSections(sections: List<HomeSection>) {
        libraryUiRepository.setHomeSections(sections)
    }

    suspend fun setMobileBottomTabs(tabs: List<MobileBottomTab>) {
        libraryUiRepository.setMobileBottomTabs(tabs)
    }

    suspend fun setPersonalMix(preferences: PersonalMixPreferences) {
        libraryUiRepository.setPersonalMix(preferences)
    }

    suspend fun setAlbumGridItemSize(sizeDp: Int) {
        libraryUiRepository.setAlbumGridItemSize(sizeDp)
    }

    suspend fun setArtistGridItemSize(sizeDp: Int) {
        libraryUiRepository.setArtistGridItemSize(sizeDp)
    }

    suspend fun setViewMode(tab: String, viewMode: String) {
        libraryUiRepository.setViewMode(tab, viewMode)
    }
}
