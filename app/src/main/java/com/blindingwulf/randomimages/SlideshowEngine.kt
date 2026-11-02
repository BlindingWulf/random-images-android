package com.blindingwulf.randomimages

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the UI needs to draw one frame of the slideshow. */
data class EngineState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val current: ShownImage? = null,
    val status: String = "",
    val shownCount: Int = 0,
    val cacheCount: Int = 0,
    val cacheBytes: Long = 0,
)

/**
 * Drives "fetch a random image, show it, wait N seconds, repeat", including the
 * provider fallback order and the repeated-failure backoff from app.py._worker.
 *
 * Kept free of Android UI types so it can run inside the foreground service and
 * be unit-tested on its own.
 */
class SlideshowEngine(private val context: Context) {

    private val settings = Settings(context)
    private val secureStore = SecureStore(context)
    private val cache = ImageCache(context)
    private val log = RingLog(context)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var loopJob: Job? = null
    private var runningQuery: String? = null
    private var seq = 0

    /** Consecutive fetch attempts that only drew "nothing this time" misses. */
    private var softFailures = 0

    /**
     * URLs already on disk, mirrored from the cache directory listing (see
     * [ImageCache.urlTokens]). Refreshed whenever the cache changes, so pruning
     * an old image frees that image and clearing the cache allows repeats —
     * no separate history to drift out of step with the files.
     */
    private val seenTokens = HashSet<String>()

    init {
        seenTokens.addAll(cache.urlTokens())
    }

    private fun update(transform: (EngineState) -> EngineState) {
        _state.value = transform(_state.value)
    }

    private fun currentCredentials() = secureStore.load()

    private fun e621() = currentCredentials().let {
        E621Provider(it.e621Username, it.e621ApiKey)
    }

    private fun furAffinity() = currentCredentials().let {
        FurAffinityProvider(it.faUsername, it.faCookie)
    }

    /** Provider order for one attempt, shuffled so "both" really alternates. */
    fun providerOrder(mode: SourceMode = settings.source): List<Provider> {
        val order = buildList {
            if (mode == SourceMode.RANDOM || mode == SourceMode.E621) add(e621())
            if (mode == SourceMode.RANDOM || mode == SourceMode.FURAFFINITY) add(furAffinity())
        }
        return order.shuffled()
    }

    fun start() {
        val query = settings.query.trim()
        if (query.isEmpty()) {
            update { it.copy(status = "Type a search term first (e.g. 'dog').") }
            return
        }
        // Restarting with a different term (or after a rating/source change) is
        // the common gesture, so it has to take effect instead of being ignored
        // in favour of the loop that is already running.
        if (loopJob?.isActive == true) {
            if (query == runningQuery && !pendingRetarget) return
            loopJob?.cancel()
            pendingRetarget = false
        } else {
            update { it.copy(running = true) }
        }
        runningQuery = query
        update { it.copy(status = "Fetching '$query'...") }
        log.write("start query=$query rating=${settings.rating.level} source=${settings.source}")
        loopJob = scope.launch { runLoop(query) }
    }

    /** Set by the UI when rating/source changed, forcing the next start() to re-target. */
    @Volatile
    var pendingRetarget = false

    fun pause() {
        loopJob?.cancel()
        loopJob = null
        runningQuery = null
        update { it.copy(running = false, loading = false, status = "Paused.") }
        log.write("pause")
    }

    fun stop() {
        pause()
        update { it.copy(status = "Stopped.") }
    }

    /** One immediate fetch, leaving the running timer alone. */
    fun next() {
        val query = settings.query.trim()
        if (query.isEmpty()) return
        scope.launch { fetchAndShow(query, userInitiated = true) }
    }

    /** Walks the cache backwards — the mobile stand-in for a history stack. */
    fun previous() {
        val files = cache.filesNewestFirst()
        val currentPath = _state.value.current?.file?.absolutePath
        val target = files.firstOrNull { it.absolutePath != currentPath } ?: run {
            update { it.copy(status = "Nothing older in the cache yet.") }
            return
        }
        scope.launch {
            update { it.copy(loading = true) }
            val format = target.extension.ifEmpty { "jpg" }
            val placeholder = ImageResult(url = "", source = "cache", query = settings.query)
            update {
                it.copy(
                    loading = false,
                    current = ShownImage(placeholder, target, format),
                    status = "From cache.",
                )
            }
        }
    }

