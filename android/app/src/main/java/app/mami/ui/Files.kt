package app.mami.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import app.mami.media.MediaLibrary
import java.io.File

/** Opening and sharing attachments with other apps, through MaMi's FileProvider. */
object Files {
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, MediaLibrary.authority(context), file)

    /** "Open with…" another app (PDF reader, music player…). */
    fun open(context: Context, file: File, mime: String?) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, file: File, mime: String?) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openUrl(context: Context, url: String) {
        val full = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    fun size(bytes: Long?): String = when {
        bytes == null -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    /** "PDF", "DOCX"… from a file name or MIME type. */
    fun typeLabel(name: String?, mime: String?): String =
        name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.uppercase()
            ?: mime?.substringAfter('/')?.substringBefore(';')?.uppercase()?.take(5)
            ?: "FILE"
}

/** Decodes a small base64 JPEG (thumbnails, link images) once. */
@Composable
fun rememberBase64Image(data: String?): ImageBitmap? = remember(data) {
    if (data.isNullOrEmpty()) return@remember null
    runCatching {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** Voice note bars from the base64 waveform. */
fun decodeWaveform(data: String?): ByteArray =
    data?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: ByteArray(0)
