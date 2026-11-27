package dev.amwear.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.amwear.shared.Playback
import dev.amwear.shared.PlaybackLink
import java.util.concurrent.Executors

class MusicListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val artworkWorker = Executors.newSingleThreadExecutor()
    private val artworkLoader by lazy { ArtworkLoader(this) }
    private val sessions by lazy { getSystemService(MediaSessionManager::class.java) }
    private val component by lazy { ComponentName(this, MusicListener::class.java) }
    private var controller: MediaController? = null
    private var connected = false
    private var metadata: MediaMetadata? = null
    private var artwork: Cover? = null
    private var artworkPending = false
    private var artworkJob = 0
    private var startedAt = 0L
    private var published: Playback? = null

    private val sessionList = MediaSessionManager.OnActiveSessionsChangedListener { bind(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(value: MediaMetadata?) {
            metadata = value
            artwork = null
            loadArtwork()
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onSessionDestroyed() {
            bind(emptyList())
            refreshSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
        try {
            sessions.addOnActiveSessionsChangedListener(sessionList, component, main)
            refreshSessions()
        } catch (error: SecurityException) {
            Log.w(TAG, "Media-session access unavailable", error)
            disconnect()
        }
    }

    override fun onListenerDisconnected() {
        disconnect()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        disconnect()
        artworkWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!connected || sbn.packageName != PlaybackLink.APPLE_MUSIC) return
        if (controller == null) refreshSessions()
        if (artwork == null && controller != null && sbn.notification.isMediaNotification()) {
            loadArtwork(sbn.notification)
        }
    }

    private fun disconnect() {
        if (!connected) return
        connected = false
        if (instance === this) instance = null
        sessions.removeOnActiveSessionsChangedListener(sessionList)
        bind(emptyList())
    }

    private fun refreshSessions() {
        if (!connected) return
        try {
            bind(sessions.getActiveSessions(component))
        } catch (error: SecurityException) {
            Log.w(TAG, "Media-session access lost", error)
            disconnect()
        }
    }

    private fun bind(active: List<MediaController>) {
        val music = active.firstOrNull { it.packageName == PlaybackLink.APPLE_MUSIC }
        if (controller?.sessionToken != music?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = music
            metadata = music?.metadata
            artwork = null
            music?.registerCallback(callback, main)
            loadArtwork()
        }
        publish()
    }

    private fun loadArtwork(notification: Notification? = null) {
        val job = ++artworkJob
        artworkPending = controller != null
        if (!artworkPending) return
        val source = metadata
        val mediaNotification = notification ?: activeNotifications.orEmpty()
            .firstOrNull { it.packageName == PlaybackLink.APPLE_MUSIC && it.notification.isMediaNotification() }
            ?.notification
        artworkWorker.execute {
            val cover = runCatching { artworkLoader.read(source, mediaNotification) }
                .onFailure { Log.d(TAG, "Artwork unavailable", it) }.getOrNull()
            main.post {
                if (job == artworkJob && connected) {
                    artwork = cover
                    artworkPending = false
                    publish()
                }
            }
        }
    }

    private fun Notification.isMediaNotification() = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

    private fun publish(force: Boolean = false) {
        val state = controller?.playbackState
        val playing = when (state?.state) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> published?.playing == true
            else -> false
        }
        val justStarted = playing && published?.playing != true
        if (justStarted) startedAt = nextRevision()
        if (!playing) startedAt = 0

        val snapshot = Playback(
            available = controller != null,
            playing = playing,
            paused = state?.state == PlaybackState.STATE_PAUSED,
            title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            actions = state?.actions ?: 0L,
            artworkId = artwork?.id.orEmpty(),
            artwork = artwork?.asset,
            artworkPending = artworkPending,
            startedAt = startedAt,
        )
        if (!force && published == snapshot) return
        published = snapshot
        val autoOpen = preferences(this).getBoolean(AUTO_OPEN, true)
        Wearable.getDataClient(this).putDataItem(snapshot.copy(revision = nextRevision()).request())
            .addOnSuccessListener {
                if (justStarted && autoOpen && startedAt == snapshot.startedAt) {
                    openWatch(snapshot.startedAt)
                }
            }
            .addOnFailureListener {
                if (published === snapshot) published = null
                Log.w(TAG, "Playback sync failed", it)
            }
    }

    private fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
        metadata?.getText(key)?.toString()?.takeIf(String::isNotBlank)
    }.orEmpty()

    private fun openWatch(start: Long) {
        Wearable.getCapabilityClient(this)
            .getCapability(PlaybackLink.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                if (startedAt != start || System.currentTimeMillis() - start > 15_000) {
                    return@addOnSuccessListener
                }
                val intent = Intent(Intent.ACTION_VIEW, PlaybackLink.openUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val helper = RemoteActivityHelper(this, mainExecutor)
                capability.nodes.filter { it.isNearby }.forEach { node ->
                    val result = helper.startRemoteActivity(intent, node.id)
                    result.addListener({
                        runCatching { result.get() }.onFailure {
                            Log.i(TAG, "Watch did not accept auto-open", it)
                        }
                    }, mainExecutor)
                }
            }
    }

    fun command(path: String) {
        if (path == PlaybackLink.REFRESH) {
            refreshSessions()
            publish(force = true)
            return
        }
        val music = controller ?: return
        val actions = music.playbackState?.actions ?: return
        val controls = music.transportControls
        when (path) {
            PlaybackLink.PREVIOUS -> if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) controls.skipToPrevious()
            PlaybackLink.NEXT -> if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) controls.skipToNext()
            PlaybackLink.PLAY -> when {
                actions and PlaybackState.ACTION_PLAY != 0L -> controls.play()
                actions and PlaybackState.ACTION_PLAY_PAUSE != 0L && published?.playing != true -> toggle(music)
            }
            PlaybackLink.PAUSE -> when {
                actions and PlaybackState.ACTION_PAUSE != 0L -> controls.pause()
                actions and PlaybackState.ACTION_PLAY_PAUSE != 0L && published?.playing == true -> toggle(music)
            }
        }
    }

    private fun toggle(music: MediaController) {
        music.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        music.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    companion object {
        private const val TAG = "AMWear"
        var instance: MusicListener? = null
            private set
        private var lastRevision = 0L

        fun nextRevision(): Long {
            lastRevision = maxOf(System.currentTimeMillis(), lastRevision + 1)
            return lastRevision
        }
    }
}

class WatchCommands : com.google.android.gms.wearable.WearableListenerService() {
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        Handler(Looper.getMainLooper()).post {
            val listener = MusicListener.instance
            if (listener != null) {
                listener.command(event.path)
            } else if (event.path == PlaybackLink.REFRESH) {
                Wearable.getDataClient(this).putDataItem(Playback(revision = MusicListener.nextRevision()).request())
            }
        }
    }
}
