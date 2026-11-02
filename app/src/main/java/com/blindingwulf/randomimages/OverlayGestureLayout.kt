package com.blindingwulf.randomimages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.hypot

/**
 * Gesture layer for the floating tile, modelled on the iOS picture-in-picture
 * window: hold and drag moves it, two fingers pinch to scale, pulling the
 * bottom-right corner resizes it, and a tap hands control back to the app.
 *
 * Detection happens in [onTouchEvent], not [onInterceptTouchEvent]. The tile's
 * own children are only its four buttons; any other press is consumed here, and
 * a ViewGroup that consumed the DOWN is never asked to intercept again, so an
 * intercept-based gesture state machine would never leave its idle state.
 */
class OverlayGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Incremental movement in screen pixels, since the previous event. */
    var onMoveBy: ((Int, Int) -> Unit)? = null

    /** Multiplicative width change (pinch), relative to the previous event. */
    var onScaleBy: ((Float) -> Unit)? = null

    /** Additive width change (corner pull), in screen pixels. */
    var onResizeBy: ((Int) -> Unit)? = null

    /** The gesture is over: persist whatever it changed. */
    var onGestureEnd: (() -> Unit)? = null

    /** A press that never moved: treat it as "open the app". */
    var onTap: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val cornerSizePx = CORNER_DP * resources.displayMetrics.density

    /** Three hatch marks saying "pull here", over the resize corner. */
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRIP_COLOR
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private enum class Gesture { NONE, MOVE, PINCH, CORNER }

    private var gesture = Gesture.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var rawDownX = 0f
    private var rawDownY = 0f
    private var downTime = 0L
    private var pinchSpan = 0f
    private val screenLocation = IntArray(2)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val gap = 6f * resources.displayMetrics.density
        val length = 13f * resources.displayMetrics.density
        val step = 9f * resources.displayMetrics.density
        val right = width.toFloat() - gap
        val bottom = height.toFloat() - gap
        for (i in 0..2) {
            val inset = i * step
            canvas.drawLine(
                right - inset, bottom - inset - length,
                right - inset - length, bottom - inset, gripPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.NONE
                downX = event.x
                downY = event.y
                rawDownX = event.rawX
                rawDownY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                downTime = System.currentTimeMillis()
                // Claim the sequence so the tile sees the rest of the gesture even
                // when the finger leaves the view while it follows along.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > 1) {
                    gesture = Gesture.PINCH
                    pinchSpan = spanOf(event)
                }
            }

            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.PINCH -> {
                    val span = spanOf(event)
                    if (pinchSpan > 0f && span > 0f) onScaleBy?.invoke(span / pinchSpan)
                    pinchSpan = span
                }

                Gesture.CORNER -> {
                    // A corner pull is usually diagonal; whichever axis travelled
                    // further this frame drives the width.
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    onResizeBy?.invoke(maxOf(dx, dy).toInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }

                Gesture.MOVE -> {
                    onMoveBy?.invoke(
                        (event.rawX - lastX).toInt(),
                        (event.rawY - lastY).toInt(),
                    )
                    lastX = event.rawX
                    lastY = event.rawY
                }

                Gesture.NONE -> if (travelledBeyondSlop(event)) {
                    gesture =
                        if (nearBottomRightCorner(downX, downY)) Gesture.CORNER else Gesture.MOVE
                    // Baseline here, not at the press: otherwise the first real
                    // move replays the whole slop distance and the tile jumps.
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // One finger left after a pinch: keep dragging from wherever it is
                // instead of teleporting the tile by the whole pinch offset.
                gesture = Gesture.MOVE
                rebaseToRemainingPointer(event)
                onGestureEnd?.invoke()
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.NONE && isTap(event)) performClick()
                if (gesture != Gesture.NONE) onGestureEnd?.invoke()
                gesture = Gesture.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gesture != Gesture.NONE) onGestureEnd?.invoke()
                gesture = Gesture.NONE
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    private fun travelledBeyondSlop(event: MotionEvent): Boolean =
        hypot(
            (event.rawX - rawDownX).toDouble(),
            (event.rawY - rawDownY).toDouble(),
        ).toFloat() > slop

    private fun isTap(event: MotionEvent): Boolean =
        System.currentTimeMillis() - downTime < TAP_MS &&
            hypot(
                ((event.x - downX)).toDouble(),
                ((event.y - downY)).toDouble(),
            ).toFloat() <= slop

    /** A corner pull starts inside the tile's bottom-right square. */
    private fun nearBottomRightCorner(x: Float, y: Float): Boolean {
        val zone = cornerZone()
        return x > width - zone && y > height - zone
    }

    /**
     * The corner square shrinks with the tile. Fixed at 96 dp it would cover a
     * window at its smallest size from edge to edge, so every drag would resize
     * and the tile could never be moved again.
     */
    private fun cornerZone(): Float =
        cornerSizePx.coerceAtMost(minOf(width, height) * MAX_CORNER_FRACTION)

    /**
     * The surviving finger must keep dragging from where it is, so its baseline is
     * rebuilt in raw screen coordinates: getX/getY are view-relative and
     * getRawX(index) is API 29, while this view is a window root whose screen
     * position is known on every supported release.
     */
    private fun rebaseToRemainingPointer(event: MotionEvent) {
        val remaining = if (event.actionIndex == 0) 1 else 0
        if (remaining >= event.pointerCount) return
        getLocationOnScreen(screenLocation)
        lastX = screenLocation[0] + event.getX(remaining)
        lastY = screenLocation[1] + event.getY(remaining)
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(
            (event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble(),
        ).toFloat()
    }

    private companion object {
        const val GRIP_COLOR = 0x99FFFFFF.toInt()

        /**
         * How far inside the bottom-right corner a press may start and still
         * resize. Generous on purpose: the tile is small, and a finger a few
         * millimetres off a tight target should not turn a resize into a move.
         */
        const val CORNER_DP = 96f

        /** Never claim more than this share of a small tile as resize-only. */
        const val MAX_CORNER_FRACTION = 0.45f
        const val TAP_MS = 300L
    }
}
