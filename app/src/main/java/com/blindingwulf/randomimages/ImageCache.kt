package com.blindingwulf.randomimages

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk image cache — every image shown is copied here at full quality,
 * capped at [MAX_FILES] with the oldest deleted first (imgcache.py).
 *
 * On mobile the cache doubles as the browse history: swiping back walks this
 * directory instead of keeping decoded bitmaps in memory.
 */
class ImageCache(context: Context) {

    private val dir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "image_cache",
    ).apply { mkdirs() }

    fun directory(): File = dir

    /** Written image file, or null on failure (imgcache.save returns "" there). */
    fun save(data: ByteArray, source: String, format: String, seq: Int, url: String = ""): File? = try {
        dir.mkdirs()
        val token = if (url.isEmpty()) "" else "-" + tokenOf(url)
        val stem = TIME_FORMAT.format(Date()) + "%06d-$source".format(seq) + token
        var file = File(dir, "$stem.$format")
        var n = 1
        while (file.exists()) {
            file = File(dir, "${stem}_$n.$format")
            n++
        }
        file.writeBytes(data)
        prune()
        file
    } catch (e: Exception) {
        null
    }

    /**
     * Every URL token present in the directory. Because the token lives in the
     * filename, this listing is the whole "already shown" set: pruning an old
     * image frees its URL, and clearing the cache empties the set — which is the
     * behaviour the user expects from "clear cache" rather than a separate
     * history that keeps banning images nothing remembers.
     */
    fun urlTokens(): Set<String> =
        listFiles().mapNotNull { TOKEN_RE.find(it.name)?.groupValues?.get(1) }.toSet()

    /** Cached images oldest first — the show order. */
    fun filesOldestFirst(): List<File> =
        listFiles().sortedBy { it.lastModified() }

    /** Cached images newest first — used when walking backwards through history. */
    fun filesNewestFirst(): List<File> = filesOldestFirst().asReversed()

    fun count(): Int = listFiles().size

    fun totalBytes(): Long = listFiles().sumOf { it.length() }

    /**
     * Count and size from a single directory walk. The status line shows both
     * every time an image appears, and on Android 5 with a full 1000-file cache
     * two separate scans per image is measurable storage I/O.
     */
    fun stats(): Pair<Int, Long> {
        val files = listFiles()
        return files.size to files.sumOf { it.length() }
    }

    /** Deletes every cached image; returns how many went. */
    fun clear(): Int {
        var n = 0
        listFiles().forEach { if (it.delete()) n++ }
        return n
    }

    fun delete(file: File): Boolean = file.delete()

    /** Keeps at most [MAX_FILES] files, removing the oldest first. */
    fun prune() {
        val files = filesOldestFirst()
        val excess = files.size - MAX_FILES
        if (excess <= 0) return
        files.take(excess).forEach { it.delete() }
    }

    private fun listFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile }.orEmpty()

    companion object {
        const val MAX_FILES = 1000
        private val TIME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-", Locale.US)

        /**
         * First five SHA-256 bytes of the URL, in hex. 40 bits against a
         * 1000-file cache puts a collision far below anything user-visible.
         * The optional `_n` covers the same-second rename in save().
         */
        fun tokenOf(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .take(5)
                .joinToString("") { "%02x".format(it) }

        private val TOKEN_RE = Regex("""-([0-9a-f]{10})(?:_\d+)?\.\w+$""")
    }
}

/**
 * Copies a cached image into the system Gallery (Pictures/RandomImages).
 * Replaces the desktop's "open the cache folder in Explorer" action, which has
 * no mobile equivalent a user would recognise.
 *
 * Q+ uses scoped storage (RELATIVE_PATH + IS_PENDING, no permission). Below Q
 * the row needs an explicit DATA path plus a media-scanner pass and therefore
 * WRITE_EXTERNAL_STORAGE, which the UI requests before calling this.
 */
object GallerySaver {

    private const val DIRECTORY = "RandomImages"

    /** True when a runtime WRITE_EXTERNAL_STORAGE grant is needed first. */
    val needsWritePermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @Suppress("DEPRECATION")
    fun saveToGallery(
        context: Context,
        bytes: ByteArray,
        format: String,
        credit: String,
    ): Result<Unit> {
        val name = "riv-${System.currentTimeMillis()}.$format"
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(format))
                if (credit.isNotBlank()) put(MediaStore.Images.Media.AUTHOR, credit)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$DIRECTORY",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        DIRECTORY,
                    )
                    dir.mkdirs()
                    put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IllegalStateException("MediaStore refused the insert"))
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.failure(IllegalStateException("Could not open output stream"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // Pre-Q the file only shows up in other apps after a rescan.
                MediaScannerConnection.scanFile(
                    context, arrayOf(uri.toString()), null, null
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mimeOf(format: String) = when (format.lowercase(Locale.US)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}
