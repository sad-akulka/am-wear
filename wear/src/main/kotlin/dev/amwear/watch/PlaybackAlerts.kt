package dev.amwear.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dev.amwear.shared.Playback
import dev.amwear.shared.PlaybackLink
import dev.amwear.shared.WatchOptions

class PlaybackAlerts : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val manager = getSystemService(NotificationManager::class.java)
        val seen = getSharedPreferences("playbackAlerts", MODE_PRIVATE)
        events.forEach { event ->
            val item = event.dataItem
            if (item.uri.path == WatchOptions.PATH) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    WatchOptions.receive(this, DataMapItem.fromDataItem(item).dataMap)
                }
                return@forEach
            }
            if (item.uri.path != PlaybackLink.STATE_PATH) return@forEach
            val source = item.uri.host ?: return@forEach
            if (event.type == DataEvent.TYPE_DELETED) {
                deliver(source, null)
                dismiss(this)
                updateOngoing(this, Playback())
                return@forEach
            }
            val state = Playback.from(DataMapItem.fromDataItem(item).dataMap)
            deliver(source, state)
            updateOngoing(this, state)
            if (!state.playing) {
                dismiss(this)
                return@forEach
            }
            val start = state.startedAt
            if (start <= seen.getLong(source, 0) || System.currentTimeMillis() - start !in -5_000L..30_000L) {
                return@forEach
            }
            seen.edit().putLong(source, start).apply()
            if (NowPlayingActivity.visible || !manager.areNotificationsEnabled()) return@forEach
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@forEach
            setup(this)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(dev.amwear.shared.R.drawable.ic_note)
                .setContentTitle(state.title.ifBlank { "Apple Music" })
                .setContentText(state.artist.ifBlank { "Открыть обложку и управление" })
                .setContentIntent(openPlayer(this))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setTimeoutAfter(120_000)
                .build()
            manager.notify(NOTIFICATION, notification)
            if (NowPlayingActivity.visible) dismiss(this)
        }
    }

    companion object {
        private const val CHANNEL = "playback_start"
        private const val NOTIFICATION = 20
        private const val ONGOING_CHANNEL = "active_playback"
        private const val ONGOING_NOTIFICATION = 21
        private var currentTrack: Pair<String, String>? = null
        private val main = Handler(Looper.getMainLooper())
        private val playbackObservers = mutableSetOf<(String, Playback?) -> Unit>()

        // The service receives updates even while an activity is in always-on mode.
        internal fun observe(observer: (String, Playback?) -> Unit) {
            playbackObservers.add(observer)
        }

        internal fun removeObserver(observer: (String, Playback?) -> Unit) {
            playbackObservers.remove(observer)
        }

        private fun deliver(source: String, state: Playback?) {
            main.post { playbackObservers.forEach { it(source, state) } }
        }

        fun setup(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Запуск Apple Music", NotificationManager.IMPORTANCE_DEFAULT)
            channel.setSound(null, null)
            val ongoing = NotificationChannel(ONGOING_CHANNEL, "Текущий трек", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, ongoing))
        }

        @Synchronized
        fun updateOngoing(context: Context, state: Playback, fromScreen: Boolean = false) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!state.playing) {
                if (currentTrack != null || manager.activeNotifications.any { it.id == ONGOING_NOTIFICATION }) {
                    manager.cancel(ONGOING_NOTIFICATION)
                    currentTrack = null
                }
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!manager.areNotificationsEnabled()) return

            val posted = manager.activeNotifications.any { it.id == ONGOING_NOTIFICATION }
            if (!fromScreen && !posted) return
            val track = state.title.ifBlank { "Apple Music" } to state.artist
            if (track == currentTrack && posted) return

            val open = openPlayer(context)
            val builder = NotificationCompat.Builder(context, ONGOING_CHANNEL)
                .setSmallIcon(dev.amwear.shared.R.drawable.ic_note)
                .setContentTitle(track.first)
                .setContentText(track.second)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setOngoing(true)
            OngoingActivity.Builder(context, ONGOING_NOTIFICATION, builder)
                .setTouchIntent(open)
                .build()
                .apply(context)
            manager.notify(ONGOING_NOTIFICATION, builder.build())
            currentTrack = track
        }

        fun dismiss(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        }

        private fun openPlayer(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
