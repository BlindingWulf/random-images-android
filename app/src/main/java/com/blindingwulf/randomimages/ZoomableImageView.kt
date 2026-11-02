package com.blindingwulf.randomimages

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Photo viewer for the slideshow: pinch to zoom, double-tap to toggle fit/full,
 * drag to pan while zoomed, and a horizontal fling to skip images.
 *
 * The desktop app had a window that resized to the image aspect ratio; on a
 * phone that maps to "fit inside, then let the user zoom", so the image is never
 * cropped or stretched by default.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val fittedMatrix = Matrix()
    private var minScale = 1f
    private var maxScale = 6f
    private var baseScale = 1f

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleBy(detector.scaleFactor)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke() ?: toggleZoom()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleOf(imageMatrix) <= baseScale + 0.01f) return false
                post { translateBy(-distanceX, -distanceY) }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (kotlin.math.abs(velocityX) < FLING_VELOCITY ||
                    kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)
                ) return false
                if (velocityX < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fitToView()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    /** Fits the whole image inside the view (the "never crop" default). */
    fun fitToView() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val scale = min(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        baseScale = scale
        minScale = scale
        maxScale = scale * MAX_ZOOM_FACTOR
        // Build the fitted matrix locally: going through translateBy() would
        // write to the view's current matrix and then be discarded below.
        fittedMatrix.reset()
        fittedMatrix.setScale(scale, scale)
        fittedMatrix.postTranslate(
            (width - d.intrinsicWidth * scale) / 2f,
            (height - d.intrinsicHeight * scale) / 2f,
        )
        imageMatrix = Matrix(fittedMatrix)
    }

    fun toggleZoom() {
        if (scaleOf(imageMatrix) > baseScale + 0.01f) {
            imageMatrix = Matrix(fittedMatrix)
        } else {
            scaleBy(2.2f, width / 2f, height / 2f)
        }
    }

    private fun scaleBy(factor: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val next = (scaleOf(imageMatrix) * factor).coerceIn(minScale, maxScale)
        imageMatrix.postScale(next / scaleOf(imageMatrix), next / scaleOf(imageMatrix), focusX, focusY)
        clampTranslation()
    }

    private fun translateBy(dx: Float, dy: Float) {
        imageMatrix.postTranslate(dx, dy)
        clampTranslation()
    }

    private fun scaleOf(matrix: Matrix): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the image from being dragged fully off screen. */
    private fun clampTranslation() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        when {
            rect.width() < width -> dx = (width - rect.width()) / 2f - rect.left
            rect.left > 0 -> dx = -rect.left
            rect.right < width -> dx = width - rect.right
        }
        when {
            rect.height() < height -> dy = (height - rect.height()) / 2f - rect.top
            rect.top > 0 -> dy = -rect.top
            rect.bottom < height -> dy = height - rect.bottom
        }
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            clampTranslation()
        }
        return handled || super.onTouchEvent(event)
    }

    /** True when the current zoom allows an image-skip fling. */
    fun canFling(): Boolean = scaleOf(imageMatrix) <= baseScale + 0.01f

    companion object {
        private const val MAX_ZOOM_FACTOR = 6f
        private const val FLING_VELOCITY = 900f
    }
}
