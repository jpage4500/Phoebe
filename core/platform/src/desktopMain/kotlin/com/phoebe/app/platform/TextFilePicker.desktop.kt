package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberTextFileSaver(): TextFileSaver =
    remember {
        TextFileSaver { suggestedFileName, text, onResult ->
            SwingUtilities.invokeLater {
                val target = runCatching { chooseSaveFile(suggestedFileName) }
                    .getOrElse { error ->
                        onResult(TextFileSaveResult.Failed(error.message))
                        return@invokeLater
                    }
                if (target == null) {
                    onResult(TextFileSaveResult.Cancelled)
                    return@invokeLater
                }
                onResult(
                    runCatching { target.writeText(text) }.fold(
                        onSuccess = { TextFileSaveResult.Saved },
                        onFailure = { TextFileSaveResult.Failed(it.message) },
                    ),
                )
            }
        }
    }

@Composable
actual fun rememberTextFileOpener(): TextFileOpener =
    remember {
        TextFileOpener { onResult ->
            SwingUtilities.invokeLater {
                val source = runCatching { chooseOpenFile() }
                    .getOrElse { error ->
                        onResult(TextFileOpenResult.Failed(error.message))
                        return@invokeLater
                    }
                if (source == null) {
                    onResult(TextFileOpenResult.Cancelled)
                    return@invokeLater
                }
                onResult(
                    runCatching { source.readText() }.fold(
                        onSuccess = { TextFileOpenResult.Opened(it) },
                        onFailure = { TextFileOpenResult.Failed(it.message) },
                    ),
                )
            }
        }
    }

private fun chooseSaveFile(suggestedFileName: String): File? {
    val initialDirectory = defaultDialogDirectory()
    if (isMacOs()) {
        // The native macOS sheet asks before overwriting, so no extra confirmation is needed here.
        val dialog = FileDialog(null as Frame?, "Save", FileDialog.SAVE).apply {
            initialDirectory?.let { directory = it.absolutePath }
            file = suggestedFileName
        }
        return try {
            dialog.isVisible = true
            val name = dialog.file ?: return null
            val directory = dialog.directory ?: return null
            File(directory, name)
        } finally {
            dialog.dispose()
        }
    }
    val chooser = JFileChooser(initialDirectory).apply {
        dialogTitle = "Save"
        selectedFile = File(initialDirectory, suggestedFileName)
        fileFilter = FileNameExtensionFilter("JSON files (*.json)", "json")
    }
    while (true) {
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val picked = chooser.selectedFile ?: return null
        val target = if (picked.extension.isEmpty()) File(picked.parentFile, "${picked.name}.json") else picked
        if (!target.exists()) return target
        val replace = JOptionPane.showConfirmDialog(
            null,
            "${target.name} already exists. Replace it?",
            "Replace file",
            JOptionPane.YES_NO_OPTION,
        )
        if (replace == JOptionPane.YES_OPTION) return target
    }
}

private fun chooseOpenFile(): File? {
    val initialDirectory = defaultDialogDirectory()
    if (isMacOs()) {
        val dialog = FileDialog(null as Frame?, "Open", FileDialog.LOAD).apply {
            initialDirectory?.let { directory = it.absolutePath }
        }
        return try {
            dialog.isVisible = true
            val name = dialog.file ?: return null
            val directory = dialog.directory ?: return null
            File(directory, name).takeIf { it.isFile }
        } finally {
            dialog.dispose()
        }
    }
    val chooser = JFileChooser(initialDirectory).apply {
        dialogTitle = "Open"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("JSON files (*.json)", "json")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.takeIf { it.isFile }
}

private fun defaultDialogDirectory(): File? {
    val home = System.getProperty("user.home")?.let(::File) ?: return null
    return home.resolve("Documents").takeIf { it.isDirectory } ?: home.takeIf { it.isDirectory }
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
