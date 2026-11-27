package dev.amwear.watch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.media.session.PlaybackState
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import dev.amwear.shared.Playback
import dev.amwear.shared.PlaybackLink
import kotlin.math.roundToInt

internal class PlayerScreen(
    context: Context,
    private val command: (String) -> Unit,
    private val togglePage: () -> Unit,
) : FrameLayout(context) {
    private val sharpBackground = ArtworkLayer(context, intArrayOf(
        0x72000000, 0x0a000000, Color.TRANSPARENT, 0x52000000, 0xc5000000.toInt(),
    ))
    private val softBackground = ArtworkLayer(context, intArrayOf(
        0x380c0710, 0x620c0710, 0xbd0c0710.toInt(),
    ))
    private val coverPanel = FrameLayout(context)
    private val controlPanel = FrameLayout(context)
    private val titleTypeface = resources.getFont(R.font.outfit_semibold)
    private val clockTypeface = resources.getFont(R.font.outfit_black)
    private val ambientClock = text(18f).apply {
        typeface = clockTypeface
        textScaleX = 1.12f
        letterSpacing = -0.035f
    }
    private val ambientCover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.40f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val ambientTitle = text(16f).apply {
        typeface = titleTypeface
        setTextColor(0xffbdbdbd.toInt())
    }
    private val ambientArtist = text(12f, "sans-serif", Typeface.BOLD)
    private val ambientContent = FrameLayout(context).apply {
        listOf(ambientClock, ambientTitle, ambientArtist).forEach(::addView)
    }
    private val ambientPanel = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(ambientCover, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x60000000, Color.TRANSPARENT, 0x83000000.toInt()),
            )
        }, LayoutParams(-1, -1))
        addView(ambientContent, LayoutParams(-1, -1))
        visibility = View.GONE
    }
    private val coverClock = text(18f).apply {
        typeface = clockTypeface
        textScaleX = 1.12f
        letterSpacing = -0.035f
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xc4000000.toInt())
    }
    private val coverTitle = text(16f).apply {
        typeface = titleTypeface
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xc4000000.toInt())
    }
    private val coverArtist = text(12f, "sans-serif", Typeface.BOLD).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val musicNote = ImageView(context).apply {
        setImageResource(dev.amwear.shared.R.drawable.ic_note)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val coverCredit = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(musicNote, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) })
        addView(coverArtist)
        setOnClickListener { togglePage() }
    }
    private val controlClock = text(12f).apply { typeface = clockTypeface
        textScaleX = 1.12f }
    private val controlTitle = text(17f).apply { typeface = titleTypeface }
    private val controlArtist = text(12f, "sans-serif", Typeface.BOLD)
    private val previous = button("Предыдущий трек", PlayerIcon.PREVIOUS) { command(PlaybackLink.PREVIOUS) }
    private val next = button("Следующий трек", PlayerIcon.NEXT) { command(PlaybackLink.NEXT) }
    private val play = button("Играть", PlayerIcon.PLAY) {
        command(if (playback.playing) PlaybackLink.PAUSE else PlaybackLink.PLAY)
    }
    private val pages = button("Показать обложку", PlayerIcon.SECOND_PAGE) { togglePage() }.apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private var playback = Playback()
    private var reachable = false
    private var cover: Bitmap? = null
    private var blurred: Bitmap? = null
    private var artTransition: ValueAnimator? = null
    private var queuedCover: (() -> Unit)? = null
    private var pageTransition: ValueAnimator? = null
    private var pageProgress = 0f
    private var ambient = false
    private var lowBitAmbient = false
    private var ambientArtwork: Bitmap? = null
    private var ambientAccent = DEFAULT_ACCENT
    private var accent = DEFAULT_ACCENT
    private var fadeFromAccent = DEFAULT_ACCENT
    private var fadeToAccent = DEFAULT_ACCENT
    private val colors = ArgbEvaluator()
    private var viewportWidth = 0
    private var viewportHeight = 0
    var controlsVisible = false
        private set

    init {
        isFocusableInTouchMode = true
        listOf(sharpBackground, softBackground, coverPanel, controlPanel, ambientPanel)
            .forEach { addView(it, LayoutParams(-1, -1)) }
        listOf(coverClock, coverTitle, coverCredit).forEach(coverPanel::addView)
        listOf(controlClock, controlTitle, controlArtist, previous, play, next, pages).forEach(controlPanel::addView)
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = dp(18).toFloat()
            val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            softBackground.oldImage.setRenderEffect(blur)
            softBackground.newImage.setRenderEffect(blur)
        }
        play.background = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }, dp(5))
        play.foreground = InsetDrawable(pill(Color.TRANSPARENT), dp(5))
        play.setPadding(dp(14), dp(14), dp(14), dp(14))
        play.setColorFilter(0xff18101e.toInt())
        applyAccent()
        applyPage(0f)
        setPlayback(Playback(), false)
    }

    fun setTime(value: String) {
        coverClock.text = value
        controlClock.text = value
        ambientClock.text = value
    }

    fun setAmbient(enabled: Boolean, lowBit: Boolean = false) {
        if (enabled == ambient && lowBit == lowBitAmbient) return
        ambient = enabled
        lowBitAmbient = lowBit
        ambientPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            listOf(ambientClock, ambientTitle, ambientArtist).forEach { it.paint.isAntiAlias = !lowBit }
            applyAmbientColors()
            layoutAmbientTrack()
            pageTransition?.cancel()
            val pending = queuedCover
            queuedCover = null
            artTransition?.cancel()
            fadeArtwork(1f)
            pending?.invoke()
        }
        applyPage(if (controlsVisible) 1f else 0f)
    }

    fun shiftAmbientContent(minute: Long) {
        val offset = dp(3).toFloat()
        val shiftX = ((minute % 3) - 1) * offset
        val shiftY = (((minute / 3) % 3) - 1) * offset
        ambientContent.translationX = shiftX
        ambientContent.translationY = shiftY
        if (viewportWidth > 0 && viewportHeight > 0) {
            ambientCover.scaleX = 1f + 2f * offset / viewportWidth
            ambientCover.scaleY = 1f + 2f * offset / viewportHeight
            ambientCover.translationX = shiftX
            ambientCover.translationY = shiftY
        }
    }

    fun setAmbientTrack(title: String, artist: String, artwork: Bitmap?) {
        val hasCover = artwork != null
        val needsLayout = ambientTitle.text.isNotBlank() != title.isNotBlank() ||
            (ambientArtwork != null) != hasCover
        if (ambientTitle.text.toString() != title) ambientTitle.text = title
        if (ambientArtist.text.toString() != artist) ambientArtist.text = artist
        if (artwork !== ambientArtwork) {
            ambientArtwork = artwork
            ambientCover.setImageBitmap(artwork)
            ambientAccent = artwork?.let(::artworkColor) ?: DEFAULT_ACCENT
            if (ambient) applyAmbientColors()
        }
        if (needsLayout && ambient) {
            layoutAmbientTrack()
            requestLayout()
        }
    }

    private fun applyAmbientColors() {
        val tint = if (lowBitAmbient) Color.WHITE else ColorUtils.blendARGB(ambientAccent, Color.BLACK, 0.27f)
        ambientClock.setTextColor(tint)
        ambientArtist.setTextColor(tint)
        ambientTitle.setTextColor(if (lowBitAmbient) Color.WHITE else 0xffbdbdbd.toInt())
    }

    private fun layoutAmbientTrack() {
        val showTrack = ambientTitle.text.isNotBlank()
        val showCover = showTrack && ambientArtwork != null && !lowBitAmbient
        ambientCover.visibility = if (showCover) View.VISIBLE else View.INVISIBLE
        ambientTitle.visibility = if (showTrack) View.VISIBLE else View.INVISIBLE
        ambientArtist.visibility = ambientTitle.visibility
        val diameter = minOf(viewportWidth, viewportHeight)
        ambientClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, diameter * 0.105f)
        place(ambientClock, diameter * 0.62f, dp(27).toFloat(), 0.50f, if (showTrack) 0.105f else 0.50f)
        place(ambientTitle, diameter * 0.83f, dp(25).toFloat(), 0.50f, 0.72f)
        place(ambientArtist, diameter * 0.70f, dp(22).toFloat(), 0.50f, 0.805f)
    }

    fun setPlayback(state: Playback, phoneReachable: Boolean) {
        playback = state
        reachable = phoneReachable
        val title = state.title.ifBlank { "Apple Music" }
        val artist = when {
            !reachable -> "Подключите телефон"
            !state.available -> "Запустите на телефоне"
            else -> state.artist
        }
        coverTitle.text = title
        coverArtist.text = artist
        coverCredit.contentDescription = if (artist.isBlank()) "Показать управление" else "$artist. Показать управление"
        controlTitle.text = title
        controlArtist.text = artist

        previous.isEnabled = can(PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        next.isEnabled = can(PlaybackState.ACTION_SKIP_TO_NEXT)
        val playAction = if (state.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        play.isEnabled = can(playAction or PlaybackState.ACTION_PLAY_PAUSE)
        play.contentDescription = if (state.playing) "Пауза" else "Играть"
        play.setImageDrawable(PlayerIcon(if (state.playing) PlayerIcon.PAUSE else PlayerIcon.PLAY))
        (listOf(previous, play, next)).forEach { it.alpha = if (it.isEnabled) 1f else 0.38f }
    }

    fun setCover(bitmap: Bitmap?) {
        if (artTransition?.isRunning == true) {
            queuedCover = if (bitmap !== cover) ({ setCover(bitmap) }) else null
            return
        }
        if (bitmap === cover) return
        sharpBackground.setImages(cover, bitmap)
        val nextBlur = if (Build.VERSION.SDK_INT < 31) bitmap?.let(::softenArtwork) else bitmap
        softBackground.setImages(blurred, nextBlur)
        cover = bitmap
        blurred = nextBlur
        fadeFromAccent = accent
        fadeToAccent = bitmap?.let(::artworkColor) ?: DEFAULT_ACCENT
        val animate = isAttachedToWindow && !ambient
        fadeArtwork(if (animate) 0f else 1f)
        if (!animate) return
        artTransition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ART_FADE_MS
            addUpdateListener { fadeArtwork(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    artTransition = null
                    val next = queuedCover
                    queuedCover = null
                    next?.invoke()
                }
            })
        }
        artTransition?.start()
    }

    private fun fadeArtwork(progress: Float) {
        sharpBackground.fade(progress, cover == null)
        softBackground.fade(progress, cover == null)
        val color = colors.evaluate(progress, fadeFromAccent, fadeToAccent) as Int
        if (color != accent) {
            accent = color
            applyAccent()
        }
    }

    private fun applyAccent() {
        coverClock.setTextColor(accent)
        coverArtist.setTextColor(accent)
        musicNote.imageTintList = ColorStateList.valueOf(accent)
        controlClock.setTextColor(accent)
        play.backgroundTintList = ColorStateList.valueOf(accent)
    }

    fun setPage(showControls: Boolean) {
        if (showControls == controlsVisible) return
        controlsVisible = showControls
        val target = if (showControls) 1f else 0f
        pageTransition?.cancel()
        if (!isAttachedToWindow || ambient) {
            applyPage(target)
            return
        }
        pageTransition = ValueAnimator.ofFloat(pageProgress, target).apply {
            duration = PAGE_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyPage(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyPage(progress: Float) {
        pageProgress = progress
        sharpBackground.visibility = if (ambient) View.INVISIBLE else View.VISIBLE
        softBackground.alpha = progress
        softBackground.visibility = if (!ambient && progress > 0f) View.VISIBLE else View.INVISIBLE
        coverPanel.alpha = 1f - progress
        controlPanel.alpha = progress
        coverPanel.translationY = -dp(6) * progress
        controlPanel.translationY = dp(8) * (1f - progress)
        coverPanel.visibility = if (!ambient && progress < 1f) View.VISIBLE else View.INVISIBLE
        controlPanel.visibility = if (!ambient && progress > 0f) View.VISIBLE else View.INVISIBLE
        coverPanel.importantForAccessibility = if (controlsVisible) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        controlPanel.importantForAccessibility = if (controlsVisible) IMPORTANT_FOR_ACCESSIBILITY_AUTO
        else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onDetachedFromWindow() {
        pageTransition?.cancel()
        queuedCover = null
        artTransition?.cancel()
        applyPage(if (controlsVisible) 1f else 0f)
        fadeArtwork(1f)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (viewportWidth != width || viewportHeight != height) {
            viewportWidth = width
            viewportHeight = height
            placeElements(minOf(width, height))
            layoutAmbientTrack()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun placeElements(diameter: Int) {
        coverClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, diameter * 0.105f)
        place(coverClock, diameter * 0.62f, dp(27).toFloat(), 0.50f, 0.105f)
        place(coverTitle, diameter * 0.83f, dp(25).toFloat(), 0.50f, 0.72f)
        place(coverCredit, diameter * 0.70f, dp(48).toFloat(), 0.50f, 0.805f)
        place(controlClock, diameter * 0.56f, dp(23).toFloat(), 0.50f, 0.22f)
        place(controlTitle, diameter * 0.81f, dp(28).toFloat(), 0.50f, 0.38f)
        place(controlArtist, diameter * 0.76f, dp(22).toFloat(), 0.50f, 0.48f)
        val outer = dp(48).toFloat()
        val center = dp(54).toFloat()
        place(previous, outer, outer, 0.24f, 0.66f)
        place(play, center, center, 0.50f, 0.66f)
        place(next, outer, outer, 0.76f, 0.66f)
        place(pages, dp(48).toFloat(), dp(48).toFloat(), 0.50f, 0.88f)
    }

    private fun place(view: View, w: Float, h: Float, x: Float, y: Float) {
        (view.layoutParams as LayoutParams).apply {
            width = w.roundToInt()
            height = h.roundToInt()
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = (viewportWidth * x - w / 2).roundToInt()
            topMargin = (viewportHeight * y - h / 2).roundToInt()
        }
    }

    private fun can(actions: Long) = reachable && playback.available && playback.actions and actions != 0L

    private fun text(size: Float, font: String = "sans-serif-medium", style: Int = Typeface.NORMAL) = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = size
        typeface = Typeface.create(font, style)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (size < 14f) 0xffe7dce9.toInt() else Color.WHITE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun button(label: String, icon: Int, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = label
        stateListAnimator = null
        backgroundTintList = null
        background = pill(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setImageDrawable(PlayerIcon(icon))
        setOnClickListener { action() }
    }

    private fun pill(color: Int) = RippleDrawable(
        ColorStateList.valueOf(0x55ffffff),
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) },
        null,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val DEFAULT_ACCENT = 0xffffb8c9.toInt()
        const val ART_FADE_MS = 360L
        const val PAGE_FADE_MS = 250L
    }
}

private class ArtworkLayer(context: Context, shade: IntArray) : FrameLayout(context) {
    val oldImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val newImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xff241928.toInt(), 0xff19111f.toInt(), 0xff0e0b14.toInt()),
        )
        addView(oldImage, LayoutParams(-1, -1))
        addView(newImage, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, shade)
        }, LayoutParams(-1, -1))
    }

    fun setImages(previous: Bitmap?, current: Bitmap?) {
        oldImage.setImageBitmap(previous)
        newImage.setImageBitmap(current)
    }

    fun fade(progress: Float, fadingOut: Boolean) {
        oldImage.alpha = if (fadingOut) 1f - progress else 1f
        newImage.alpha = progress
        if (progress >= 1f) oldImage.setImageDrawable(null)
    }
}

