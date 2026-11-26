package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun rememberTextFileSaver(): TextFileSaver =
    remember {
        TextFileSaver { suggestedFileName, text, onResult -> saveTextFileIos(suggestedFileName, text, onResult) }
    }

@Composable
actual fun rememberTextFileOpener(): TextFileOpener =
    remember {
        TextFileOpener { onResult -> openTextFileIos(onResult) }
    }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun saveTextFileIos(suggestedFileName: String, text: String, onResult: (TextFileSaveResult) -> Unit) {
    val root = topViewController()
    if (root == null) {
        onResult(TextFileSaveResult.Failed("Couldn't show the save dialog."))
        return
    }
    // The export picker copies an existing file, so write the text to a temporary file first.
    val tempUrl = NSURL.fileURLWithPath(NSTemporaryDirectory() + suggestedFileName)
    val written = NSString.create(string = text).writeToURL(
        tempUrl,
        atomically = true,
        encoding = NSUTF8StringEncoding,
        error = null,
    )
    if (!written) {
        onResult(TextFileSaveResult.Failed("Couldn't prepare the file to save."))
        return
    }
    val picker = UIDocumentPickerViewController(forExportingURLs = listOf(tempUrl), asCopy = true)
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            activeDelegates.remove(controller.hashCode())
            onResult(TextFileSaveResult.Saved)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            activeDelegates.remove(controller.hashCode())
            onResult(TextFileSaveResult.Cancelled)
        }
    }
    activeDelegates[picker.hashCode()] = delegate
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun openTextFileIos(onResult: (TextFileOpenResult) -> Unit) {
    val root = topViewController()
    if (root == null) {
        onResult(TextFileOpenResult.Failed("Couldn't show the file picker."))
        return
    }
    val types = listOfNotNull(
        UTType.typeWithIdentifier("public.json"),
        UTType.typeWithIdentifier("public.plain-text"),
    )
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
    picker.allowsMultipleSelection = false
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            activeDelegates.remove(controller.hashCode())
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            if (url == null) {
                onResult(TextFileOpenResult.Cancelled)
                return
            }
            val text = NSData.create(contentsOfURL = url)?.toUtf8String()
            onResult(
                if (text == null) {
                    TextFileOpenResult.Failed("Couldn't read the chosen file.")
                } else {
                    TextFileOpenResult.Opened(text)
                },
            )
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            activeDelegates.remove(controller.hashCode())
            onResult(TextFileOpenResult.Cancelled)
        }
    }
    activeDelegates[picker.hashCode()] = delegate
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toUtf8String(): String {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
    return bytes.decodeToString()
}

private fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val window = app.keyWindow ?: (app.windows.firstOrNull() as? UIWindow)
    var current = window?.rootViewController
    while (current?.presentedViewController != null) {
        current = current.presentedViewController
    }
    return current
}

// UIKit holds document picker delegates weakly, so keep each one alive until its picker finishes.
private val activeDelegates = mutableMapOf<Int, Any>()
