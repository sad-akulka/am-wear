package dev.amwear.watch

import android.content.Context
import android.content.Intent

internal class PlaybackPreview private constructor() {
    fun show(screen: PlayerScreen) = Unit
    fun onAmbient(screen: PlayerScreen, isAmbient: () -> Boolean) = Unit
    fun command(path: String) = Unit

    companion object {
        fun open(context: Context, intent: Intent): PlaybackPreview? = null
    }
}
