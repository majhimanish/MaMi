package app.mami.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.mami.core.LinkPreview
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Makes link previews on the sender's phone, from the page's Open Graph
 * tags. The preview travels inside the encrypted message, so neither the
 * MaMi server nor the partner's phone ever fetches the link.
 */
class LinkPreviewer(http: OkHttpClient) {
    private val client = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(link: String): LinkPreview? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) { runCatching { load(link) }.getOrNull() }
    }

    private fun load(link: String): LinkPreview? {
        val url = (if (link.startsWith("http", ignoreCase = true)) link else "https://$link").toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) MaMi link preview")
            .header("Accept", "text/html")
            .build()
        val (html, finalUrl) = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true)) return null
            val source = response.body.source()
            source.request(MAX_HTML_BYTES)
            source.buffer.readUtf8(minOf(source.buffer.size, MAX_HTML_BYTES)) to response.request.url
        }
        val meta = parseMeta(html)
        val title = meta["og:title"] ?: meta["twitter:title"] ?: titleTag(html)
        val description = meta["og:description"] ?: meta["twitter:description"] ?: meta["description"]
        if (title.isNullOrBlank() && description.isNullOrBlank()) return null
        val imageUrl = (meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"])?.let { finalUrl.resolve(it) }
        return LinkPreview(
            url = link,
            title = title?.take(200),
            description = description?.take(300),
            image = imageUrl?.let { runCatching { image(it.toString()) }.getOrNull() },
        )
    }

    private fun image(url: String): String? {
        val request = Request.Builder().url(url).build()
        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            if ((response.body.contentLength()) > MAX_IMAGE_BYTES) return null
            val source = response.body.source()
            source.request(MAX_IMAGE_BYTES + 1)
            if (source.buffer.size > MAX_IMAGE_BYTES) return null
            source.buffer.readByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, IMAGE_SIDE.toFloat() / max(decoded.width, decoded.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 72, it)
            it.toByteArray()
        }
        if (small !== decoded) small.recycle()
        decoded.recycle()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    companion object {
        private const val MAX_HTML_BYTES = 512L * 1024
        private const val MAX_IMAGE_BYTES = 3L * 1024 * 1024
        private const val IMAGE_SIDE = 360

        private val metaTag = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
        private val attribute = Regex("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        private val title = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

        /** `property`/`name` → `content` for every meta tag; the first one wins. */
        fun parseMeta(html: String): Map<String, String> {
            val found = LinkedHashMap<String, String>()
            metaTag.findAll(html).forEach { tag ->
                val attributes = attribute.findAll(tag.value).associate { m ->
                    m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groups[4]?.value.orEmpty())
                }
                val key = (attributes["property"] ?: attributes["name"])?.lowercase() ?: return@forEach
                val content = attributes["content"]?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                found.putIfAbsent(key, content)
            }
            return found
        }

        fun titleTag(html: String): String? = title.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

        private fun codePoint(value: Int): String? = runCatching { String(Character.toChars(value)) }.getOrNull()

        fun unescape(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
            val entity = m.groupValues[1]
            when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
                else -> when (entity.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> null
                }
            } ?: m.value
        }
    }
}
