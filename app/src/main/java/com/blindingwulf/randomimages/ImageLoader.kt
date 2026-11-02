package com.blindingwulf.randomimages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/**
 * Bitmap loading for phones that may have 1 GB of RAM: the desktop app can hold
 * a full-resolution image, a 2015 handset cannot, so everything is decoded at
 * the size it will actually be drawn at.
 */
object ImageLoader {

    /**
     * Decodes [file] downsampled so neither dimension exceeds [maxPx].
     *
     * inSampleSize can only halve dimensions, which alone would leave an image
     * up to twice too small (and visibly soft once stretched). The remaining
     * fraction is applied through the decoder's own scaler, so the result lands
     * on [maxPx] instead of somewhere below it.
     */
    fun load(file: File, maxPx: Int): Bitmap? {
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
            val decodedLongest = maxOf(bounds.outWidth, bounds.outHeight) / sample
            val scale = (maxPx.toFloat() / decodedLongest).coerceAtMost(1f)
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    // A scale of 1 would only resample (and blur) needlessly.
                    inScaled = scale < 1f
                    inDensity = BASE_DENSITY
                    inTargetDensity = (BASE_DENSITY * scale).toInt().coerceAtLeast(1)
                },
            )
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Animated WebP/GIF stay animated: the still decoder would freeze them on
     * frame one, so those go through the drawable path when the platform
     * supports it (API 28+ for AnimatedImageDrawable).
     */
    fun loadAnimated(context: Context, file: File): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(file)
            val drawable = ImageDecoder.decodeDrawable(source)
            (drawable as? AnimatedImageDrawable)?.start()
            drawable
        } catch (e: Exception) {
            null
        }
    }

    /** Powers-of-two downsample, as BitmapFactory requires. */
    fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxPx) sample *= 2
        return sample
    }

    /** inDensity/inTargetDensity are only a ratio; any fixed base works. */
    private const val BASE_DENSITY = 1000
}
