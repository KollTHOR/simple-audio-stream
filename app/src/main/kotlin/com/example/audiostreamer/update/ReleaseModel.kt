package com.example.audiostreamer.update

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val isApk: Boolean,
    val isSha256: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): ReleaseAsset {
            val name = json.optString("name", "")
            return ReleaseAsset(
                id = json.optLong("id", 0L),
                name = name,
                downloadUrl = json.optString("browser_download_url", ""),
                sizeBytes = json.optLong("size", 0L),
                isApk = name.endsWith(".apk", ignoreCase = true),
                isSha256 = name.endsWith(".sha256", ignoreCase = true)
            )
        }
    }
}

enum class UpdateCompatibility {
    NEWER,
    SAME,
    OLDER
}

data class GithubRelease(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val isPrerelease: Boolean,
    val htmlUrl: String,
    val apkAsset: ReleaseAsset?,
    val checksumAsset: ReleaseAsset?,
    val channel: ReleaseChannel,
    val parsedVersionName: String,
    val parsedVersionCode: Long?,
    val commitSha: String?
) {
    val cleanVersion: String
        get() = parsedVersionName.ifEmpty { tagName.removePrefix("v").trim() }

    val formattedSize: String
        get() = apkAsset?.let {
            val mb = it.sizeBytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB", mb)
        } ?: "No APK"

    val formattedPublishDate: String
        get() = try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(publishedAt)
            if (date != null) {
                val output = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                output.format(date)
            } else {
                publishedAt.take(10)
            }
        } catch (e: Exception) {
            publishedAt.take(10)
        }

    val commitShort: String?
        get() = commitSha?.let { if (it.length > 7) it.take(7) else it }

    companion object {
        fun fromJson(json: JSONObject): GithubRelease {
            val id = json.optLong("id", 0L)
            val tagName = json.optString("tag_name", "")
            val name = json.optString("name", tagName)
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")
            val isPrerelease = json.optBoolean("prerelease", false)
            val htmlUrl = json.optString("html_url", "")

            val assetsJson = json.optJSONArray("assets") ?: JSONArray()
            var apk: ReleaseAsset? = null
            var checksum: ReleaseAsset? = null

            for (i in 0 until assetsJson.length()) {
                val assetObj = assetsJson.getJSONObject(i)
                val asset = ReleaseAsset.fromJson(assetObj)
                if (asset.isApk && apk == null) {
                    apk = asset
                } else if (asset.isSha256 && checksum == null) {
                    checksum = asset
                }
            }

            // A release is NIGHTLY if prerelease == true, or tag/name contains "nightly"
            val isNightly = isPrerelease ||
                tagName.contains("nightly", ignoreCase = true) ||
                name.contains("nightly", ignoreCase = true)
            val channel = if (isNightly) ReleaseChannel.NIGHTLY else ReleaseChannel.STABLE

            val commitSha = parseCommitSha(body, tagName)
            val versionCode = parseVersionCode(body, tagName)
            val versionName = parseVersionName(body, tagName)

            return GithubRelease(
                id = id,
                tagName = tagName,
                name = name,
                body = body,
                publishedAt = publishedAt,
                isPrerelease = isPrerelease,
                htmlUrl = htmlUrl,
                apkAsset = apk,
                checksumAsset = checksum,
                channel = channel,
                parsedVersionName = versionName,
                parsedVersionCode = versionCode,
                commitSha = commitSha
            )
        }

        fun parseList(jsonArray: JSONArray): List<GithubRelease> {
            val list = mutableListOf<GithubRelease>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                val tag = obj.optString("tag_name", "")
                if (tag.isEmpty()) continue
                list.add(fromJson(obj))
            }
            return list
        }

        private fun parseCommitSha(body: String, tag: String): String? {
            val metaMatch = Regex("""commit[:=]\s*([a-f0-9]{7,40})""", RegexOption.IGNORE_CASE).find(body)
            if (metaMatch != null) return metaMatch.groupValues[1].take(7)
            val tagMatch = Regex("""\+([a-f0-9]{7,40})""").find(tag)
            if (tagMatch != null) return tagMatch.groupValues[1].take(7)
            val nightlyMatch = Regex("""nightly-\d{8}-(?:b\d+-)?([a-f0-9]{7,40})""", RegexOption.IGNORE_CASE).find(tag)
            if (nightlyMatch != null) return nightlyMatch.groupValues[1].take(7)
            return null
        }

        private fun parseVersionCode(body: String, tag: String): Long? {
            val metaMatch = Regex("""versionCode[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)
            if (metaMatch != null) return metaMatch.groupValues[1].toLongOrNull()
            val buildMatch = Regex("""\*{0,2}Build:?\*{0,2}\s*`?(\d+)`?""", RegexOption.IGNORE_CASE).find(body)
            if (buildMatch != null) return buildMatch.groupValues[1].toLongOrNull()
            val tagBuildMatch = Regex("""nightly-\d{8}-b(\d+)-""", RegexOption.IGNORE_CASE).find(tag)
            if (tagBuildMatch != null) return tagBuildMatch.groupValues[1].toLongOrNull()
            return null
        }

        private fun parseVersionName(body: String, tag: String): String {
            val metaMatch = Regex("""versionName[:=]\s*([^\s>]+)""", RegexOption.IGNORE_CASE).find(body)
            if (metaMatch != null) return metaMatch.groupValues[1].trim()
            val bodyMatch = Regex("""\*{0,2}Version:?\*{0,2}\s*`?([0-9]+\.[0-9]+[^`\r\n\s]*)`?""", RegexOption.IGNORE_CASE).find(body)
            if (bodyMatch != null) return bodyMatch.groupValues[1].trim()
            return tag.removePrefix("v").trim()
        }
    }
}
