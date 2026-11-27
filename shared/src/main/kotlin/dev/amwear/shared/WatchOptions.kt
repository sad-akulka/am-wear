package dev.amwear.shared

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WatchOptions {
    const val PATH = "/amwear/settings"
    const val TAP = "tapToPause"
    const val DOUBLE_TAP = "doubleTapNext"
    const val HIDE = "hideOnPause"
    val keys = listOf(TAP, DOUBLE_TAP, HIDE)

    fun preferences(context: Context) = context.getSharedPreferences("watchOptions", Context.MODE_PRIVATE)

    fun sync(context: Context) = Wearable.getDataClient(context).putDataItem(
        PutDataMapRequest.create(PATH).apply {
            keys.forEach { dataMap.putBoolean(it, preferences(context).getBoolean(it, false)) }
        }.asPutDataRequest().setUrgent(),
    )

    fun receive(context: Context, map: DataMap) {
        preferences(context).edit().apply {
            keys.forEach { putBoolean(it, map.getBoolean(it)) }
        }.apply()
    }
}
