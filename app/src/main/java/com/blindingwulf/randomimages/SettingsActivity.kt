package com.blindingwulf.randomimages

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var overlaySwitch: MaterialSwitch

    /** Stops the switch being re-synced from the store as if the user moved it. */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = RivApplication.from(this)
        settings = app.settings
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        overlaySwitch = findViewById(R.id.overlaySwitch)
        overlaySwitch.isChecked = SlideshowService.instance?.overlayShowing == true
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked && !OverlayPermission.isGranted(this)) {
                // Do not pretend it is on: put the switch back and take the user
                // to the page where the permission can actually be given.
                binding = true
                overlaySwitch.isChecked = false
                binding = false
                startActivity(OverlayPermission.settingsIntent(this))
                return@setOnCheckedChangeListener
            }
            // The switch is an instruction, not a preference the service may
            // honour later: it shows or hides the window now.
            SlideshowService.start(
                this,
                if (checked) SlideshowService.ACTION_SHOW_OVERLAY
                else SlideshowService.ACTION_HIDE_OVERLAY,
            )
        }
        findViewById<MaterialButton>(R.id.overlayPermissionButton).setOnClickListener {
            startActivity(OverlayPermission.settingsIntent(this))
        }

        val sizeSlider = findViewById<Slider>(R.id.overlaySize)
        sizeSlider.value = settings.overlaySizePercent.toFloat()
        sizeSlider.addOnChangeListener { _, value, _ ->
            settings.overlaySizePercent = value.toInt()
            SlideshowService.instance?.refreshOverlay()
            findViewById<TextView>(R.id.overlaySizeLabel).text =
                getString(R.string.overlay_size_label, value.toInt())
        }
        findViewById<TextView>(R.id.overlaySizeLabel).text =
            getString(R.string.overlay_size_label, settings.overlaySizePercent)

        val repeatSwitch = findViewById<MaterialSwitch>(R.id.noRepeat)
        repeatSwitch.isChecked = settings.avoidRepeats
        repeatSwitch.setOnCheckedChangeListener { _, checked -> settings.avoidRepeats = checked }

        val nightSwitch = findViewById<MaterialSwitch>(R.id.nightMode)
        nightSwitch.isChecked = settings.nightMode
        nightSwitch.setOnCheckedChangeListener { _, checked ->
            settings.nightMode = checked
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (checked) {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }

        val awakeSwitch = findViewById<MaterialSwitch>(R.id.keepAwake)
        awakeSwitch.isChecked = settings.keepAwake
        awakeSwitch.setOnCheckedChangeListener { _, checked -> settings.keepAwake = checked }

        val wifiSwitch = findViewById<MaterialSwitch>(R.id.wifiOnly)
        wifiSwitch.isChecked = settings.wifiOnly
        wifiSwitch.setOnCheckedChangeListener { _, checked -> settings.wifiOnly = checked }

        findViewById<MaterialButton>(R.id.overlayTestButton).setOnClickListener {
            if (!OverlayPermission.isGranted(this)) {
                startActivity(OverlayPermission.settingsIntent(this))
                return@setOnClickListener
            }
            // Ask the service: it either puts the floating tile up or it does not.
            // The switch follows through the same listener a real tap would use.
            overlaySwitch.isChecked = true
            SlideshowService.start(this, SlideshowService.ACTION_SHOW_OVERLAY)
        }
        findViewById<TextView>(R.id.versionLabel).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        // Both can change while this screen is paused: the permission in system
        // settings, the window from its own close button.
        val granted = OverlayPermission.isGranted(this)
        findViewById<TextView>(R.id.overlayPermissionRow).visibility =
            if (granted) TextView.GONE else TextView.VISIBLE
        findViewById<MaterialButton>(R.id.overlayPermissionButton).visibility =
            if (granted) TextView.GONE else TextView.VISIBLE
        binding = true
        overlaySwitch.isChecked = SlideshowService.instance?.overlayShowing == true
        binding = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
