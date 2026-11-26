package dev.amwear.phone

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.net.Uri
import com.google.android.gms.wearable.Asset
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class Cover(val id: String, val asset: Asset)

internal class ArtworkLoader(private val context: Context) {
    fun read(metadata: MediaMetadata?, notification: Notification?): Cover? {
        val bitmap = bitmapKeys.firstNotNullOfOrNull { metadata?.getBitmap(it) }
            ?: uriKeys.firstNotNullOfOrNull { metadata?.getString(it)?.let(::readUri) }
            ?: (notification?.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap
            ?: return null

        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val image = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true,
        )
        val buffer = ByteArrayOutputStream()
        if (!image.compress(Bitmap.CompressFormat.JPEG, 88, buffer)) return null
        val bytes = buffer.toByteArray()
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Cover(id, Asset.createFromBytes(bytes))
    }

    private fun readUri(raw: String): Bitmap? {
        val uri = Uri.parse(raw)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT &&
            uri.scheme != ContentResolver.SCHEME_ANDROID_RESOURCE
        ) return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_SIZE * 2) {
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private companion object {
        const val MAX_SIZE = 512
        val bitmapKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        val uriKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
    }
}
