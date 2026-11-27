package dev.amwear.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.InputDevice
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.amwear.shared.Playback
import dev.amwear.shared.PlaybackLink
import dev.amwear.shared.WatchOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sign

class NowPlayingActivity : ComponentActivity() {
    private lateinit var screen: PlayerScreen
    private val data by lazy { Wearable.getDataClient(this) }
    private val capabilities by lazy { Wearable.getCapabilityClient(this) }
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private var active = false
    private var playback = Playback()
    private var sourceNode: String? = null
    private var phoneNodes = emptySet<String>()
    private var artworkId = ""
    private var artworkBitmap: Bitmap? = null
    private var loadingArtworkId: String? = null
    private var artworkJob = 0
    private var downX = 0f
    private var downY = 0f
    private var swipeHandled = false
    private var rotaryAmount = 0f
    private var forwardDirection = 0f
    private var preview: PlaybackPreview? = null
    private var ambientDetails: AmbientLifecycleObserver.AmbientDetails? = null
    private var coverTouch = false
    private val options by lazy { WatchOptions.preferences(this) }
    private val optionListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        hideIfPaused()
    }
    private val taps by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (!screen.controlsVisible && options.getBoolean(WatchOptions.TAP, false)) {
                    send(PlaybackLink.PAUSE)
                }
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!screen.controlsVisible && options.getBoolean(WatchOptions.DOUBLE_TAP, false)) {
                    send(PlaybackLink.NEXT)
                }
                return true
            }
        })
    }

    private val timeTick = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private fun updateTime() {
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        val locale = resources.configuration.locales[0]
        val now = Date()
        screen.setTime(SimpleDateFormat(pattern, locale).format(now))
        val details = ambientDetails ?: return
        screen.setAmbient(true, details.deviceHasLowBitAmbient)
        val minute = now.time / 60_000L
        if (details.burnInProtectionRequired) screen.shiftAmbientContent(minute)
    }

    private val playbackObserver: (String, Playback?) -> Unit = { node, state ->
        if (active || ambientDetails != null) {
            if (state != null) {
                receive(node, state)
            } else if (node == sourceNode) {
                sourceNode = null
                playback = Playback()
                showPlayback()
            }
        }
    }
    private val phoneListener = CapabilityClient.OnCapabilityChangedListener { capability ->
        if (active) applyPhones(capability.nodes.map { it.id }.toSet())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = PlayerScreen(this, ::send) { screen.setPage(!screen.controlsVisible) }
        setContentView(screen)
        screen.setPage(savedInstanceState?.getBoolean("controls") ?: false)
        screen.requestFocus()
        lifecycle.addObserver(AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                this@NowPlayingActivity.ambientDetails = ambientDetails
                visible = true
                main.removeCallbacks(timeTick)
                updateTime()
                preview?.onAmbient(screen) { this@NowPlayingActivity.ambientDetails != null && !isDestroyed }
            }

            override fun onUpdateAmbient() {
                updateTime()
                if (preview == null) readState()
            }

            override fun onExitAmbient() {
                ambientDetails = null
                visible = active
                screen.setAmbient(false)
                main.removeCallbacks(timeTick)
                if (active) timeTick.run()
            }
        }))
        PlaybackAlerts.setup(this)
        preview = PlaybackPreview.open(this, intent)
        preview?.show(screen)?.let { playback = it }
        options.registerOnSharedPreferenceChangeListener(optionListener)
        if (preview == null) PlaybackAlerts.observe(playbackObserver)
        if (preview == null && savedInstanceState == null && intent.action == Intent.ACTION_MAIN) {
            askForAlerts()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlaybackAlerts.dismiss(this)
    }

    override fun onStart() {
        super.onStart()
        active = true
        visible = true
        PlaybackAlerts.dismiss(this)
        if (ambientDetails == null) timeTick.run() else updateTime()
        if (preview != null) return
        data.getDataItems(android.net.Uri.parse("wear:" + WatchOptions.PATH), DataClient.FILTER_LITERAL)
            .addOnSuccessListener { items ->
                try {
                    items.forEach { WatchOptions.receive(this, DataMapItem.fromDataItem(it).dataMap) }
                } finally {
                    items.release()
                }
            }
        readState()
        capabilities.addListener(phoneListener, PlaybackLink.PHONE_CAPABILITY)
        capabilities.getCapability(PlaybackLink.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(this) { if (active) applyPhones(it.nodes.map { node -> node.id }.toSet()) }
            .addOnFailureListener(this) { if (active) applyPhones(emptySet()) }
    }

    override fun onStop() {
        active = false
        visible = ambientDetails != null
        capabilities.removeListener(phoneListener, PlaybackLink.PHONE_CAPABILITY)
        main.removeCallbacks(timeTick)
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackAlerts.removeObserver(playbackObserver)
        options.unregisterOnSharedPreferenceChangeListener(optionListener)
        if (ambientDetails != null) visible = false
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("controls", screen.controlsVisible)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotaryAmount += event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (abs(rotaryAmount) >= 0.5f) {
                val direction = rotaryAmount.sign
                rotaryAmount = 0f
                if (forwardDirection == 0f) {
                    forwardDirection = if (screen.controlsVisible) -direction else direction
                }
                if (!screen.controlsVisible && direction == forwardDirection) {
                    screen.setPage(true)
                } else if (screen.controlsVisible && direction != forwardDirection) {
                    screen.setPage(false)
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                coverTouch = !screen.controlsVisible && ambientDetails == null &&
                    (options.getBoolean(WatchOptions.TAP, false) || options.getBoolean(WatchOptions.DOUBLE_TAP, false))
                downX = event.x
                downY = event.y
                swipeHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeHandled) return true
                val dy = event.y - downY
                val dx = event.x - downX
                val threshold = ViewConfiguration.get(this).scaledTouchSlop * 3
                if (abs(dy) > threshold && abs(dy) > abs(dx) && (dy < 0) != screen.controlsVisible) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    if (coverTouch) taps.onTouchEvent(cancel)
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    screen.setPage(dy < 0)
                    swipeHandled = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swipeHandled) return true
        }
        if (coverTouch) {
            taps.onTouchEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun askForAlerts() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun applyPhones(nodes: Set<String>) {
        val newNodes = nodes - phoneNodes
        phoneNodes = nodes
        if (sourceNode !in nodes && nodes.isNotEmpty()) {
            sourceNode = null
            playback = Playback()
            readState()
        }
        showPlayback()
        newNodes.forEach { node -> Wearable.getMessageClient(this).sendMessage(node, PlaybackLink.REFRESH, null) }
    }

    private fun readState() {
        data.getDataItems(PlaybackLink.stateUri, DataClient.FILTER_LITERAL)
            .addOnSuccessListener { items ->
                try {
                    if (active || ambientDetails != null) items.forEach(::receive)
                } finally {
                    items.release()
                }
            }
    }

    private fun receive(item: DataItem) {
        val node = item.uri.host ?: return
        receive(node, Playback.from(DataMapItem.fromDataItem(item).dataMap))
    }

    private fun receive(node: String, incoming: Playback) {
        if (phoneNodes.isNotEmpty() && node !in phoneNodes) return
        if (incoming.revision <= playback.revision &&
            (sourceNode == node || sourceNode in phoneNodes || phoneNodes.isEmpty())
        ) return
        playback = incoming
        sourceNode = node
        showPlayback()
    }

    private fun showPlayback() {
        hideIfPaused()
        val sourceReachable = sourceNode in phoneNodes
        screen.setPlayback(playback, sourceReachable || (sourceNode == null && phoneNodes.isNotEmpty()))
        if (sourceReachable) PlaybackAlerts.updateOngoing(this, playback, fromScreen = true)
        val asset = playback.artwork
        if (asset == null) {
            cancelArtworkRequest()
            if (!playback.artworkPending) showArtwork("", null)
            return
        }

        val nextArtworkId = playback.artworkId
        if (nextArtworkId == artworkId && artworkBitmap != null) {
            cancelArtworkRequest()
            showAmbientTrack()
            return
        }
        if (nextArtworkId == loadingArtworkId) return
        val job = ++artworkJob
        loadingArtworkId = nextArtworkId
        data.getFdForAsset(asset).addOnSuccessListener { response ->
            if (isDestroyed || job != artworkJob) {
                response.release()
                return@addOnSuccessListener
            }
            decoder.execute {
                val bitmap = runCatching { BitmapFactory.decodeStream(response.inputStream) }
                    .onFailure { Log.w("AMWear", "Artwork decoding failed", it) }.getOrNull()
                response.release()
                main.post {
                    if (!isDestroyed && job == artworkJob) {
                        showArtwork(nextArtworkId, bitmap)
                    }
                }
            }
        }.addOnFailureListener {
            if (!isDestroyed && job == artworkJob) showArtwork("", null)
            Log.d("AMWear", "Artwork sync failed", it)
        }
    }

    private fun hideIfPaused() {
        if ((active || ambientDetails != null) && playback.available && playback.paused &&
            options.getBoolean(WatchOptions.HIDE, false)
        ) {
            moveTaskToBack(true)
        }
    }

    private fun cancelArtworkRequest() {
        if (loadingArtworkId == null) return
        artworkJob++
        loadingArtworkId = null
    }

    private fun showArtwork(id: String, bitmap: Bitmap?) {
        loadingArtworkId = null
        artworkId = if (bitmap == null) "" else id
        artworkBitmap = bitmap
        screen.setCover(bitmap)
        showAmbientTrack()
    }

    private fun showAmbientTrack() {
        if (playback.available) {
            screen.setAmbientTrack(playback.title.ifBlank { "Apple Music" }, playback.artist, artworkBitmap)
        } else {
            screen.setAmbientTrack("", "", null)
        }
    }

    private fun send(path: String) {
        preview?.let {
            it.command(path)
            playback = it.show(screen)
            hideIfPaused()
            return
        }
        val node = sourceNode
        if (node == null || node !in phoneNodes) return
        Wearable.getMessageClient(this).sendMessage(node, path, null)
            .addOnFailureListener(this) {
                Toast.makeText(this, "Команда не отправлена", Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        @Volatile
        var visible = false
            private set
    }
}
