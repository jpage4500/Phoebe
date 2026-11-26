package com.phoebe.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.phoebe.app.AppState
import com.phoebe.app.UserDataFile
import com.phoebe.app.data.BackupRestoreMode
import com.phoebe.app.platform.TextFileOpener
import com.phoebe.app.platform.TextFileSaver
import com.phoebe.app.platform.rememberTextFileOpener
import com.phoebe.app.platform.rememberTextFileSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Backup & restore settings buttons, each opening the platform save or open dialog. */
internal class UserDataFileActions(
    val exportBackup: () -> Unit,
    val mergeBackup: () -> Unit,
    val replaceFromBackup: () -> Unit,
    val exportFavoritePlaylists: () -> Unit,
    val importFavoritePlaylists: () -> Unit,
    val exportRadioStations: () -> Unit,
    val importRadioStations: () -> Unit,
)

@Composable
internal fun rememberUserDataFileActions(state: AppState): UserDataFileActions {
    val saver = rememberTextFileSaver()
    val opener = rememberTextFileOpener()
    // The pickers must be launched from the UI thread, so the export is built in a UI-bound scope.
    val scope = rememberCoroutineScope()
    return remember(state, saver, opener, scope) {
        UserDataFileActions(
            exportBackup = { scope.export(state, saver, UserDataFile.Backup) },
            mergeBackup = { opener.import(state, UserDataFile.Backup, BackupRestoreMode.Merge) },
            replaceFromBackup = { opener.import(state, UserDataFile.Backup, BackupRestoreMode.Replace) },
            exportFavoritePlaylists = { scope.export(state, saver, UserDataFile.FavoritePlaylists) },
            importFavoritePlaylists = { opener.import(state, UserDataFile.FavoritePlaylists) },
            exportRadioStations = { scope.export(state, saver, UserDataFile.RadioStations) },
            importRadioStations = { opener.import(state, UserDataFile.RadioStations) },
        )
    }
}

private fun CoroutineScope.export(state: AppState, saver: TextFileSaver, file: UserDataFile) {
    launch {
        val export = state.prepareUserDataExport(file) ?: return@launch
        saver.save(file.suggestedFileName, export.text) { result ->
            state.onUserDataExportSaved(file, export, result)
        }
    }
}

private fun TextFileOpener.import(
    state: AppState,
    file: UserDataFile,
    mode: BackupRestoreMode = BackupRestoreMode.Merge,
) {
    open { result -> state.importUserDataFile(file, result, mode) }
}
