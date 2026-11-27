package dev.amwear.watch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.amwear.shared.Playback
import dev.amwear.shared.PlaybackLink

internal class PlaybackPreview private constructor(
    private val cover: Bitmap,
    private val advanceInAmbient: Boolean,
) {
    private var playing = true
    private var original = true
    private val secondCover by lazy {
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, 0f, 512f, 0xff142449.toInt(), 0xff9e67b5.toInt(), Shader.TileMode.CLAMP,
            )
            canvas.drawPaint(paint)
            paint.shader = null
            paint.color = 0xffd1c3ff.toInt()
            canvas.drawCircle(315f, 176f, 90f, paint)
            paint.color = 0xff382659.toInt()
            canvas.drawCircle(247f, 579f, 356f, paint)
            paint.color = 0xff201834.toInt()
            canvas.drawCircle(402f, 685f, 411f, paint)
        }
    }

    fun show(screen: PlayerScreen): Playback {
        val title = if (original) "Seoul City" else "After Hours"
        val artist = if (original) "Jennie" else "AM Wear · демо"
        val artwork = if (original) cover else secondCover
        val state = Playback(
                available = true,
                playing = playing,
                paused = !playing,
                title = title,
                artist = artist,
                actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
        screen.setPlayback(state, phoneReachable = true)
        screen.setCover(artwork)
        screen.setAmbientTrack(title, artist, artwork)
        return state
    }

    fun onAmbient(screen: PlayerScreen, isAmbient: () -> Boolean) {
        if (!advanceInAmbient) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAmbient()) {
                command(PlaybackLink.NEXT)
                show(screen)
            }
        }, 6_000L)
    }

    fun command(path: String) {
        when (path) {
            PlaybackLink.PLAY -> playing = true
            PlaybackLink.PAUSE -> playing = false
            PlaybackLink.NEXT, PlaybackLink.PREVIOUS -> original = !original
        }
    }

    companion object {
        fun open(context: Context, intent: Intent): PlaybackPreview? {
            if (!intent.getBooleanExtra("amwear_preview", false)) return null
            return PlaybackPreview(
                BitmapFactory.decodeResource(context.resources, R.drawable.preview_cover),
                intent.getBooleanExtra("amwear_preview_advance_ambient", false),
            )
        }
    }
}
