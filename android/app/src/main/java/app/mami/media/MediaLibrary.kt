package app.mami.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import app.mami.data.db.MediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something about a picked file that the person should hear about, in plain words. */
class MediaException(message: String) : IOException(message)

/** A file ready to be sent: copied into the app's private folder, with everything the partner needs to show it. */
data class PreparedMedia(
    val kind: String,
    val fileName: String,
    val mime: String,
    val name: String?,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** Tiny JPEG, base64. */
    val thumbnail: String? = null,
    /** Voice note loudness, base64 of 0..255 values. */
    val waveform: String? = null,
)

/**
 * Photos, videos, voice notes and files on this phone. Everything lives in
 * the app's private storage (no other app can read it, and it's excluded
 * from backups); "Save" copies a file to the gallery on request.
 */
class MediaLibrary(private val context: Context) {
    val dir: File = File(context.filesDir, "media").apply { mkdirs() }
    private val temp: File get() = File(context.cacheDir, "transfers").apply { mkdirs() }

    /** Where camera captures are written before they're prepared. */
    val captureDir: File get() = File(context.cacheDir, "capture").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    fun newFile(extension: String): File = File(dir, "${UUID.randomUUID()}.$extension")

    fun tempFile(suffix: String): File = File(temp, "${UUID.randomUUID()}$suffix")

    fun delete(name: String?) {
        if (name != null) file(name).delete()
    }

    /** Deletes every attachment (signing out, unlinking). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        temp.listFiles()?.forEach { it.delete() }
        captureDir.listFiles()?.forEach { it.delete() }
    }

    /** A photo, video or document picked by the person. */
    suspend fun prepare(uri: Uri, asDocument: Boolean = false): PreparedMedia = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: guessMime(uri)
        when {
            asDocument -> prepareDocument(uri, mime)
            mime == "image/gif" -> prepareDocument(uri, mime).copy(kind = MediaType.PHOTO)
            mime.startsWith("image/") -> preparePhoto(uri)
            mime.startsWith("video/") -> prepareVideo(uri, mime)
            else -> prepareDocument(uri, mime)
        }
    }

    /** A voice note recorded straight into the media folder. */
    fun prepareVoice(file: File, durationMs: Long, levels: ByteArray): PreparedMedia = PreparedMedia(
        kind = MediaType.VOICE,
        fileName = file.name,
        mime = "audio/mp4",
        name = null,
        size = file.length(),
        durationMs = durationMs,
        waveform = Base64.encodeToString(levels, Base64.NO_WRAP),
    )

    /**
     * Photos are re-encoded at most 2560 px on the long side. That keeps them
     * sharp on any phone, small to send, and drops the hidden location and
     * camera details a photo file usually carries.
     */
    private fun preparePhoto(uri: Uri): PreparedMedia {
        val bitmap = decodeBounded(uri, MAX_PHOTO_SIDE) ?: throw MediaException("That photo couldn't be opened.")
        val out = newFile("jpg")
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            return PreparedMedia(
                kind = MediaType.PHOTO,
                fileName = out.name,
                mime = "image/jpeg",
                name = null,
                size = out.length(),
                width = bitmap.width,
                height = bitmap.height,
                thumbnail = thumbnail(bitmap),
            )
        } catch (e: IOException) {
            out.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    private fun prepareVideo(uri: Uri, mime: String): PreparedMedia {
        val size = sizeOf(uri)
        if (size != null && size > MAX_FILE_BYTES) throw MediaException("Videos can be up to 100 MB. Try a shorter one.")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val w = width
                width = height
                height = w
            }
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val out = copyIn(uri, MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4")
            return PreparedMedia(
                kind = MediaType.VIDEO,
                fileName = out.name,
                mime = mime,
                name = null,
                size = out.length(),
                width = width,
                height = height,
                durationMs = duration,
                thumbnail = frame?.let { thumbnail(it).also { _ -> it.recycle() } },
            )
        } catch (e: RuntimeException) {
            throw MediaException("That video couldn't be opened.")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun prepareDocument(uri: Uri, mime: String): PreparedMedia {
        val size = sizeOf(uri)
        if (size != null && size > MAX_FILE_BYTES) throw MediaException("Files can be up to 100 MB.")
        val name = displayName(uri)
        val extension = name?.substringAfterLast('.', "")?.takeIf { it.length in 1..8 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val out = copyIn(uri, extension)
        return PreparedMedia(
            kind = MediaType.FILE,
            fileName = out.name,
            mime = mime,
            name = name ?: "File.$extension",
            size = out.length(),
        )
    }

    private fun copyIn(uri: Uri, extension: String): File {
        val out = newFile(extension)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw MediaException("That file couldn't be opened.")
            input.use { source ->
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw MediaException("Files can be up to 100 MB.")
                        sink.write(buffer, 0, read)
                    }
                }
            }
            return out
        } catch (e: IOException) {
            out.delete()
            throw e
        }
    }

    /** Decodes an image no larger than [maxSide], the right way up. */
    fun decodeBounded(uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = maxSide.toFloat() / max(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                    // Software bitmaps can be compressed and read back.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: IOException) {
                null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (degrees == 0f && scale == 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    /** About 1-2 KB: enough for a blurred preview while the real thing downloads. */
    fun thumbnail(bitmap: Bitmap): String {
        val scale = THUMB_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val small = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        val bytes = ByteArrayOutputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 60, it)
            it.toByteArray()
        }
        if (small !== bitmap) small.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Copies an attachment into the phone's gallery or Downloads, where other
     * apps can see it. Returns false if it couldn't be saved.
     */
    suspend fun saveToDevice(name: String, kind: String?, mime: String, displayName: String?): Boolean = withContext(Dispatchers.IO) {
        val source = file(name)
        if (!source.exists()) return@withContext false
        val fileName = displayName ?: "MaMi_${System.currentTimeMillis()}.${source.extension}"
        val (collection, folder) = when (kind) {
            MediaType.PHOTO -> mediaCollection(images = true) to Environment.DIRECTORY_PICTURES
            MediaType.VIDEO -> mediaCollection(images = false) to Environment.DIRECTORY_MOVIES
            else -> null to Environment.DIRECTORY_DOWNLOADS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = collection ?: MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/MaMi")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(target, values) ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                true
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val folderFile = File(Environment.getExternalStoragePublicDirectory(folder), "MaMi").apply { mkdirs() }
            val target = File(folderFile, fileName)
            try {
                source.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    private fun mediaCollection(images: Boolean): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (images) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun sizeOf(uri: Uri): Long? = query(uri, OpenableColumns.SIZE)?.toLongOrNull()

    private fun displayName(uri: Uri): String? = query(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }

    private fun query(uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun guessMime(uri: Uri): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_PHOTO_SIDE = 2560
        const val THUMB_SIDE = 40

        /** Files a [MediaLibrary] serves to other apps (share, open with) through this authority. */
        fun authority(context: Context) = "${context.packageName}.files"

        /** File extension for a MIME type, for files written on download. */
        fun extensionFor(mime: String?, name: String?): String =
            name?.substringAfterLast('.', "")?.takeIf { it.length in 1..8 }
                ?: mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: when {
                    mime == "audio/mp4" -> "m4a"
                    mime?.startsWith("image/") == true -> "jpg"
                    mime?.startsWith("video/") == true -> "mp4"
                    else -> "bin"
                }
    }
}