    private suspend fun runLoop(query: String) {
        var consecutiveFailures = 0
        while (scope.isActive && _state.value.running) {
            val outcome = fetchAndShow(query, userInitiated = false)
            if (outcome) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= REPEATED_FAILURE_LIMIT) {
                    update {
                        it.copy(status = "Repeated failures - check connection or search term. Last: ${it.status}")
                    }
                    delay(BACKOFF_MS)
                }
            }
            // Interruptible wait so a pause or interval change takes effect at once.
            val intervalMs = settings.intervalSec * 1000L
            val deadline = System.currentTimeMillis() + intervalMs
            while (_state.value.running && System.currentTimeMillis() < deadline) {
                delay(SMALL_SLEEP_MS)
            }
        }
    }

    /**
     * True when an identical image is already in the cache. The URL is the
     * identity: e621 encodes the post's file hash and FurAffinity its submission
     * id, so both are stable across requests.
     */
    private fun isRepeat(url: String): Boolean =
        url.isNotEmpty() && ImageCache.tokenOf(url) in seenTokens

    /**
     * Re-reads the tokens from the cache directory. Called after every save (the
     * write may have pruned the oldest file) and whenever the cache is cleared,
     * which is what makes "clear cache" allow images to appear again.
     */
    fun refreshSeenTokens() {
        seenTokens.clear()
        seenTokens.addAll(cache.urlTokens())
    }

    /** Returns true when an image was downloaded and cached. */
    private fun fetchAndShow(query: String, userInitiated: Boolean): Boolean {
        if (settings.wifiOnly && !isUnmetered()) {
            update { it.copy(status = "Wi-Fi only is on and this network is metered.") }
            return false
        }
        update { it.copy(loading = true, status = "Fetching...") }
        val rating = settings.rating
        val avoidRepeats = settings.avoidRepeats
        var lastError = "no provider tried"
        var softMisses = 0
        var hardError: String? = null
        var round = 1
        while (round <= MAX_FETCH_ROUNDS) {
            // Only the closing round accepts a repeat, so a search that has
            // cached every image it can find still shows something.
            val acceptRepeat = round == MAX_FETCH_ROUNDS
            var skipped = 0
            if (acceptRepeat && avoidRepeats) {
                log.write("no unseen '$query' image found in $MAX_FETCH_ROUNDS rounds - accepting a repeat")
            }
            for (provider in providerOrder()) {
                val result = try {
                    provider.fetchRandom(query, rating)
                } catch (e: Exception) {
                    log.write("provider ${provider.name} failed: ${e.message}")
                    lastError = "${provider.name}: ${e.message}"
                    hardError = lastError
                    continue
                }
                if (!result.isOk) {
                    lastError = result.error ?: "empty result"
                    if (result.retryable) {
                        softMisses++
                        log.write("miss ${result.source}: $lastError")
                    } else {
                        hardError = lastError
                    }
                    continue
                }
                if (!acceptRepeat && avoidRepeats && isRepeat(result.url)) {
                    skipped++
                    lastError = "only repeats so far"
                    log.write("skip repeat ${result.source} ${result.url.take(70)}")
                    continue
                }
                val downloaded = try {
                    downloadImage(result)
                } catch (e: Exception) {
                    log.write("download failed: ${e.message}")
                    update { it.copy(loading = false, status = "[${result.source}] download failed: ${e.message}") }
                    return false
                }
                seq++
                val file = cache.save(downloaded.bytes, result.source, downloaded.format, seq, result.url)
                refreshSeenTokens()
                softFailures = 0
                val (count, bytes) = cache.stats()
                update {
                    it.copy(
                        loading = false,
                        current = ShownImage(result, file ?: File(""), downloaded.format),
                        shownCount = it.shownCount + 1,
                        status = statusLine(result),
                        cacheCount = count,
                        cacheBytes = bytes,
                    )
                }
                log.write("shown ${result.source} ${result.url.take(80)}")
                return true
            }
            // A round with neither skips nor re-drawable misses means the
            // providers hard-failed; asking again cannot change that.
            if (skipped == 0 && softMisses == 0) break
            round++
        }
        val quiet = hardError == null && softMisses > 0
        val status = if (quiet) {
            softFailures++
            // Show the exact provider miss only once it keeps repeating; a
            // niche tag plus a strict rating filter draws blanks all the time.
            if (softFailures < SOFT_ANNOUNCE_LIMIT) {
                "No match for '$query' yet - still trying..."
            } else {
                "$lastError - retrying..."
            }
        } else {
            softFailures = 0
            "$lastError - retrying..."
        }
        update { it.copy(loading = false, status = status) }
        if (userInitiated) log.write("manual next failed: $lastError")
        return false
    }

    private fun statusLine(result: ImageResult): String = buildString {
        append(result.source)
        if (result.rating.isNotBlank()) append(" · ${result.rating}")
        if (result.credit.isNotBlank()) append(" · ${result.credit}")
    }

    /**
     * True when the active network is unmetered. getActiveNetwork()/
     * NetworkCapabilities are API 23, so 21-22 fall back to the deprecated
     * per-type connection info (Wi-Fi treated as unmetered, everything else
     * metered) rather than crashing on the old phones this build targets.
     */
    @Suppress("DEPRECATION")
    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
    }

    fun refreshCacheStats() {
        val (count, bytes) = cache.stats()
        update { it.copy(cacheCount = count, cacheBytes = bytes) }
    }

    fun cache(): ImageCache = cache

    /** Test connection for both sites, mirroring app.on_test_connections. */
    fun testConnections(): Map<String, AuthResult> = providerOrder(SourceMode.RANDOM)
        .associate { it.name to it.testAuth() }

    companion object {
        private const val REPEATED_FAILURE_LIMIT = 4
        private const val BACKOFF_MS = 10_000L
        private const val SMALL_SLEEP_MS = 250L

        /** Re-draws a provider may make while hunting for an unseen image. */
        private const val MAX_FETCH_ROUNDS = 4

        /** Consecutive blank draws before the exact provider error is shown. */
        private const val SOFT_ANNOUNCE_LIMIT = 3
    }
}

/**
 * Small on-disk log (cacheDir/riv.log) so behaviour can be inspected with
 * `adb exec-out run-as <pkg> cat cache/riv.log` without a logcat filter.
 */
class RingLog(context: Context) {
    private val file = File(context.cacheDir, "riv.log")
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun write(message: String) {
        runCatching {
            val text = "${stamp.format(Date())} $message\n"
            file.appendText(text)
            if (file.length() > MAX_BYTES) {
                val keep = file.readBytes().takeLast(MAX_BYTES / 2).toByteArray()
                file.writeBytes(keep)
            }
        }
        android.util.Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "RandomImages"
        private const val MAX_BYTES = 256 * 1024
    }
}
