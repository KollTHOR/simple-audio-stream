package com.example.audiostreamer.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class UpdateRepository(private val context: Context) {

    companion object {
        private const val TAG = "UpdateRepository"
        private const val PREFS_NAME = "update_center_prefs"
        private const val KEY_CHANNEL = "update_channel"
        private const val KEY_CACHED_RELEASES = "cached_releases_json"
        private const val KEY_CACHE_TIMESTAMP = "cached_releases_timestamp"
        private const val CACHE_MAX_AGE_MS = 2 * 60 * 1000L // 2 minutes

        const val REPO_OWNER = "KollTHOR"
        const val REPO_NAME = "simple-audio-stream"
        const val GITHUB_API_BASE = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME"

        /**
         * Filters releases according to the selected channel.
         * If STABLE: returns only non-prerelease releases.
         * If NIGHTLY: returns all releases (prereleases and stable).
         */
        fun filterByChannel(releases: List<GithubRelease>, channel: ReleaseChannel): List<GithubRelease> {
            return when (channel) {
                ReleaseChannel.STABLE -> releases.filter { it.channel == ReleaseChannel.STABLE }
                ReleaseChannel.NIGHTLY -> releases
            }
        }

        /**
         * Sorts releases strictly descending:
         * 1. parsedVersionCode descending (if available for both and different)
         * 2. VersionComparator.compareSemantic descending
         * 3. publishedAt descending
         * 4. release id descending
         */
        fun sortReleasesDescending(releases: List<GithubRelease>): List<GithubRelease> {
            return releases.sortedWith { r1, r2 ->
                val c1 = r1.parsedVersionCode
                val c2 = r2.parsedVersionCode
                if (c1 != null && c2 != null && c1 != c2) {
                    return@sortedWith c2.compareTo(c1)
                }
                val semCmp = VersionComparator.compareSemantic(r1.cleanVersion, r2.cleanVersion)
                if (semCmp != 0) {
                    return@sortedWith -semCmp
                }
                val pubCmp = r1.publishedAt.compareTo(r2.publishedAt)
                if (pubCmp != 0) {
                    return@sortedWith -pubCmp
                }
                r2.id.compareTo(r1.id)
            }
        }

        /**
         * Finds the latest update candidate for the specified channel that has an APK attached.
         */
        fun findLatestCandidate(releases: List<GithubRelease>, channel: ReleaseChannel): GithubRelease? {
            val filtered = filterByChannel(releases, channel)
            val sorted = sortReleasesDescending(filtered)
            return sorted.firstOrNull { it.apkAsset != null }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPreferredChannel(): ReleaseChannel {
        val defaultChannel = BuildInfo.current().channel.name
        val str = prefs.getString(KEY_CHANNEL, defaultChannel)
        return try {
            ReleaseChannel.valueOf(str ?: defaultChannel)
        } catch (e: Exception) {
            ReleaseChannel.fromString(defaultChannel)
        }
    }

    fun setPreferredChannel(channel: ReleaseChannel) {
        prefs.edit().putString(KEY_CHANNEL, channel.name).apply()
    }

    data class ReleasesResult(
        val releases: List<GithubRelease>,
        val isFromCache: Boolean,
        val hasMorePages: Boolean,
        val page: Int,
        val errorMessage: String? = null
    )

    /**
     * Fetches a page of releases from GitHub or local cache.
     *
     * @param page 1-indexed page number
     * @param perPage items per page (default 30)
     * @param forceRefresh if true, bypasses local cache for page 1
     */
    suspend fun fetchReleases(
        page: Int = 1,
        perPage: Int = 30,
        forceRefresh: Boolean = false
    ): ReleasesResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val cachedJson = prefs.getString(KEY_CACHED_RELEASES, null)

        // For page 1, if cache is fresh and forceRefresh is false, serve from cache
        if (page == 1 && !forceRefresh && !cachedJson.isNullOrEmpty() && (now - cacheTimestamp < CACHE_MAX_AGE_MS)) {
            try {
                val array = JSONArray(cachedJson)
                val list = GithubRelease.parseList(array)
                if (list.isNotEmpty()) {
                    val sortedList = sortReleasesDescending(list)
                    return@withContext ReleasesResult(
                        releases = sortedList,
                        isFromCache = true,
                        hasMorePages = sortedList.size >= perPage,
                        page = 1
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading releases cache", e)
            }
        }

        try {
            val endpoint = "$GITHUB_API_BASE/releases?page=$page&per_page=$perPage"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "SimpleAudioStream-Android")
            }

            val code = conn.responseCode
            if (code == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(jsonText)
                val parsedList = GithubRelease.parseList(array)
                val sortedList = sortReleasesDescending(parsedList)

                // Cache page 1 results locally
                if (page == 1) {
                    prefs.edit()
                        .putString(KEY_CACHED_RELEASES, jsonText)
                        .putLong(KEY_CACHE_TIMESTAMP, now)
                        .apply()
                }

                ReleasesResult(
                    releases = sortedList,
                    isFromCache = false,
                    hasMorePages = sortedList.size >= perPage,
                    page = page
                )
            } else if (code == 403) {
                val errorMsg = "GitHub API rate limit reached. Showing offline cache if available."
                serveCacheOrError(cachedJson, errorMsg, page)
            } else {
                val errorMsg = "GitHub API returned HTTP $code"
                serveCacheOrError(cachedJson, errorMsg, page)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network failure fetching releases: ${e.message}")
            val errorMsg = "Network error: ${e.localizedMessage ?: "Unable to connect to GitHub"}"
            serveCacheOrError(cachedJson, errorMsg, page)
        }
    }

    private fun serveCacheOrError(cachedJson: String?, errorMsg: String, page: Int): ReleasesResult {
        if (!cachedJson.isNullOrEmpty() && page == 1) {
            return try {
                val array = JSONArray(cachedJson)
                val list = GithubRelease.parseList(array)
                val sortedList = sortReleasesDescending(list)
                ReleasesResult(
                    releases = sortedList,
                    isFromCache = true,
                    hasMorePages = false,
                    page = 1,
                    errorMessage = errorMsg
                )
            } catch (ignored: Exception) {
                ReleasesResult(emptyList(), isFromCache = false, hasMorePages = false, page = page, errorMessage = errorMsg)
            }
        }
        return ReleasesResult(emptyList(), isFromCache = false, hasMorePages = false, page = page, errorMessage = errorMsg)
    }

    fun filterByChannel(releases: List<GithubRelease>, channel: ReleaseChannel): List<GithubRelease> =
        Companion.filterByChannel(releases, channel)

    fun findLatestCandidate(releases: List<GithubRelease>, channel: ReleaseChannel): GithubRelease? =
        Companion.findLatestCandidate(releases, channel)

    fun sortReleasesDescending(releases: List<GithubRelease>): List<GithubRelease> =
        Companion.sortReleasesDescending(releases)
}
