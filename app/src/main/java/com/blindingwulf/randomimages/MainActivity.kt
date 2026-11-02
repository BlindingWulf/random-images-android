package com.blindingwulf.randomimages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var engine: SlideshowEngine

    private lateinit var image: ZoomableImageView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private lateinit var queryInput: TextInputEditText
    private lateinit var startButton: MaterialButton
    private lateinit var popoutButton: MaterialButton
    private lateinit var intervalSlider: Slider
    private lateinit var intervalLabel: TextView
    private lateinit var ratingGroup: ChipGroup
    private lateinit var sourceGroup: ChipGroup
    private lateinit var toolbar: MaterialToolbar
    private lateinit var panel: View

    /** True while the toolbar and control panel are hidden for a full-screen view. */
    private var fullscreen = false

    /** Stops the chip listeners writing back while the UI is being populated. */
    private var binding = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startSlideshow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = RivApplication.from(this)
        settings = app.settings
        engine = app.engine
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        image = findViewById(R.id.image)
        caption = findViewById(R.id.caption)
        status = findViewById(R.id.status)
        queryInput = findViewById(R.id.queryInput)
        startButton = findViewById(R.id.startButton)
        popoutButton = findViewById(R.id.popoutButton)
        intervalSlider = findViewById(R.id.intervalSlider)
        intervalLabel = findViewById(R.id.intervalLabel)
        ratingGroup = findViewById(R.id.ratingGroup)
        sourceGroup = findViewById(R.id.sourceGroup)
        toolbar = findViewById(R.id.toolbar)
        panel = findViewById(R.id.panel)

        setSupportActionBar(toolbar)
        bindSettingsToUi()
        wireControls()
        observeEngine()
    }

    // ------------------------------------------------------------------ setup

    private fun bindSettingsToUi() {
        binding = true
        queryInput.setText(settings.query)
        intervalSlider.value = settings.intervalSec.toFloat()
        intervalLabel.text = getString(R.string.interval_label, settings.intervalSec)
        ratingGroup.check(
            when (settings.rating) {
                Rating.SFW -> R.id.chipSfw
                Rating.QUESTIONABLE -> R.id.chipMature
                Rating.NSFW -> R.id.chipExplicit
            }
        )
        sourceGroup.check(
            when (settings.source) {
                SourceMode.RANDOM -> R.id.chipBoth
                SourceMode.E621 -> R.id.chipE621
                SourceMode.FURAFFINITY -> R.id.chipFa
            }
        )
        binding = false
    }

    private fun wireControls() {
        queryInput.setOnEditorActionListener { _, actionId, event ->
            val search = actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (search) {
                commitQuery()
                startSlideshow()
                true
            } else {
                false
            }
        }
        startButton.setOnClickListener {
            if (engine.state.value.running) {
                SlideshowService.start(this, SlideshowService.ACTION_PAUSE)
            } else {
                commitQuery()
                startSlideshow()
            }
        }
        findViewById<MaterialButton>(R.id.nextButton).setOnClickListener { engine.next() }
        findViewById<MaterialButton>(R.id.previousButton).setOnClickListener { engine.previous() }

        popoutButton.setOnClickListener { togglePopout() }

        intervalSlider.addOnChangeListener { _, value, _ ->
            val seconds = value.toInt()
            settings.intervalSec = seconds
            intervalLabel.text = getString(R.string.interval_label, seconds)
        }

        ratingGroup.setOnCheckedStateChangeListener { _, ids ->
            if (binding || ids.isEmpty()) return@setOnCheckedStateChangeListener
            val rating = when (ids.first()) {
                R.id.chipMature -> Rating.QUESTIONABLE
                R.id.chipExplicit -> Rating.NSFW
                else -> Rating.SFW
            }
            if (rating == Rating.NSFW && !settings.nsfwAcknowledged) {
                confirmNsfw(rating)
            } else {
                settings.rating = rating
                engine.pendingRetarget = true
            }
        }
        sourceGroup.setOnCheckedStateChangeListener { _, ids ->
            if (binding || ids.isEmpty()) return@setOnCheckedStateChangeListener
            settings.source = when (ids.first()) {
                R.id.chipE621 -> SourceMode.E621
                R.id.chipFa -> SourceMode.FURAFFINITY
                else -> SourceMode.RANDOM
            }
            engine.pendingRetarget = true
        }

        image.onSingleTap = ::toggleSystemUi
        image.onSwipeLeft = { engine.next() }
        image.onSwipeRight = { engine.previous() }
    }

    private fun confirmNsfw(pending: Rating) {
        val previous = settings.rating
        AlertDialog.Builder(this)
            .setMessage(R.string.nsfw_notice)
            .setPositiveButton(R.string.i_understand) { _, _ ->
                settings.nsfwAcknowledged = true
                settings.rating = pending
                engine.pendingRetarget = true
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                // Put the selection back where it was.
                ratingGroup.check(
                    when (previous) {
                        Rating.SFW -> R.id.chipSfw
                        Rating.QUESTIONABLE -> R.id.chipMature
                        Rating.NSFW -> R.id.chipExplicit
                    }
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun commitQuery() {
        val text = queryInput.text?.toString()?.trim().orEmpty()
        settings.query = text
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(queryInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- service

    /**
     * Android 13 moved notifications to a runtime permission and a foreground
     * service without one is killed, so ask before the first start.
     */
    private fun startSlideshow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SlideshowService.start(this, SlideshowService.ACTION_START)
    }

    // ---------------------------------------------------------- pop-out window

    /**
     * The floating window is always a deliberate act — nothing else in the app
     * opens it, so the label drives the request rather than a preference the
     * slideshow could honour or ignore.
     */
    private fun togglePopout() {
        val showing = SlideshowService.instance?.overlayShowing == true
        if (!showing && !OverlayPermission.isGranted(this)) {
            toast(getString(R.string.popout_needs_permission))
            startActivity(OverlayPermission.settingsIntent(this))
            return
        }
        if (showing) {
            SlideshowService.start(this, SlideshowService.ACTION_HIDE_OVERLAY)
        } else {
            // A window with nothing in it is not a feature: fetch the first image
            // when the engine has nothing to show yet.
            if (engine.state.value.current == null) {
                commitQuery()
                startSlideshow()
            }
            SlideshowService.start(this, SlideshowService.ACTION_SHOW_OVERLAY)
        }
        // The service acts on the next main-thread pass, so hold the new label
        // until it agrees; syncPopoutLabel() would otherwise read the old state.
        popoutRequested = !showing
        renderPopoutLabel(!showing)
    }

    /**
     * True while a request is in flight: the label shows what was asked for and
     * refuses to snap back to a service that has not caught up. onResume clears
     * it, which is also the safety net if the request could never be honoured.
     */
    private var popoutRequested: Boolean? = null

    private fun syncPopoutLabel() {
        val actual = SlideshowService.instance?.overlayShowing == true
        val pending = popoutRequested
        if (pending != null && pending != actual) return
        popoutRequested = null
        renderPopoutLabel(actual)
    }

    private fun renderPopoutLabel(showing: Boolean) {
        popoutButton.setText(if (showing) R.string.popout_hide else R.string.popout_show)
    }

    // ------------------------------------------------------------------- view

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { state ->
                    startButton.setText(if (state.running) R.string.pause else R.string.start)
                    // Cheap and keeps the pop-out label honest when the window's
                    // own close button ended it while the app was behind it.
                    syncPopoutLabel()
                    status.text = state.status
                    if (state.loading && state.current == null) caption.visibility =
                        TextView.VISIBLE
                    val shown = state.current ?: return@collect
                    if (state.loading) return@collect
                    loadImage(shown)
                    caption.text = listOf(
                        shown.result.source,
                        shown.result.rating,
                        shown.result.credit,
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    caption.visibility = TextView.VISIBLE
                    status.text = buildString {
                        append(state.status)
                        append("  ·  ")
                        append(
                            getString(
                                R.string.images_shown,
                                state.shownCount,
                                state.cacheCount,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun loadImage(shown: ShownImage) {
        if (!shown.file.isFile) return
        lifecycleScope.launch(Dispatchers.IO) {
            val target = maxOf(resources.displayMetrics.widthPixels, 720)
            val animated = if (shown.format == "gif" || shown.format == "webp") {
                ImageLoader.loadAnimated(this@MainActivity, shown.file)
            } else {
                null
            }
            val bitmap = if (animated == null) ImageLoader.load(shown.file, target) else null
            launch(Dispatchers.Main) {
                if (animated != null) {
                    image.setImageDrawable(animated)
                } else {
                    bitmap?.let { image.setImageBitmap(it) }
                }
            }
        }
    }

    /**
     * Tap-to-toggle full screen: the toolbar, status line and control panel go
     * away and the image takes the whole display. A swipe from either edge
     * briefly reveals the system bars (they come back with the next tap).
     */
    private fun toggleSystemUi() {
        fullscreen = !fullscreen
        val visibility = if (fullscreen) View.GONE else View.VISIBLE
        toolbar.visibility = visibility
        panel.visibility = visibility
        status.visibility = visibility
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // The setting used to be ignored: the flag was added unconditionally, so
        // "keep the screen on" was permanently on rather than opt-in.
        if (settings.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine.refreshCacheStats()
        popoutRequested = null
        syncPopoutLabel()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        // Rotation recreates the activity; never leave the next instance
        // starting with the system bars hidden.
        if (fullscreen) {
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_credentials -> startActivity(Intent(this, CredentialsActivity::class.java))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_save -> saveToGallery()
            R.id.action_share -> shareImage()
            R.id.action_open_source -> openSource()
            R.id.action_clear_cache -> confirmClearCache()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun saveToGallery() {
        val shown = engine.state.value.current
        if (shown == null || !shown.file.isFile) {
            toast(getString(R.string.no_image))
            return
        }
        if (GallerySaver.needsWritePermission &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        persist(shown)
    }

    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val shown = engine.state.value.current
            if (granted && shown != null) persist(shown) else toast(getString(R.string.save_failed, "permission denied"))
        }

    private fun persist(shown: ShownImage) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GallerySaver.saveToGallery(
                    this@MainActivity,
                    shown.file.readBytes(),
                    shown.format,
                    shown.result.credit,
                )
            }
            toast(
                result.getOrNull()?.let { getString(R.string.saved_to_gallery) }
                    ?: getString(R.string.save_failed, result.exceptionOrNull()?.message ?: "?")
            )
        }
    }

    private fun shareImage() {
        val shown = engine.state.value.current
        if (shown == null || !shown.file.isFile) {
            toast(getString(R.string.no_image))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shown.file)
        startActivity(
            ShareCompat.IntentBuilder(this)
                .setType("image/*")
                .setStream(uri)
                .setChooserTitle(R.string.share_via)
                .intent
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .let { android.content.Intent.createChooser(it, getString(R.string.share_via)) }
        )
    }

    private fun openSource() {
        val url = engine.state.value.current?.result?.url?.takeIf { it.isNotBlank() } ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cache_clear_title)
            .setMessage(R.string.cache_clear_body)
            .setPositiveButton(R.string.ok) { _, _ ->
                SlideshowService.start(this, SlideshowService.ACTION_STOP)
                val removed = engine.state.value.cacheCount
                engine.cache().clear()
                engine.refreshSeenTokens()
                engine.refreshCacheStats()
                toast(getString(R.string.cache_cleared, removed))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
