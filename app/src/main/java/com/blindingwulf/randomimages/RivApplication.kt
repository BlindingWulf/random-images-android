package com.blindingwulf.randomimages

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import kotlin.concurrent.thread

/**
 * Process entry point: applies the stored theme before any activity inflates,
 * warms the trust bundle off the main thread, and owns the single engine that
 * both the activity and the overlay service observe.
 */
class RivApplication : Application() {

    lateinit var engine: SlideshowEngine
        private set

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        engine = SlideshowEngine(this)
        AppCompatDelegate.setDefaultNightMode(
            if (settings.nightMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        // ~120 certificate parses; keeps them out of the first frame.
        thread(name = "trust-init", isDaemon = true) { TrustBundle.install(this) }
    }

    companion object {
        fun from(context: android.content.Context): RivApplication =
            context.applicationContext as RivApplication
    }
}
