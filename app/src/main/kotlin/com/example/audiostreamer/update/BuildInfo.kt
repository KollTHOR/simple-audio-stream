package com.example.audiostreamer.update

import com.example.audiostreamer.BuildConfig

/**
 * Release channel classification.
 */
enum class ReleaseChannel(val displayName: String) {
    STABLE("Stable"),
    NIGHTLY("Nightly");

    companion object {
        fun fromString(value: String?): ReleaseChannel {
            return if (value.equals("nightly", ignoreCase = true)) NIGHTLY else STABLE
        }
    }
}

/**
 * Encapsulates the current installation metadata and build identity.
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Long,
    val channel: ReleaseChannel,
    val gitCommitSha: String,
    val buildTimestamp: String,
    val baseVersionName: String
) {
    val displayVersion: String
        get() = "Version $versionName (Build $versionCode)"

    val commitShort: String
        get() = if (gitCommitSha.length > 7) gitCommitSha.take(7) else gitCommitSha

    val formattedBuildDate: String
        get() = if (buildTimestamp.length == 8) {
            "${buildTimestamp.substring(0, 4)}-${buildTimestamp.substring(4, 6)}-${buildTimestamp.substring(6, 8)}"
        } else {
            buildTimestamp
        }

    companion object {
        fun current(): BuildInfo {
            return BuildInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                channel = ReleaseChannel.fromString(BuildConfig.BUILD_CHANNEL),
                gitCommitSha = BuildConfig.GIT_COMMIT_SHA,
                buildTimestamp = BuildConfig.BUILD_TIMESTAMP,
                baseVersionName = BuildConfig.BASE_VERSION_NAME
            )
        }
    }
}
