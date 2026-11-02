package com.blindingwulf.randomimages

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The floating image window — the mobile counterpart of the desktop's
 * always-on-top Tk image window, which a phone cannot have without this.
 *
 * Requires SYSTEM_ALERT_WINDOW (the "display over other apps" permission), so
 * it is opt-in in Settings and the service only attaches it once granted.
 *
 * Gestures follow the phone picture-in-picture window the user expects: drag it
 * anywhere, pinch or pull a corner to resize, tap it to return to the app.
 */
class OverlayController(
    private val context: Context,
    private val engine: SlideshowEngine,
    private val settings: Settings,
    /** Lets the service drop this controller when the window's own close button ends it. */
    private val onClosed: () -> Unit = {},
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: OverlayGestureLayout? = null
    private var body: View? = null
    private var image: ImageView? = null
    private var caption: TextView? = null
    private var status: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0

    /** Main-thread scope for the deferred decode; its jobs are cancelled on hide. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frame = 0
    private var decodedFile: File? = null
    private var decodedTarget = 0

    private val density: Float
        get() = context.resources.displayMetrics.density

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_image, null)
        val layout = view as OverlayGestureLayout
        root = layout
        body = view.findViewById(R.id.overlayBody)
        image = view.findViewById(R.id.overlayImage)
        caption = view.findViewById(R.id.overlayCaption)
        status = view.findViewById(R.id.overlayStatus)

        view.findViewById<ImageButton>(R.id.overlayPrevious).setOnClickListener { engine.previous() }
        view.findViewById<ImageButton>(R.id.overlayPause).setOnClickListener {
            if (engine.state.value.running) engine.pause() else engine.start()
        }
        view.findViewById<ImageButton>(R.id.overlayNext).setOnClickListener { engine.next() }
        view.findViewById<ImageButton>(R.id.overlayClose).setOnClickListener {
            // The window may be ended from itself, and the service has to hear
            // about it: otherwise the app's pop-out control would keep saying
            // the window is still up.
            hide()
            onClosed()
        }

        layout.onMoveBy = { dx, dy -> moveBy(dx, dy) }
        layout.onScaleBy = { factor -> scaleWidthBy(factor) }
        layout.onResizeBy = { delta -> resizeWidthBy(delta) }
        layout.onGestureEnd = { persistWidth() }
        layout.onTap = { openMainApp() }

        val windowParams = layoutParams()
        windowManager.addView(view, windowParams)
        params = windowParams
        screenWidth = screenWidthPx()
        setBodyWidth(windowWidthPx())
        render(engine.state.value)
    }

    fun hide() {
        // Stops an in-flight decode from touching the views being torn down.
        frame++
        decodedFile = null
        decodedTarget = 0
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        body = null
        params = null
        image = null
        caption = null
        status = null
    }

    /** Called for every engine state change while the overlay is attached. */
    fun render(state: EngineState) {
        refitIfDisplayChanged()
        val shown = state.current
        // The bar is narrow, so while an image is up it shows just the source and
        // leaves the credit to the caption. "Loading" only matters when there is
        // nothing to look at yet, otherwise it sits over a perfectly good image.
        status?.text = when {
            shown != null -> shown.result.source
            state.loading -> context.getString(R.string.loading)
            else -> state.status
        }
        root?.findViewById<ImageButton>(R.id.overlayPause)
            ?.setImageResource(if (state.running) R.drawable.ic_pause else R.drawable.ic_play)
        if (shown != null && shown.file.isFile) showImage(shown)
    }

    /**
     * Decodes off the main thread. render() is called from the service's
     * Dispatchers.Main.immediate collector, and a full-size decode on a 1 GB
     * Android 5 handset is easily a few hundred milliseconds — long enough to be
     * an interactive hitch, or an ANR, in that context.
     *
     * [frame] discards a decode that was overtaken: a resize re-renders
     * immediately, and the older, smaller bitmap must not land afterwards.
     */
    private fun showImage(shown: ShownImage) {
        val target = (tileWidthPx() * 2).coerceIn(320, 1440)
        if (shown.file == decodedFile && target == decodedTarget) return
        val attempt = ++frame
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(shown.file, target) }
            if (attempt != frame || bitmap == null) return@launch
            decodedFile = shown.file
            decodedTarget = target
            image?.setImageBitmap(bitmap)
            caption?.text = listOf(shown.result.source, shown.result.credit)
                .filter { it.isNotBlank() }.joinToString(" · ")
            caption?.visibility = View.VISIBLE
        }
    }

    /** Applies a new size preference (the Settings slider) to a live window. */
    fun resize() {
        setBodyWidth(windowWidthPx())
        clampIntoScreen()
        render(engine.state.value)
    }

    /**
     * The overlay is not an activity, so no configuration callback tells it the
     * display changed size. Re-check whenever a frame is drawn: after a rotation
     * the tile would otherwise be wider than the screen and partly off it.
     */
    private fun refitIfDisplayChanged() {
        if (screenWidthPx() == screenWidth) return
        screenWidth = screenWidthPx()
        setBodyWidth(windowWidthPx())
        clampIntoScreen()
    }

    private fun moveBy(dx: Int, dy: Int) {
        val windowParams = params ?: return
        windowParams.x += dx
        windowParams.y += dy
        clampIntoScreen()
        updateWindow()
    }

    /** Pinch: scale the tile about its own centre, as the phone PiP window does. */
    private fun scaleWidthBy(factor: Float) {
        val view = root ?: return
        val before = tileWidthPx()
        val after = (before * factor).toInt().coerceIn(minWidthPx(), maxWidthPx())
        if (before <= 0 || after == before) return
        // The bar row is a fixed height, so the new height is only an estimate;
        // it keeps the centre roughly under the fingers, which is all this needs.
        val heightDelta = view.height.toFloat() * (after - before) / before
        setBodyWidth(after)
        params?.let {
            it.x -= ((after - before) / 2)
            it.y -= (heightDelta / 2).toInt()
        }
        clampIntoScreen()
        updateWindow()
    }

    /** Corner pull: the width follows the finger, keeping its left edge put. */
    private fun resizeWidthBy(deltaPx: Int) {
        val before = tileWidthPx()
        val after = (before + deltaPx).coerceIn(minWidthPx(), maxWidthPx())
        if (after == before) return
        setBodyWidth(after)
        clampIntoScreen()
        updateWindow()
    }

    private fun persistWidth() {
        val width = tileWidthPx()
        if (width <= 0) return
        settings.overlaySizePercent = percentForWidth(width)
        render(engine.state.value)
        // A resize clamps against the width the view had before the gesture, so
        // the last pull can leave the tile a few pixels past the edge. By the
        // time this runs the new size is measured and it can be centred properly.
        root?.post {
            clampIntoScreen()
            updateWindow()
        }
    }

    /**
     * The tile carries its own width in pixels and the window is WRAP_CONTENT, so
     * a resize is an ordinary re-layout. WindowManager.LayoutParams objects get
     * mutated in place by the window system, and pushing a changed width back
     * through the same instance proved unreliable.
     */
    private fun setBodyWidth(width: Int) {
        val tile = body ?: return
        if (width <= 0) return
        val lp = tile.layoutParams
        if (lp.width != width) {
            lp.width = width
            tile.layoutParams = lp
        }
    }

    private fun tileWidthPx(): Int = body?.layoutParams?.width ?: 0

    private fun updateWindow() {
        val view = root ?: return
        val windowParams = params ?: return
        runCatching { windowManager.updateViewLayout(view, windowParams) }
    }

    /**
     * The window uses LAYOUT_NO_LIMITS so it can be dragged freely, which also
     * means it could be parked somewhere untappable: every move pulls it back
     * inside the display.
     */
    private fun clampIntoScreen() {
        val view = root ?: return
        val windowParams = params ?: return
        val metrics = realMetrics()
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
        windowParams.x = windowParams.x.coerceIn(0, maxX)
        windowParams.y = windowParams.y.coerceIn(0, maxY)
    }

    private fun openMainApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                },
            )
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Focusable stays off so the overlay never steals input from the app
            // underneath; buttons and gestures still work because touches reach
            // our view.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (realMetrics().heightPixels * 0.14f).toInt()
        }
    }

    /**
     * The percentage is of the shorter display edge, not the current one: a tile
     * sized in portrait would otherwise leap to double the width on rotation,
     * the way a video PiP does not change size when the phone turns.
     */
    private fun referenceWidthPx(): Int {
        val metrics = realMetrics()
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    private fun minWidthPx(): Int = (referenceWidthPx() * MIN_PERCENT / 100f).toInt()

    private fun maxWidthPx(): Int = (referenceWidthPx() * MAX_PERCENT / 100f).toInt()

    private fun windowWidthPx(): Int =
        (referenceWidthPx() * settings.overlaySizePercent / 100f).toInt()
            .coerceIn(minWidthPx(), maxWidthPx())

    private fun percentForWidth(width: Int): Int {
        val percent = (width * 100f / referenceWidthPx()).toInt()
        return (percent / 5 * 5).coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun screenWidthPx(): Int = realMetrics().widthPixels

    private companion object {
        const val MIN_PERCENT = 20
        const val MAX_PERCENT = 90
    }
}
