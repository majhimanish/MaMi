package app.mami.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import android.util.Log
import app.mami.data.db.MediaType
import app.mami.media.MediaLibrary
import app.mami.media.PreparedMedia
import app.mami.media.VoiceRecorder
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Makes the pretend partner's photos, voice notes, videos and files on the
 * phone itself, so the demo needs no network and no bundled assets.
 */
class DemoMedia(private val media: MediaLibrary) {

    enum class Scene(val caption: String) {
        SUNSET("Sunset from the lake 🌅"),
        BEACH("Wish you were here 🏖️"),
        NIGHT("Same moon, same sky 🌙"),
    }

    fun photo(scene: Scene, width: Int = 1080, height: Int = 1350): PreparedMedia {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), scene, width.toFloat(), height.toFloat(), 0f)
        val out = media.newFile("jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val thumb = media.thumbnail(bitmap)
        bitmap.recycle()
        return PreparedMedia(MediaType.PHOTO, out.name, "image/jpeg", null, out.length(), width, height, thumbnail = thumb)
    }

    /** A little hummed tune, as a WAV file (every phone plays it). */
    fun voice(seconds: Double = 5.5): PreparedMedia {
        val rate = 16_000
        val samples = (rate * seconds).toInt()
        // A few notes of a lullaby, hummed with a soft vibrato.
        val notes = listOf(392.0, 440.0, 392.0, 523.3, 493.9, 392.0, 440.0, 392.0, 587.3, 523.3)
        val noteLength = samples / notes.size
        val pcm = ShortArray(samples)
        val random = Random(7)
        for (i in 0 until samples) {
            val note = notes[(i / noteLength).coerceAtMost(notes.lastIndex)]
            val inNote = (i % noteLength).toDouble() / noteLength
            val envelope = sin(PI * inNote).let { it * it } * (0.55 + 0.45 * sin(2 * PI * i / samples * 3))
            val t = i.toDouble() / rate
            val vibrato = 1 + 0.004 * sin(2 * PI * 5.5 * t)
            val tone = sin(2 * PI * note * vibrato * t) + 0.35 * sin(4 * PI * note * vibrato * t) + 0.12 * sin(6 * PI * note * t)
            val breath = (random.nextDouble() - 0.5) * 0.03
            pcm[i] = ((tone * 0.45 * envelope + breath) * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        val out = media.newFile("wav")
        DataOutputStream(out.outputStream().buffered()).use { data ->
            fun int(v: Int) = data.writeInt(Integer.reverseBytes(v))
            fun short(v: Int) = data.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            data.writeBytes("RIFF")
            int(36 + samples * 2)
            data.writeBytes("WAVEfmt ")
            int(16)
            short(1)
            short(1)
            int(rate)
            int(rate * 2)
            short(2)
            short(16)
            data.writeBytes("data")
            int(samples * 2)
            pcm.forEach { short(it.toInt()) }
        }
        val window = rate / 20
        val levels = (0 until samples step window).map { start ->
            val end = minOf(samples, start + window)
            (start until end).maxOf { abs(pcm[it].toInt()) } * 255 / Short.MAX_VALUE
        }
        val bars = VoiceRecorder.bars(levels, VoiceRecorder.BARS)
        return PreparedMedia(
            MediaType.VOICE,
            out.name,
            "audio/wav",
            null,
            out.length(),
            durationMs = (seconds * 1000).toLong(),
            waveform = Base64.encodeToString(bars, Base64.NO_WRAP),
        )
    }

    /**
     * A 4-second clip of the sunset slowly setting, encoded on the phone's
     * own video encoder (it takes the 4 seconds, so call it off the main
     * thread). Where there's no encoder (tests) it returns only a thumbnail,
     * which shows as a video still on its way.
     */
    fun video(): PreparedMedia {
        val width = 480
        val height = 848
        val still = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(still), Scene.SUNSET, width.toFloat(), height.toFloat(), 0f)
        val thumb = media.thumbnail(still)
        still.recycle()
        val out = media.newFile("mp4")
        val durationMs = 4_000L
        val ok = runCatching { encode(out, width, height, durationMs) }
            .onFailure { Log.i("MaMi.Demo", "no video encoder here: ${it.message}") }
            .isSuccess
        if (!ok) out.delete()
        return PreparedMedia(
            MediaType.VIDEO,
            if (ok) out.name else "",
            "video/mp4",
            null,
            if (ok) out.length() else 2_400_000,
            width,
            height,
            durationMs = durationMs,
            thumbnail = thumb,
        )
    }

    /** A one-page PDF with a trip plan. */
    fun document(): PreparedMedia {
        val out = runCatching {
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                color = Color.rgb(209, 47, 180)
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f }
            canvas.drawText("Our weekend in Pokhara", 48f, 80f, title)
            listOf(
                "Friday   6:30 pm  bus from Kathmandu",
                "Saturday sunrise at Sarangkot",
                "         boat on Phewa lake",
                "         momo at the lakeside place",
                "Sunday   paragliding (if you're brave)",
                "         slow breakfast, then home",
            ).forEachIndexed { i, line -> canvas.drawText(line, 48f, 130f + i * 26f, body) }
            pdf.finishPage(page)
            val file = media.newFile("pdf")
            file.outputStream().use { pdf.writeTo(it) }
            pdf.close()
            file
        }.getOrElse {
            media.newFile("txt").apply { writeText("Our weekend in Pokhara\n\nSaturday: sunrise at Sarangkot, boat on Phewa lake, momo.\n") }
        }
        val pdf = out.extension == "pdf"
        return PreparedMedia(
            MediaType.FILE,
            out.name,
            if (pdf) "application/pdf" else "text/plain",
            if (pdf) "Pokhara weekend.pdf" else "Pokhara weekend.txt",
            out.length(),
        )
    }

    /** A small preview image for a link, base64. */
    fun linkImage(): String {
        val bitmap = Bitmap.createBitmap(360, 200, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Scene.BEACH, 360f, 200f, 0f)
        val bytes = java.io.ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun draw(canvas: Canvas, scene: Scene, w: Float, h: Float, progress: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (scene) {
            Scene.SUNSET -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(54, 32, 110), Color.rgb(214, 70, 143), Color.rgb(255, 162, 95)), floatArrayOf(0f, 0.55f, 0.8f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                val sunY = h * (0.62f + 0.08f * progress)
                paint.shader = RadialGradient(w * 0.5f, sunY, w * 0.32f, intArrayOf(Color.argb(255, 255, 236, 170), Color.argb(0, 255, 180, 120)), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(w * 0.5f, sunY, w * 0.32f, paint)
                paint.shader = null
                paint.color = Color.rgb(255, 240, 200)
                canvas.drawCircle(w * 0.5f, sunY, w * 0.11f, paint)
                mountains(canvas, w, h, h * 0.66f, Color.rgb(92, 40, 110))
                mountains(canvas, w, h, h * 0.74f, Color.rgb(48, 22, 70))
                paint.color = Color.argb(70, 255, 220, 200)
                for (i in 0 until 5) canvas.drawRect(w * 0.3f, h * (0.8f + i * 0.03f), w * 0.7f, h * (0.8f + i * 0.03f) + 3f, paint)
            }
            Scene.BEACH -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, Color.rgb(46, 139, 255), Color.rgb(170, 225, 255), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h * 0.6f, paint)
                paint.shader = LinearGradient(0f, h * 0.55f, 0f, h * 0.8f, Color.rgb(0, 160, 200), Color.rgb(64, 224, 208), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h * 0.55f, w, h * 0.8f, paint)
                paint.shader = null
                paint.color = Color.rgb(244, 220, 170)
                canvas.drawRect(0f, h * 0.78f, w, h, paint)
                paint.color = Color.rgb(255, 245, 200)
                canvas.drawCircle(w * 0.78f, h * 0.18f, w * 0.08f, paint)
                paint.color = Color.argb(200, 255, 255, 255)
                for (i in 0 until 3) canvas.drawRoundRect(w * (0.1f + i * 0.25f), h * (0.12f + i * 0.04f), w * (0.3f + i * 0.25f), h * (0.16f + i * 0.04f), 40f, 40f, paint)
            }
            Scene.NIGHT -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(8, 12, 40), Color.rgb(40, 30, 90), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
                val random = Random(3)
                paint.color = Color.WHITE
                repeat(140) { canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h * 0.75f, 1f + random.nextFloat() * 2.5f, paint) }
                paint.shader = RadialGradient(w * 0.68f, h * 0.24f, w * 0.25f, Color.argb(120, 255, 255, 230), Color.argb(0, 255, 255, 230), Shader.TileMode.CLAMP)
                canvas.drawCircle(w * 0.68f, h * 0.24f, w * 0.25f, paint)
                paint.shader = null
                paint.color = Color.rgb(255, 250, 225)
                canvas.drawCircle(w * 0.68f, h * 0.24f, w * 0.1f, paint)
                mountains(canvas, w, h, h * 0.8f, Color.rgb(14, 16, 36))
            }
        }
    }

    private fun mountains(canvas: Canvas, w: Float, h: Float, base: Float, color: Int) {
        val path = Path().apply {
            moveTo(0f, h)
            lineTo(0f, base)
            var x = 0f
            var up = true
            val random = Random(base.toInt())
            while (x < w) {
                x += w * (0.08f + random.nextFloat() * 0.12f)
                lineTo(x, base + (if (up) -1 else 1) * h * (0.03f + random.nextFloat() * 0.06f))
                up = !up
            }
            lineTo(w, h)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    /** Draws frames onto the encoder's input surface and muxes them into an MP4. */
    private fun encode(out: File, width: Int, height: Int, durationMs: Long) {
        val fps = 30
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val frames = (durationMs * fps / 1000).toInt()

        fun drain(endOfStream: Boolean) {
            if (endOfStream) codec.signalEndOfInputStream()
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++idle > 200) return
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing && buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (frame in 0 until frames) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    draw(canvas, Scene.SUNSET, width.toFloat(), height.toFloat(), frame / frames.toFloat())
                    // A soft heart drifting up, so it's clearly moving.
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 120, 170) }
                    val y = height * (0.9f - 0.6f * frame / frames)
                    canvas.drawCircle(width * 0.46f, y, 18f, paint)
                    canvas.drawCircle(width * 0.54f, y, 18f, paint)
                    canvas.drawPath(Path().apply {
                        moveTo(width * 0.42f, y + 6f)
                        lineTo(width * 0.5f, y + 44f)
                        lineTo(width * 0.58f, y + 6f)
                        close()
                    }, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                // A canvas surface stamps frames with the time they're posted, so post in real time.
                Thread.sleep(1000L / fps)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
        check(out.length() > 0) { "empty video" }
    }
}