private class PlayerIcon(private val icon: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val bar = RectF()

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (icon) {
            PLAY -> triangle(canvas, 7f, 20f, 4f, 20f)
            PAUSE -> {
                roundedBar(canvas, 6f, 10f, 5f, 19f)
                roundedBar(canvas, 14f, 18f, 5f, 19f)
            }
            PREVIOUS -> {
                triangle(canvas, 18f, 8f, 5f, 19f)
                roundedBar(canvas, 5f, 7f, 5f, 19f)
            }
            NEXT -> {
                triangle(canvas, 6f, 16f, 5f, 19f)
                roundedBar(canvas, 17f, 19f, 5f, 19f)
            }
            SECOND_PAGE -> {
                canvas.drawCircle(8f, 12f, 1.3f, paint)
                canvas.drawCircle(16f, 12f, 2.2f, paint)
            }
        }
        canvas.restore()
    }

    private fun triangle(canvas: Canvas, edge: Float, tip: Float, top: Float, bottom: Float) {
        path.reset()
        path.moveTo(edge, top)
        path.lineTo(tip, (top + bottom) / 2f)
        path.lineTo(edge, bottom)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun roundedBar(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        bar.set(left, top, right, bottom)
        canvas.drawRoundRect(bar, 0.8f, 0.8f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val PREVIOUS = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
        const val SECOND_PAGE = 4
    }
}

private fun artworkColor(bitmap: Bitmap): Int {
    val sample = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
    val pixels = IntArray(48 * 48)
    sample.getPixels(pixels, 0, 48, 0, 0, 48, 48)
    val scores = FloatArray(24)
    val reds = FloatArray(24)
    val greens = FloatArray(24)
    val blues = FloatArray(24)
    val hsv = FloatArray(3)
    pixels.forEach { pixel ->
        Color.colorToHSV(pixel, hsv)
        if (hsv[1] > 0.12f && hsv[2] > 0.12f) {
            val bucket = ((hsv[0] + 7.5f) / 15f).toInt() % scores.size
            val weight = hsv[1] * hsv[1] * kotlin.math.sqrt(hsv[2])
            scores[bucket] += weight
            reds[bucket] += Color.red(pixel) * weight
            greens[bucket] += Color.green(pixel) * weight
            blues[bucket] += Color.blue(pixel) * weight
        }
    }
    val best = scores.indices.maxBy { scores[it] }
    val score = scores[best]
    if (sample !== bitmap) sample.recycle()
    if (score == 0f) return 0xffe0e0e0.toInt()
    val source = Color.rgb((reds[best] / score).roundToInt(),
        (greens[best] / score).roundToInt(), (blues[best] / score).roundToInt())
    Color.colorToHSV(source, hsv)
    hsv[1] = (hsv[1] * 0.34f).coerceIn(0.12f, 0.34f)
    hsv[2] = 0.97f
    return Color.HSVToColor(hsv)
}
private fun softenArtwork(bitmap: Bitmap): Bitmap {
    val width = 32
    val height = (bitmap.height * width.toFloat() / bitmap.width).roundToInt().coerceIn(1, 64)
    val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val pixels = IntArray(width * height)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    val softened = IntArray(pixels.size) { index ->
        val x = index % width
        val y = index / width
        var red = 0
        var green = 0
        var blue = 0
        for (row in y - 3..y + 3) {
            for (column in x - 3..x + 3) {
                val pixel = pixels[row.coerceIn(0, height - 1) * width + column.coerceIn(0, width - 1)]
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
        }
        Color.rgb(red / 49, green / 49, blue / 49)
    }
    return Bitmap.createBitmap(softened, width, height, Bitmap.Config.ARGB_8888)
}
