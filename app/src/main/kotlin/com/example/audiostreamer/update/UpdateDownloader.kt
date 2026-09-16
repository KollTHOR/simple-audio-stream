package com.example.audiostreamer.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloader(private val context: Context) {

    companion object {
        private const val TAG = "UpdateDownloader"
        const val EXPECTED_PACKAGE_NAME = "com.example.audiostreamer"
    }

    data class DownloadResult(
        val apkFile: File,
        val verifiedPackageName: String,
        val archiveVersionCode: Long,
        val archiveVersionName: String,
        val isChecksumVerified: Boolean
    )

    /**
     * Downloads an APK asset with progress reporting, verifies SHA-256 (if checksum asset exists),
     * and ensures the APK belongs strictly to [EXPECTED_PACKAGE_NAME].
     */
    suspend fun downloadAndVerify(
        release: GithubRelease,
        onProgress: (progressPercent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        val apkAsset = release.apkAsset ?: return@withContext Result.failure(
            IllegalArgumentException("Release ${release.tagName} has no APK asset attached.")
        )

        val targetDir = context.cacheDir
        val destinationFile = File(targetDir, "simple-audio-stream-${release.tagName}.apk")

        try {
            // Clean up any stale partial files
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            // 1. Download APK with redirect handling (AWS S3 / GitHub Release CDN)
            var currentUrl = apkAsset.downloadUrl
            var redirectCount = 0
            var conn: HttpURLConnection

            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                }

                val code = conn.responseCode
                if (code in 301..308) {
                    currentUrl = conn.getHeaderField("Location")
                        ?: throw RuntimeException("HTTP $code redirect without Location header")
                    redirectCount++
                    if (redirectCount > 6) throw RuntimeException("Too many HTTP redirects ($redirectCount)")
                } else if (code == 200) {
                    break
                } else {
                    throw RuntimeException("HTTP $code on downloading APK from $currentUrl")
                }
            }

            val totalLength = conn.contentLengthLong.takeIf { it > 0 } ?: apkAsset.sizeBytes
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalLength > 0) {
                            val percent = ((downloadedBytes * 100) / totalLength).toInt().coerceIn(0, 100)
                            onProgress(percent, downloadedBytes, totalLength)
                        }
                    }
                }
            }

            if (destinationFile.length() < 100_000) {
                destinationFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("Downloaded APK is truncated or invalid (${destinationFile.length()} bytes)")
                )
            }

            // 2. Checksum verification
            var checksumVerified = false
            if (release.checksumAsset != null) {
                val checksumText = fetchChecksumText(release.checksumAsset.downloadUrl)
                if (!checksumText.isNullOrBlank()) {
                    val matches = ChecksumVerifier.verify(destinationFile, checksumText)
                    if (!matches) {
                        val expected = ChecksumVerifier.parseExpectedHash(checksumText)
                        val actual = ChecksumVerifier.calculateSha256(destinationFile)
                        destinationFile.delete()
                        return@withContext Result.failure(
                            SecurityException("SHA-256 checksum mismatch!\nExpected: $expected\nActual: $actual")
                        )
                    }
                    checksumVerified = true
                }
            }

            // 3. Package Identity & Version Verification
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(destinationFile.absolutePath, 0)
                ?: run {
                    destinationFile.delete()
                    return@withContext Result.failure(
                        SecurityException("Package parser failed to read APK archive metadata.")
                    )
                }

            if (pkgInfo.packageName != EXPECTED_PACKAGE_NAME) {
                destinationFile.delete()
                return@withContext Result.failure(
                    SecurityException("Incompatible APK package: expected '$EXPECTED_PACKAGE_NAME', found '${pkgInfo.packageName}'")
                )
            }

            val rawVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            val versionName = pkgInfo.versionName ?: release.cleanVersion

            Result.success(
                DownloadResult(
                    apkFile = destinationFile,
                    verifiedPackageName = pkgInfo.packageName,
                    archiveVersionCode = rawVersionCode,
                    archiveVersionName = versionName,
                    isChecksumVerified = checksumVerified
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            if (destinationFile.exists()) destinationFile.delete()
            Result.failure(e)
        }
    }

    private fun fetchChecksumText(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SimpleAudioStream-Android")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching checksum file: ${e.message}")
            null
        }
    }

    fun cleanupCachedApks(preserveTag: String? = null) {
        try {
            listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
                dir.listFiles { _, name ->
                    name.startsWith("simple-audio-stream") && name.endsWith(".apk") &&
                        (preserveTag == null || !name.contains(preserveTag))
                }?.forEach { it.delete() }
            }
        } catch (ignored: Exception) {}
    }
}
