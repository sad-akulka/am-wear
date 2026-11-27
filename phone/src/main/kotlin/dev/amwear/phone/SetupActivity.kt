package dev.amwear.phone

import androidx.appcompat.app.AppCompatActivity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import dev.amwear.shared.WatchOptions
import android.widget.TextView
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.amwear.shared.PlaybackLink
import kotlin.math.roundToInt

internal const val AUTO_OPEN = "autoOpen"
internal fun preferences(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

class SetupActivity : AppCompatActivity() {
    private lateinit var access: TextView
    private lateinit var accessButton: Button
    private lateinit var watch: TextView
    private val capabilities by lazy { Wearable.getCapabilityClient(this) }
    private val watchListener = CapabilityClient.OnCapabilityChangedListener { info ->
        showWatch(info.nodes.isNotEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val lightTheme = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val scroll = ScrollView(this).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            panel.setPadding(dp(24), dp(32) + top, dp(24), dp(32) + bottom)
            insets
        }
        scroll.addView(panel)
        val title = label("AM Wear", 40f, themeColor(com.google.android.material.R.attr.colorOnSurface)).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        panel.addView(label("Apple Music на часах", 17f))

        val connection = card(panel)
        access = label("", 16f, themeColor(com.google.android.material.R.attr.colorOnSurface))
        connection.addView(access)
        connection.addView(label("Доступ нужен, чтобы получать обложку и управлять Apple Music.", 14f))
        accessButton = MaterialButton(this).apply {
            text = "Настроить доступ"
            isAllCaps = false


            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        connection.addView(accessButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        watch = label("Поиск часов…", 15f)
        connection.addView(watch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val behavior = card(panel)
        behavior.addView(MaterialSwitch(this).apply {
            text = "Открывать экран на часах"
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            textSize = 16f
            minHeight = dp(48)
            switchPadding = dp(12)
            isChecked = preferences(this@SetupActivity).getBoolean(AUTO_OPEN, true)
            setOnCheckedChangeListener { _, checked ->
                preferences(this@SetupActivity).edit().putBoolean(AUTO_OPEN, checked).apply()
            }
        })
        behavior.addView(label(
            "При запуске Apple Music часы получат уведомление, если экран ещё не открыт. Разрешите уведомления в AM Wear на часах.",
            14f,
        ))
        behavior.addView(label(
            "Если открывается штатный плеер, выключите автозапуск медиаконтролов в системных настройках часов. Переключатель AM Wear можно оставить включённым.",
            14f,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val gestures = card(panel)
        option(gestures, "Касание обложки — пауза", WatchOptions.TAP)
        option(gestures, "Двойное касание — следующий трек", WatchOptions.DOUBLE_TAP)
        option(gestures, "Скрывать приложение при паузе", WatchOptions.HIDE)
        gestures.addView(label("Жесты работают на экране обложки. После паузы часы вернутся к циферблату.", 14f))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        WatchOptions.sync(this)
        val granted = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MusicListener::class.java))
        access.text = if (granted) "Доступ к уведомлениям включён" else "Нужен доступ к уведомлениям"
        accessButton.visibility = if (granted) View.GONE else View.VISIBLE
        capabilities.addListener(watchListener, PlaybackLink.WATCH_CAPABILITY)
        capabilities.getCapability(PlaybackLink.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(this) { showWatch(it.nodes.isNotEmpty()) }
            .addOnFailureListener(this) { showWatch(false) }
    }

    override fun onPause() {
        capabilities.removeListener(watchListener, PlaybackLink.WATCH_CAPABILITY)
        super.onPause()
    }

    private fun option(parent: LinearLayout, title: String, key: String) {
        parent.addView(MaterialSwitch(this).apply {
            text = title
            textSize = 16f
            minHeight = dp(64)
            switchPadding = dp(12)
            isChecked = WatchOptions.preferences(this@SetupActivity).getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                WatchOptions.preferences(this@SetupActivity).edit().putBoolean(key, checked).apply()
                WatchOptions.sync(this@SetupActivity).addOnFailureListener {
                    android.widget.Toast.makeText(this@SetupActivity, "Настройка сохранена. Синхронизация повторится при открытии приложения.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun themeColor(attribute: Int) = MaterialColors.getColor(this, attribute, "AM Wear")

    private fun showWatch(connected: Boolean) {
        watch.text = if (connected) "Часы подключены" else "Установите AM Wear на сопряжённые часы"
    }

    private fun label(value: String, size: Float, color: Int = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(themeColor(com.google.android.material.R.attr.colorSurfaceContainer), 22)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
