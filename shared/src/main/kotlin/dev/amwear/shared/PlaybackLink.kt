package dev.amwear.shared

import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest

object PlaybackLink {
    const val APPLE_MUSIC = "com.apple.android.music"
    const val PHONE_CAPABILITY = "amwear_phone"
    const val WATCH_CAPABILITY = "amwear_watch"
    const val STATE_PATH = "/amwear/state"
    val stateUri: Uri = Uri.parse("wear:$STATE_PATH")
    val openUri: Uri = Uri.parse("amwear://now-playing")
    private const val COMMAND_PREFIX = "/amwear/command/"
    const val REFRESH = COMMAND_PREFIX + "refresh"
    const val PREVIOUS = COMMAND_PREFIX + "previous"
    const val PLAY = COMMAND_PREFIX + "play"
    const val PAUSE = COMMAND_PREFIX + "pause"
    const val NEXT = COMMAND_PREFIX + "next"
}

data class Playback(
    val available: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val actions: Long = 0,
    val artworkId: String = "",
    val artwork: Asset? = null,
    val artworkPending: Boolean = false,
    val revision: Long = 0,
    val startedAt: Long = 0,
) {
    fun request() = PutDataMapRequest.create(PlaybackLink.STATE_PATH).apply {
        dataMap.putBoolean("available", available)
        dataMap.putBoolean("playing", playing)
        dataMap.putString("title", title)
        dataMap.putString("artist", artist)
        dataMap.putLong("actions", actions)
        dataMap.putString("artworkId", artworkId)
        artwork?.let { dataMap.putAsset("artwork", it) }
        dataMap.putBoolean("artworkPending", artworkPending)
        dataMap.putLong("revision", revision)
        dataMap.putLong("startedAt", startedAt)
    }.asPutDataRequest().setUrgent()

    companion object {
        fun from(map: DataMap) = Playback(
            available = map.getBoolean("available"),
            playing = map.getBoolean("playing"),
            title = map.getString("title").orEmpty(),
            artist = map.getString("artist").orEmpty(),
            actions = map.getLong("actions"),
            artworkId = map.getString("artworkId").orEmpty(),
            artwork = map.getAsset("artwork"),
            artworkPending = map.getBoolean("artworkPending"),
            revision = map.getLong("revision"),
            startedAt = map.getLong("startedAt"),
        )
    }
}
