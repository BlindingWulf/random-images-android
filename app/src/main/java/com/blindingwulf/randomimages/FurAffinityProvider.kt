package com.blindingwulf.randomimages

/**
 * FurAffinity has no public API (the legacy JSON service is dead, the new one is
 * closed beta), so this scrapes the server-rendered search page exactly like
 * providers.py: results are <figure id="sid-{id}" class="r-{rating} t-image u-{user}">
 * and the full-size URL is derived from the thumbnail's timestamp + extension.
 *
 * Mature/adult results only appear with a logged-in session cookie (FAC); the
 * login form is behind a Cloudflare Turnstile captcha, so the cookie is copied
 * from a browser session instead.
 */
class FurAffinityProvider(
    private val username: String = "",
    private val cookie: String = "",
) : Provider {

    override val name = "FurAffinity"
    override val hasAuth: Boolean get() = cookie.isNotBlank()

    private val base = "https://www.furaffinity.net"
    private val throttle = Throttle(1100)

    companion object {
        private val FIGURE_RE =
            Regex("""<figure id="sid-(\d+)"[^>]*class="([^"]*)"""")
    }

    private fun headers(): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to Net.FA_UA,
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        if (hasAuth) {
            // Tolerate pasting a bare cookie value instead of "NAME=value".
            h["Cookie"] = if (!cookie.substringBefore(';').contains('=')) "FAC=$cookie" else cookie
        }
        return h
    }

    private fun searchPage(query: String, rating: Rating, page: Int): String {
        throttle.await()
        val params = mutableMapOf("q" to query, "page" to page.toString())
        params["rating-general"] = "1"
        if (rating == Rating.QUESTIONABLE || rating == Rating.NSFW) params["rating-mature"] = "1"
        if (rating == Rating.NSFW) params["rating-adult"] = "1"
        return Net.getString("$base/search/", params, headers())
    }

    override fun fetchRandom(query: String, rating: Rating): ImageResult {
        if (rating == Rating.NSFW && !hasAuth) {
            return ImageResult.error(
                name, query,
                "FurAffinity requires a session cookie for NSFW: log in in your browser, " +
                    "then paste the FAC cookie value into the FurAffinity section",
            )
        }

        val page = rnd().nextInt(1, 5)
        val html = try {
            searchPage(query, rating, page)
        } catch (e: Exception) {
            return ImageResult.error(name, query, "FurAffinity: ${e.message}")
        }

        var images = FIGURE_RE.findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].split(" ") }
            .filter { "t-image" in it.second }
            .toList()
        images = when (rating) {
            Rating.QUESTIONABLE -> images.filterNot { "r-adult" in it.second }
            Rating.SFW -> images.filterNot { "r-adult" in it.second || "r-mature" in it.second }
            Rating.NSFW -> images
        }
        if (images.isEmpty()) {
            // A random 1..4 page can legitimately be empty while the search has
            // results, so this is a miss to re-draw, not an error to display.
            return ImageResult.error(
                name, query, "FurAffinity: no image results for '$query' (page $page)",
                retryable = true,
            )
        }

        val (fid, classes) = images[rnd().nextInt(images.size)]
        val ratingLabel = when {
            "r-adult" in classes -> "adult"
            "r-mature" in classes -> "mature"
            else -> "general"
        }
        val user = classes.firstOrNull { it.startsWith("u-") }?.removePrefix("u-").orEmpty()

        // Read the thumbnail URL embedded for this submission to recover the
        // timestamp and extension needed to build the full-size URL.
        val start = html.indexOf("id=\"sid-$fid\"")
        val seg = if (start < 0) "" else html.substring(start, minOf(start + 3000, html.length))
        val thumbMatch = Regex("""t\.furaffinity\.net/$fid@(\w+)-(\d+)\.(\w+)""").find(seg)
        if (thumbMatch == null) {
            return ImageResult.error(
                name, query, "FurAffinity: no thumbnail data for submission $fid"
            )
        }
        val ts = thumbMatch.groupValues[2]
        val ext = thumbMatch.groupValues[3]

        val fullUrl = "https://t.furaffinity.net/$fid@1000-$ts.$ext"
        val thumbUrl = "https://t.furaffinity.net/$fid@200-$ts.$ext"
        return ImageResult(
            url = fullUrl,
            source = name,
            query = query,
            credit = user,
            tags = query,
            rating = ratingLabel,
            fallbackUrls = listOf(thumbUrl).filter { it != fullUrl },
        )
    }

    override fun testAuth(): AuthResult {
        if (!hasAuth) {
            return AuthResult(true, "No cookie - anonymous access (SFW/general only)")
        }
        return try {
            val html = searchPage("cat", Rating.NSFW, 1)
            val ratings = FIGURE_RE.findAll(html)
                .map { m ->
                    val classes = m.groupValues[2].split(" ")
                    when {
                        "r-adult" in classes -> "adult"
                        "r-mature" in classes -> "mature"
                        else -> "general"
                    }
                }
                .toSet()
            if (ratings.any { it == "mature" || it == "adult" }) {
                AuthResult(true, "Cookie works - NSFW visible (ratings: ${ratings.sorted().joinToString()})")
            } else {
                AuthResult(
                    false,
                    "Cookie accepted but no NSFW results - cookie may be expired " +
                        "or account not approved for mature content",
                )
            }
        } catch (e: Exception) {
            AuthResult(false, "FurAffinity error: ${e.message} - cookie may be expired")
        }
    }
}

/**
 * Downloads the image, trying fallback URLs in order, then identifies the format
 * from magic bytes so the cache keeps the right extension (mirrors
 * providers.download_image + imgcache._ext).
 */
fun downloadImage(result: ImageResult): DownloadedImage {
    val referer = if (result.source == "FurAffinity") Net.REFERER_FA else Net.REFERER_E621
    val headers = mapOf("User-Agent" to Net.FA_UA, "Referer" to referer)
    var lastErr: String? = null
    for (url in (listOf(result.url) + result.fallbackUrls).filter { it.isNotEmpty() }) {
        try {
            val data = Net.getBytes(url, headers)
            return DownloadedImage(data, sniffFormat(data))
        } catch (e: Exception) {
            lastErr = "$url -> ${e.message}"
        }
    }
    throw java.io.IOException("Could not download image: $lastErr")
}

data class DownloadedImage(val bytes: ByteArray, val format: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DownloadedImage && other.bytes.contentEquals(bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Magic-byte sniffing; falls back to the URL extension, then to jpg. */
fun sniffFormat(data: ByteArray, url: String = ""): String {
    fun starts(vararg sig: Int) =
        data.size >= sig.size && sig.withIndex().all { (i, b) -> data[i].toInt() and 0xFF == b }
    return when {
        starts(0xFF, 0xD8, 0xFF) -> "jpg"
        starts(0x89, 0x50, 0x4E, 0x47) -> "png"
        starts(0x47, 0x49, 0x46, 0x38) -> "gif"
        data.size > 12 &&
            data[0].toInt() == 'R'.code && data[1].toInt() == 'I'.code &&
            data[2].toInt() == 'F'.code && data[3].toInt() == 'F'.code &&
            data[8].toInt() == 'W'.code && data[9].toInt() == 'E'.code &&
            data[10].toInt() == 'B'.code && data[11].toInt() == 'P'.code -> "webp"
        data.size > 12 &&
            data[0].toInt() == 'I'.code && data[1].toInt() == 'I'.code -> "tiff"
        else -> when (url.substringAfterLast('.').substringBefore('?').lowercase()) {
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
    }
}
