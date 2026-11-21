package com.arindam.camerax.data.media

import com.arindam.camerax.util.commons.Constants
import java.io.File
import java.util.Locale

/**
 * Data: capture-file helpers used by [FileMediaRepository] and the in-app gallery.
 * Keep extension checks here so the UI does not reimplement the media whitelist.
 */
internal fun listedCaptures(directory: File): List<File> {
    val files = directory.listFiles { file -> file.isCaptureFile() } ?: return emptyList()
    val stillNames = files.filter { it.isStillCapture() }
        .map { it.nameWithoutExtension }
        .toHashSet()
    return files
        .filterNot { file -> file.isLivePhotoCompanion(stillNames) }
        .sortedByDescending { it.lastModified() }
}

/** A Live Photo clip is published to the gallery but never listed as its own item. */
internal fun File.isLivePhotoCompanion(stillNames: Set<String>): Boolean =
    extension.equals("mov", ignoreCase = true) && stillNames.contains(nameWithoutExtension)

internal fun File.isCaptureFile(): Boolean =
    Constants.FILE.EXTENSION_WHITELIST.contains(extension.lowercase(Locale.US))

internal fun File.isVideoCapture(): Boolean =
    extension.equals("mp4", ignoreCase = true) || extension.equals("mov", ignoreCase = true)

internal fun File.isRawCapture(): Boolean = extension.equals("dng", ignoreCase = true)

internal fun File.isStillCapture(): Boolean =
    extension.equals("jpg", ignoreCase = true) ||
        extension.equals("jpeg", ignoreCase = true) ||
        extension.equals("jpe", ignoreCase = true) ||
        extension.equals("heic", ignoreCase = true)
