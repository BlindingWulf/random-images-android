package com.blindingwulf.randomimages

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A [ScrollView] that shrinks its content to fit the available height instead
 * of scrolling it. The control panel at large display sizes used to overflow:
 * the search row scrolled off the top and the bottom row sat half clipped,
 * which reads as a broken layout rather than a scrollable one — a screen
 * already showing half its controls does not advertise that more exist. The
 * content is therefore measured at its natural size, and when that exceeds
 * the viewport the whole panel is scaled down (via the child's scale
 * properties, which move the touch targets with the drawing) so every control
 * stays visible and tappable at any display size or font scale.
 *
 * Above [MIN_FIT_SCALE] the panel never scrolls; below it the content is
 * pinned at the minimum legible size and the old scrolling takes over as the
 * safety net.
 */
class FitScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private var fitScale = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        fitScale = 1f
        if (childCount == 0) return
        val child = getChildAt(0)
        val viewport = measuredHeight - paddingTop - paddingBottom
        val innerWidth = measuredWidth - paddingLeft - paddingRight
        if (viewport <= 0 || innerWidth <= 0 || child.measuredHeight <= viewport) {
            child.scaleX = 1f
            child.scaleY = 1f
            return
        }
        // Scale so the content's natural height fits, then reflow it once at
        // the widened effective width: chip rows that wrapped for the squeeze
        // can return to one line, so the final scale needs re-reading.
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var scale = viewport.toFloat() / child.measuredHeight
        child.measure(
            MeasureSpec.makeMeasureSpec((innerWidth / scale).toInt(), MeasureSpec.EXACTLY),
            heightSpec,
        )
        scale = minOf(scale, viewport.toFloat() / child.measuredHeight)
        fitScale = maxOf(scale, MIN_FIT_SCALE)
        child.measure(
            MeasureSpec.makeMeasureSpec((innerWidth / fitScale).toInt(), MeasureSpec.EXACTLY),
            heightSpec,
        )
        // Top-left pivot so the scaled content still starts at the panel's
        // own origin; the scale properties also transform touch coordinates.
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = fitScale
        child.scaleY = fitScale
    }

    /**
     * ScrollView computes its scroll range (and drag clamping) from the
     * child's unscaled height; without this a fitted panel would still offer
     * a phantom scroll that drags the content off-screen into blank space.
     */
    override fun computeVerticalScrollRange(): Int {
        if (childCount == 0) return 0
        val child = getChildAt(0)
        return (child.height * fitScale).toInt() + paddingTop + paddingBottom
    }

    private companion object {
        /** Below this the controls stop being legible; scroll instead. */
        private const val MIN_FIT_SCALE = 0.5f
    }
}
