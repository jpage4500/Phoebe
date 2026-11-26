package com.phoebe.app.data

import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.LocalMetadataOverride
import com.phoebe.app.domain.SavedSearch
import com.phoebe.app.domain.SmartPlaylist
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PhoebeBackupPackage(
    // Always written so a backup file can be told apart from other Phoebe exports when it's opened.
    @EncodeDefault val format: String = FormatId,
    @EncodeDefault val version: Int = CurrentVersion,
    val settings: AppSettings = AppSettings.Default,
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val localMetadataOverrides: List<LocalMetadataOverride> = emptyList(),
) {
    companion object {
        const val CurrentVersion = 3
        const val FormatId = "phoebe-backup"
    }
}

data class BackupRestorePreview(
    val smartPlaylistCount: Int = 0,
    val savedSearchCount: Int = 0,
    val metadataOverrideCount: Int = 0,
)

enum class BackupRestoreMode {
    Merge,
    Replace,
}

@SingleIn(AppScope::class)
@Inject
class ImportExportService(
    private val appSettingsRepository: AppSettingsRepository,
    private val userArtifactsRepository: UserArtifactsRepository,
) {
    fun exportBackupPackage(): String =
        PhoebeDataJson.encodeToString(
            PhoebeBackupPackage(
                settings = appSettingsRepository.settings.value.copy(keepPlayingEnabled = false),
                smartPlaylists = userArtifactsRepository.smartPlaylists.value,
                savedSearches = userArtifactsRepository.savedSearches.value,
                localMetadataOverrides = userArtifactsRepository.metadataOverrides.value,
            ),
        )

    fun previewBackupPackage(payload: String): BackupRestorePreview {
        val backup = decodeBackup(payload) ?: return BackupRestorePreview()
        return BackupRestorePreview(
            smartPlaylistCount = backup.smartPlaylists.size,
            savedSearchCount = backup.savedSearches.size,
            metadataOverrideCount = backup.localMetadataOverrides.size,
        )
    }

    /** Restores [payload], or returns null without changing anything when it isn't a readable Phoebe backup. */
    suspend fun restoreBackupPackage(payload: String, mode: BackupRestoreMode): BackupRestorePreview? {
        val backup = decodeBackup(payload) ?: return null
        if (mode == BackupRestoreMode.Replace) {
            userArtifactsRepository.clearUserArtifacts()
        }
        backup.smartPlaylists.forEach { userArtifactsRepository.upsertSmartPlaylist(it) }
        backup.savedSearches.forEach { userArtifactsRepository.upsertSavedSearch(it) }
        backup.localMetadataOverrides.forEach { userArtifactsRepository.upsertMetadataOverride(it) }
        appSettingsRepository.setDownloadPolicySettings(backup.settings.downloadPolicy)
        appSettingsRepository.setStreamingPolicySettings(backup.settings.streamingPolicy)
        appSettingsRepository.setAudioProcessingSettings(backup.settings.audioProcessing)
        appSettingsRepository.setListenBrainzSettings(backup.settings.listenBrainz)
        appSettingsRepository.setLastFmSettings(backup.settings.lastFm)
        appSettingsRepository.setCrossfadeSeconds(backup.settings.crossfadeSeconds)
        appSettingsRepository.setScanLibraryOnLaunch(backup.settings.scanLibraryOnLaunch)
        appSettingsRepository.setNotifyWhenDownloadFinishes(backup.settings.notifyWhenDownloadFinishes)
        appSettingsRepository.setNowPlayingVisualizerPreset(backup.settings.nowPlayingVisualizerPreset)
        appSettingsRepository.setNowPlayingVisualizerInTvFrame(false)
        appSettingsRepository.setBlurredArtworkAppearance(backup.settings.blurredArtworkAppearance)
        appSettingsRepository.setFullBleedDetailArtwork(backup.settings.fullBleedDetailArtwork)
        appSettingsRepository.setTintedBackgroundGradient(backup.settings.tintedBackgroundGradient)
        return previewBackupPackage(payload)
    }

    private fun decodeBackup(payload: String): PhoebeBackupPackage? =
        try {
            // Every field has a default, so any JSON object would otherwise decode as an empty backup,
            // and restoring one in Replace mode would wipe the user's data.
            if (!looksLikeBackup(PhoebeDataJson.parseToJsonElement(payload).jsonObject)) return null
            PhoebeDataJson.decodeFromString<PhoebeBackupPackage>(payload)
                .takeIf { it.version <= PhoebeBackupPackage.CurrentVersion }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}

private val LegacyBackupKeys = setOf("settings", "smartPlaylists", "savedSearches", "localMetadataOverrides")

// Backups written before the format marker existed are recognized by their own top-level keys.
private fun looksLikeBackup(json: JsonObject): Boolean {
    val format = (json["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (format != null) return format == PhoebeBackupPackage.FormatId
    return json.keys.any { it in LegacyBackupKeys }
}
