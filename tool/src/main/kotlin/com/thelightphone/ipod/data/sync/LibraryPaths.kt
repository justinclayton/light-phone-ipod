package com.thelightphone.ipod.data.sync

import com.thelightphone.ipod.data.scan.LibraryScanner
import java.io.File

/**
 * Guards the one thing the Mac controls on the phone: where a file lands under
 * `music/`. Anything that could escape the folder, or that the scanner would never
 * pick up, is rejected before a byte is downloaded.
 */
object LibraryPaths {
    const val PART_SUFFIX = ".part"

    /** `path` normalized to forward slashes and resolved under [musicRoot], or `null` when it is unsafe. */
    fun resolve(musicRoot: File, path: String): File? {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any(::isForbiddenChar) }) return null
        if (segments.last().endsWith(PART_SUFFIX)) return null
        val file = File(musicRoot, segments.joinToString(File.separator))
        val rootPath = musicRoot.canonicalPath + File.separator
        return file.canonicalFile.takeIf { it.path.startsWith(rootPath) }
    }

    fun isPlayable(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in LibraryScanner.SUPPORTED_EXTENSIONS

    fun displayName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    private fun isForbiddenChar(c: Char): Boolean = c.code < 0x20 || c == ':'
}
