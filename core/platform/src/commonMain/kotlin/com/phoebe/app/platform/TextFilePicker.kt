package com.phoebe.app.platform

import androidx.compose.runtime.Composable

/**
 * Opens the platform "save as" dialog with a suggested file name and writes the text to wherever the
 * user picks. Used for backups and exports the user should be able to keep or move to another device,
 * unlike [PlatformStorage], which is private to the app.
 */
fun interface TextFileSaver {
    fun save(suggestedFileName: String, text: String, onResult: (TextFileSaveResult) -> Unit)
}

/** Opens the platform file picker and reads the chosen file as UTF-8 text. */
fun interface TextFileOpener {
    fun open(onResult: (TextFileOpenResult) -> Unit)
}

sealed interface TextFileSaveResult {
    data object Saved : TextFileSaveResult
    data object Cancelled : TextFileSaveResult
    data class Failed(val message: String?) : TextFileSaveResult
}

sealed interface TextFileOpenResult {
    data class Opened(val text: String) : TextFileOpenResult
    data object Cancelled : TextFileOpenResult
    data class Failed(val message: String?) : TextFileOpenResult
}

@Composable
expect fun rememberTextFileSaver(): TextFileSaver

@Composable
expect fun rememberTextFileOpener(): TextFileOpener
