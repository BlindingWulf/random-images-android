package com.blindingwulf.randomimages

import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

/**
 * Same contract for both sites, mirroring providers.py:
 *   fetchRandom(query, rating) -> ImageResult
 *   testAuth() -> Pair(ok, message)
 */
interface Provider {
    val name: String
    val hasAuth: Boolean
    fun fetchRandom(query: String, rating: Rating, exclude: List<String> = emptyList()): ImageResult
    fun testAuth(): AuthResult
}

data class AuthResult(val ok: Boolean, val message: String)

/** e621 official JSON API. Anonymous works for all ratings; a UA is mandatory. */
class E621Provider(
    private val username: String = "",
    private val apiKey: String = "",
) : Provider {

    override val name = "e621"
    override val hasAuth: Boolean get() = apiKey.isNotBlank()

    private val base = "https://e621.net"
    private val pageSize = 25
    private val throttle = Throttle(550)

    private fun headers(): Map<String, String> {
        val h = mutableMapOf("User-Agent" to Net.E621_UA)
        if (hasAuth) {
            val token = Base64.encodeToString(
                "$username:$apiKey".toByteArray(),
                Base64.NO_WRAP,
            )
            h["Authorization"] = "Basic $token"
        }
        return h
    }

    /** page is 1-based; e621 rejects page=0 with 410 Gone. */
    private fun search(tags: String, limit: Int, page: Int? = null): JSONObject {
        throttle.await()
        val params = mutableMapOf("tags" to tags, "limit" to limit.toString())
        if (page != null) params["page"] = page.toString()
        return JSONObject(Net.getString("$base/posts.json", params, headers()))
    }

    /**
     * e621 serializes a missing URL as JSON null, which org.json's optString
     * reads back as the literal string "null" — treat that as absent.
     */
    private fun fileUrlOf(post: JSONObject, obj: String): String {
        val u = post.optJSONObject(obj)?.optString("url").orEmpty()
        return u.takeUnless { it == "null" }.orEmpty()
    }

    override fun fetchRandom(query: String, rating: Rating, exclude: List<String>): ImageResult {
        // One-to-one rating mapping: Explicit means explicit only — selecting
        // it used to admit S posts, which read as a ratings bug. e621 honours
        // rating: server-side (verified live 2026-11-03), so it is sent as a
        // tag to widen the candidate pool; the client-side check below stays
        // as the guarantee whatever the server does.
        val allowed = when (rating) {
            Rating.NSFW -> setOf("e")
            Rating.QUESTIONABLE -> setOf("q")
            Rating.SFW -> setOf("s")
        }
        val ratingTag = "rating:" + allowed.first()
        // The blacklist never appears in the user's query string or the
        // status line: it is appended to the tags actually sent.
        val tags = Blacklist.compose(query.trim() + " " + ratingTag, exclude)

        val first = search(tags, pageSize)
        val page1 = first.optJSONArray("posts").toPostList()
        if (page1.isEmpty()) {
            // A narrow query can return an empty first page while other pages
            // still hold posts (and the sampled pages change every request),
            // so this is a re-drawable miss, not a dead end.
            return ImageResult.error(
                name, query, "e621: no posts match '$query'",
                retryable = true,
            )
        }

        // posts.json exposes no total count, so variety comes from sampling a
        // random page (1..10) rather than order=random, which is not random.
        var candidates = page1
        if (page1.size >= pageSize) {
            for (page in (1..10).shuffled().take(3)) {
                val posts = search(tags, pageSize, page).optJSONArray("posts").toPostList()
                if (posts.isNotEmpty()) {
                    candidates = posts
                    break
                }
            }
        }

        val usable = candidates.filter { post ->
            val r = post.optString("rating", "s")
            if (r !in allowed) return@filter false
            fileUrlOf(post, "file").isNotEmpty() || fileUrlOf(post, "sample").isNotEmpty()
        }
        if (usable.isEmpty()) {
            return ImageResult.error(
                name, query,
                "e621: no '$query' posts within allowed ratings (rating=${rating.level})",
                retryable = true,
            )
        }

        val post = usable[rnd().nextInt(usable.size)]
        val fileUrl = fileUrlOf(post, "file")
        val sampleUrl = fileUrlOf(post, "sample")
        val url = sampleUrl.ifEmpty { fileUrl }

        var tagText = ""
        val rawTags = post.opt("tags")
        when (rawTags) {
            is JSONObject -> {
                tagText = rawTags.keys().asSequence()
                    .flatMap { rawTags.optJSONArray(it).toStringList().asSequence() }
                    .joinToString(" ")
            }
            null -> Unit
            else -> tagText = rawTags.toString()
        }

        val fallbacks = listOf(fileUrl, sampleUrl).filter { it.isNotEmpty() && it != url }
        return ImageResult(
            url = url,
            source = name,
            query = query,
            credit = post.optString("uploader_name"),
            tags = tagText.take(300),
            rating = post.optString("rating"),
            fallbackUrls = fallbacks,
        )
    }

    override fun testAuth(): AuthResult {
        if (!hasAuth) {
            return AuthResult(true, "No API key - running anonymous (works for all ratings)")
        }
        return try {
            val data = search("dog rating:s", 1)
            val n = data.optJSONArray("posts")?.length() ?: 0
            AuthResult(true, "API key accepted ($n sample result(s) returned)")
        } catch (e: Exception) {
            val code = (e as? Net.HttpException)?.status
            if (code == 401) {
                AuthResult(false, "e621 rejected the API key (401) - check username/key")
            } else {
                AuthResult(false, "e621 error: ${e.message}")
            }
        }
    }
}

private fun org.json.JSONArray?.toPostList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

private fun <T> List<T>.shuffled(rng: java.util.Random): List<T> {
    val copy = ArrayList(this)
    java.util.Collections.shuffle(copy, rng)
    return copy
}
