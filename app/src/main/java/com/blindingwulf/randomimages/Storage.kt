package com.blindingwulf.randomimages

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret app settings. The desktop app keeps these in tkinter variables;
 * here they survive restarts through SharedPreferences. The secret values live
 * in SecureStore instead, mirroring the desktop's Credential Manager use.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("riv_settings", Context.MODE_PRIVATE)

    var query: String
        get() = prefs.getString(KEY_QUERY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_QUERY, value).apply()

    var rating: Rating
        get() = Rating.fromLevel(prefs.getString(KEY_RATING, Rating.SFW.level))
        set(value) = prefs.edit().putString(KEY_RATING, value.level).apply()

    var source: SourceMode
        get() = runCatching {
            SourceMode.valueOf(prefs.getString(KEY_SOURCE, SourceMode.RANDOM.name)!!)
        }.getOrDefault(SourceMode.RANDOM)
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    /** Seconds between images; the desktop slider range is 1..300. */
    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)).apply()

    /** Dark theme on/off. Dark by default: this is an image viewer. */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT, value).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /**
     * Overlay window size as a percentage of screen width.
     *
     * Snapped to the slider's 5 % step on the way in and out: Material's Slider
     * throws IllegalStateException rather than rounding, so a value written by an
     * older build (free-form corner drags) used to crash the Settings screen the
     * moment it opened.
     */
    var overlaySizePercent: Int
        get() = snappedSize(prefs.getInt(KEY_OVERLAY_SIZE, DEFAULT_OVERLAY_SIZE))
        set(value) = prefs.edit().putInt(KEY_OVERLAY_SIZE, snappedSize(value)).apply()

    /** Only fetch over unmetered networks. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /**
     * Refuse to show an image the user has already seen. On by default: the
     * point of a slideshow is variety, and repeats read as a bug. The seen set
     * is the image cache itself (ImageCache.urlTokens), so clearing the cache
     * allows images to appear again.
     */
    var avoidRepeats: Boolean
        get() = prefs.getBoolean(KEY_AVOID_REPEATS, true)
        set(value) = prefs.edit().putBoolean(KEY_AVOID_REPEATS, value).apply()

    /** Stops the NSFW opt-in prompt being asked twice. */
    var nsfwAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_NSFW_ACK, false)
        set(value) = prefs.edit().putBoolean(KEY_NSFW_ACK, value).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(l)
    }

    private fun snappedSize(raw: Int): Int =
        (raw / OVERLAY_SIZE_STEP * OVERLAY_SIZE_STEP)
            .coerceIn(OVERLAY_SIZE_MIN, OVERLAY_SIZE_MAX)

    companion object {
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 300
        const val DEFAULT_INTERVAL = 15

        /** Matches the Settings slider's stepSize and range exactly. */
        const val OVERLAY_SIZE_MIN = 20
        const val OVERLAY_SIZE_MAX = 90
        const val OVERLAY_SIZE_STEP = 5
        private const val DEFAULT_OVERLAY_SIZE = 45

        private const val KEY_QUERY = "query"
        private const val KEY_RATING = "rating"
        private const val KEY_SOURCE = "source"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_NIGHT = "night_mode"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_OVERLAY_SIZE = "overlay_size"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AVOID_REPEATS = "avoid_repeats"
        private const val KEY_NSFW_ACK = "nsfw_ack"
    }
}
