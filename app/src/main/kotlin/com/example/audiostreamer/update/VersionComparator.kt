package com.example.audiostreamer.update

/**
 * Handles deterministic version and build comparisons between remote releases and the installed app.
 */
object VersionComparator {

    /**
     * Evaluates compatibility and ordering between a release and the currently installed build.
     */
    fun compare(selected: GithubRelease, installed: BuildInfo): UpdateCompatibility {
        return compare(selected.parsedVersionCode, selected.cleanVersion, installed)
    }

    /**
     * Compares based on versionCode first if available, otherwise falls back to semantic versioning.
     */
    fun compare(selectedVersionCode: Long?, selectedVersionName: String, installed: BuildInfo): UpdateCompatibility {
        if (selectedVersionCode != null && selectedVersionCode > 0) {
            return when {
                selectedVersionCode > installed.versionCode -> UpdateCompatibility.NEWER
                selectedVersionCode == installed.versionCode -> UpdateCompatibility.SAME
                else -> UpdateCompatibility.OLDER
            }
        }

        val cmp = compareSemantic(selectedVersionName, installed.versionName)
        return when {
            cmp > 0 -> UpdateCompatibility.NEWER
            cmp == 0 -> UpdateCompatibility.SAME
            else -> UpdateCompatibility.OLDER
        }
    }

    /**
     * Compares two version strings (e.g. "1.8.14" vs "1.8.13", or "1.8.14-nightly.20260916" vs "1.8.14").
     * Returns > 0 if v1 > v2, < 0 if v1 < v2, 0 if equal.
     */
    fun compareSemantic(v1: String, v2: String): Int {
        val parts1 = parseNumericParts(v1)
        val parts2 = parseNumericParts(v2)

        // If one of the versions is a date-formatted nightly tag (e.g. "nightly-20260917-abc")
        // with no semantic version numbers, compare by build dates directly
        val date1 = extractDateOrTimestamp(v1)
        val date2 = extractDateOrTimestamp(v2)
        if (date1.isNotEmpty() && date2.isNotEmpty() && (parts1.isEmpty() || parts2.isEmpty())) {
            val dateCmp = date1.compareTo(date2)
            if (dateCmp != 0) return dateCmp
        }

        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }

        // Check for prerelease/nightly suffix if numeric segments are identical
        val isPre1 = isPrereleaseString(v1)
        val isPre2 = isPrereleaseString(v2)

        return when {
            isPre1 && !isPre2 -> -1 // e.g. 1.8.14-nightly is older than final 1.8.14
            !isPre1 && isPre2 -> 1  // e.g. final 1.8.14 is newer than 1.8.14-nightly
            isPre1 && isPre2 -> comparePrereleaseDates(v1, v2)
            else -> 0
        }
    }

    private fun isPrereleaseString(versionStr: String): Boolean {
        return versionStr.contains("nightly", ignoreCase = true) ||
            versionStr.contains("pre", ignoreCase = true) ||
            versionStr.contains("beta", ignoreCase = true) ||
            versionStr.contains("alpha", ignoreCase = true)
    }

    private fun comparePrereleaseDates(v1: String, v2: String): Int {
        val date1 = extractDateOrTimestamp(v1)
        val date2 = extractDateOrTimestamp(v2)
        return date1.compareTo(date2)
    }

    private fun extractDateOrTimestamp(str: String): String {
        val match = Regex("""\b(20\d{6})\b""").find(str)
        return match?.groupValues?.get(1) ?: ""
    }

    fun parseNumericParts(versionStr: String): List<Int> {
        val clean = versionStr.removePrefix("v")
            .substringBefore("-")
            .substringBefore("+")
            .trim()
        return clean.split(".").mapNotNull { it.toIntOrNull() }
    }
}
