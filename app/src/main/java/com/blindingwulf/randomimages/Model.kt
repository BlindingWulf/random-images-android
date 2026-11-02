package com.blindingwulf.randomimages

import java.util.concurrent.ThreadLocalRandom

internal fun rnd(): ThreadLocalRandom = ThreadLocalRandom.current()

/** Content level the user selected. Mirrors providers.py: "sfw" / "q" / "nsfw". */
enum class Rating(val level: String) {
    SFW("sfw"),
    QUESTIONABLE("q"),
    NSFW("nsfw"),
    ;

    companion object {
        fun fromLevel(level: String?): Rating = entries.firstOrNull { it.level == level } ?: SFW
    }
}

/** Which site to pull from. Mirrors app.py SOURCES. */
enum class SourceMode { RANDOM, E621, FURAFFINITY }

/** One image selected by a provider, before the bytes are downloaded. */
data class ImageResult(
    val url: String,
    val source: String,
    val query: String,
    val credit: String = "",
    val tags: String = "",
    val rating: String = "",
    /** Tried in order when [url] fails, mirroring providers.fallback_urls. */
    val fallbackUrls: List<String> = emptyList(),
    val error: String? = null,
    /** A "nothing this time" miss (e.g. every sampled post failed the rating
     * filter) — worth re-drawing quietly instead of showing as an error. */
    val retryable: Boolean = false,
) {
    val isOk: Boolean get() = error == null && url.isNotEmpty()

    companion object {
        fun error(source: String, query: String, message: String, retryable: Boolean = false) =
            ImageResult(url = "", source = source, query = query, error = message, retryable = retryable)
    }
}

/** A downloaded image plus the file it was written to in the cache. */
data class ShownImage(
    val result: ImageResult,
    val file: java.io.File,
    val format: String,
)
