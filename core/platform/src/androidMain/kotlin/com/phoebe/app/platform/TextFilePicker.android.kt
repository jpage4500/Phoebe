package com.phoebe.app.platform

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PendingSave(val text: String, val onResult: (TextFileSaveResult) -> Unit)

@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberTextFileSaver must be hosted in an Activity."
    }
    val scope = rememberCoroutineScope()
    // The launcher's callback is fixed when it is registered, so the text and callback for the
    // save in progress are held here until the system dialog returns.
    val pending = remember { arrayOfNulls<PendingSave>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val request = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (uri == null) {
            request.onResult(TextFileSaveResult.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = checkNotNull(activity.contentResolver.openOutputStream(uri, "wt")) {
                        "Couldn't open the chosen file."
                    }
                    stream.bufferedWriter().use { it.write(request.text) }
                }
            }
            request.onResult(
                result.fold(
                    onSuccess = { TextFileSaveResult.Saved },
                    onFailure = { TextFileSaveResult.Failed(it.message) },
                ),
            )
        }
    }
    return remember(launcher) {
        TextFileSaver { suggestedFileName, text, onResult ->
            pending[0] = PendingSave(text, onResult)
            runCatching { launcher.launch(suggestedFileName) }.onFailure { error ->
                pending[0] = null
                onResult(TextFileSaveResult.Failed(error.message))
            }
        }
    }
}

@Composable
actual fun rememberTextFileOpener(): TextFileOpener {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberTextFileOpener must be hosted in an Activity."
    }
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<(TextFileOpenResult) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val onResult = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (uri == null) {
            onResult(TextFileOpenResult.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = checkNotNull(activity.contentResolver.openInputStream(uri)) {
                        "Couldn't open the chosen file."
                    }
                    stream.bufferedReader().use { it.readText() }
                }
            }
            onResult(
                result.fold(
                    onSuccess = { TextFileOpenResult.Opened(it) },
                    onFailure = { TextFileOpenResult.Failed(it.message) },
                ),
            )
        }
    }
    return remember(launcher) {
        TextFileOpener { onResult ->
            pending[0] = onResult
            // Some file providers report JSON as text/plain or octet-stream, so accept any file and
            // let the import report a file it can't read.
            runCatching { launcher.launch(arrayOf("*/*")) }.onFailure { error ->
                pending[0] = null
                onResult(TextFileOpenResult.Failed(error.message))
            }
        }
    }
}
