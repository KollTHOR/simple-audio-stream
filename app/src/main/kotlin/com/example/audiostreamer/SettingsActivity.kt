package com.example.audiostreamer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val GITHUB_REPO_URL = "https://github.com/KollTHOR/simple-audio-stream"
        private const val GITHUB_API_RELEASES =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases?per_page=10"
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases/latest"
    }

    private lateinit var btnBack: ImageView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnGithubRepo: MaterialButton
    private lateinit var btnAppInfo: MaterialButton
    private lateinit var btnAccessibilitySettings: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var toggleProfileGroup: MaterialButtonToggleGroup
    private lateinit var btnProfileMusic: MaterialButton
    private lateinit var btnProfileLowLatency: MaterialButton
    private lateinit var tvProfileDescription: TextView

    enum class UpdateState {
        CHECK,
        DOWNLOAD,
        INSTALL
    }

    private var updateState = UpdateState.CHECK
    private var latestReleaseTag: String? = null
    private var latestApkUrl: String? = null
    private var downloadedApkFile: File? = null
    private var isWaitingForInstallPermission = false

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = false
                val file = downloadedApkFile
                if (file != null && file.exists() && file.length() > 500_000) {
                    installApk(file)
                }
            } else {
                Toast.makeText(this, "Install permission is required to update", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_settings)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        btnBack = findViewById(R.id.btn_back)
        tvAppVersion = findViewById(R.id.tv_app_version)
        btnGithubRepo = findViewById(R.id.btn_github_repo)
        btnAccessibilitySettings = findViewById(R.id.btn_accessibility_settings)
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        pbDownload = findViewById(R.id.pb_download)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        toggleProfileGroup = findViewById(R.id.toggle_profile_group)
        btnProfileMusic = findViewById(R.id.btn_profile_music)
        btnProfileLowLatency = findViewById(R.id.btn_profile_low_latency)
        tvProfileDescription = findViewById(R.id.tv_profile_description)

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val currentProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
        if (currentProfile == AudioConfig.PROFILE_LOW_LATENCY) {
            toggleProfileGroup.check(R.id.btn_profile_low_latency)
        } else {
            toggleProfileGroup.check(R.id.btn_profile_music)
        }
        updateProfileUi(currentProfile)

        toggleProfileGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selected = if (checkedId == R.id.btn_profile_low_latency) {
                    AudioConfig.PROFILE_LOW_LATENCY
                } else {
                    AudioConfig.PROFILE_MUSIC
                }
                prefs.edit().putString(AudioConfig.PREF_KEY_PROFILE, selected).apply()
                updateProfileUi(selected)
            }
        }

        btnBack.setOnClickListener { finish() }

        val currentVersion = BuildConfig.VERSION_NAME
        tvAppVersion.text = "Version $currentVersion (Build ${BuildConfig.VERSION_CODE})"

        btnGithubRepo.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }

        btnAppInfo = findViewById(R.id.btn_app_info)
        btnAppInfo.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Tap the 3 dots at top-right -> Allow restricted settings", Toast.LENGTH_LONG).show()
        }

        btnAccessibilitySettings.setOnClickListener {
            if (VolumeKeyInterceptorService.isRunning.get()) {
                Toast.makeText(this, "Background volume key interception is active", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Turn on Audio Streamer in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnCheckUpdate.text = "Check for Updates"
        updateState = UpdateState.CHECK

        btnCheckUpdate.setOnClickListener {
            when (updateState) {
                UpdateState.CHECK -> checkForUpdates()
                UpdateState.DOWNLOAD -> {
                    val url = latestApkUrl
                    val tag = latestReleaseTag
                    if (url != null && tag != null) {
                        downloadAndPromptInstall(url, tag)
                    } else {
                        checkForUpdates()
                    }
                }
                UpdateState.INSTALL -> {
                    val file = downloadedApkFile
                    if (file != null && file.exists() && file.length() > 500_000) {
                        checkPermissionAndInstall(file)
                    } else {
                        checkForUpdates()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
        checkInstallPermissionOnResume()
    }

    private fun checkInstallPermissionOnResume() {
        if (isWaitingForInstallPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = false
                val file = downloadedApkFile
                if (file != null && file.exists() && file.length() > 500_000) {
                    installApk(file)
                }
            }
        }
    }

    private fun updateProfileUi(profile: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        if (profile == AudioConfig.PROFILE_LOW_LATENCY) {
            btnProfileLowLatency.backgroundTintList = ColorStateList.valueOf(colorPrimary)
            btnProfileLowLatency.setTextColor(Color.WHITE)
            btnProfileMusic.backgroundTintList = ColorStateList.valueOf(colorCard)
            btnProfileMusic.setTextColor(colorTextSecondary)
            tvProfileDescription.text = "Low Latency Mode: 30ms pre-roll, 50ms target clamp, and Android Fast Track. Optimized for TikTok, video lip-sync, and instant reaction audio. Controlled by server."
        } else {
            btnProfileMusic.backgroundTintList = ColorStateList.valueOf(colorPrimary)
            btnProfileMusic.setTextColor(Color.WHITE)
            btnProfileLowLatency.backgroundTintList = ColorStateList.valueOf(colorCard)
            btnProfileLowLatency.setTextColor(colorTextSecondary)
            tvProfileDescription.text = "Music Mode: Studio Master 24-bit / 48 kHz PCM (2.3 Mbps) with 500ms pre-roll cushion and a 2.56s jitter buffer. Crystal-clear, stutter-free playback. Controlled by server."
        }
    }

    private fun updateAccessibilityButton() {
        val isServiceActive = VolumeKeyInterceptorService.isRunning.get()
        if (isServiceActive) {
            btnAccessibilitySettings.text = "Step 2: Accessibility (Active)"
            val colorGreen = ContextCompat.getColor(this, R.color.status_green)
            btnAccessibilitySettings.setTextColor(colorGreen)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorGreen)
            btnAppInfo.text = "Step 1: App Info (Completed)"
            btnAppInfo.setTextColor(colorGreen)
            btnAppInfo.strokeColor = ColorStateList.valueOf(colorGreen)
        } else {
            btnAccessibilitySettings.text = "Step 2: Enable in Accessibility Settings"
            val colorPrimary = ContextCompat.getColor(this, R.color.primary)
            btnAccessibilitySettings.setTextColor(colorPrimary)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorPrimary)
            btnAppInfo.text = "Step 1: Open App Info (Allow Restricted Settings)"
            val colorSecondary = ContextCompat.getColor(this, R.color.text_primary)
            btnAppInfo.setTextColor(colorSecondary)
            btnAppInfo.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card_stroke))
        }
    }

    private fun parseVersion(versionStr: String): List<Int> {
        val clean = versionStr.removePrefix("v").trim()
        return clean.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLen = maxOf(v1.size, v2.size)
        for (i in 0 until maxLen) {
            val p1 = v1.getOrElse(i) { 0 }
            val p2 = v2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        return compareVersions(parseVersion(remoteTag), parseVersion(currentVersion)) > 0
    }

    private fun checkForUpdates() {
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "Checking GitHub for latest release..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var tagName: String? = null
                var apkDownloadUrl: String? = null

                // Attempt fetching releases list to identify the highest semantic release with an APK
                try {
                    val releasesUrl = URL(GITHUB_API_RELEASES)
                    val conn = (releasesUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                    }

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val releasesArray = JSONArray(responseText)

                        var bestTag: String? = null
                        var bestUrl: String? = null
                        var bestVer: List<Int>? = null

                        for (i in 0 until releasesArray.length()) {
                            val rel = releasesArray.getJSONObject(i)
                            if (rel.optBoolean("draft", false)) continue
                            val tag = rel.optString("tag_name", "")
                            if (tag.isEmpty()) continue

                            val assets = rel.optJSONArray("assets") ?: continue
                            var foundApk: String? = null
                            for (j in 0 until assets.length()) {
                                val asset = assets.getJSONObject(j)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    foundApk = asset.optString("browser_download_url")
                                    break
                                }
                            }

                            if (foundApk != null) {
                                val ver = parseVersion(tag)
                                if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                                    bestVer = ver
                                    bestTag = tag
                                    bestUrl = foundApk
                                }
                            }
                        }

                        tagName = bestTag
                        apkDownloadUrl = bestUrl
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Releases list query failed, falling back to latest: ${e.message}")
                }

                // Fallback to /releases/latest if list did not yield an APK
                if (tagName == null || apkDownloadUrl == null) {
                    val latestConn = (URL(GITHUB_API_LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                    }
                    if (latestConn.responseCode == 200) {
                        val responseText = latestConn.inputStream.bufferedReader().use { it.readText() }
                        val releaseJson = JSONObject(responseText)
                        tagName = releaseJson.optString("tag_name", "v1.0.0")
                        val assets = releaseJson.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkDownloadUrl = asset.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                    }
                }

                val finalTag = tagName
                val finalApkUrl = apkDownloadUrl

                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true

                    if (finalTag == null) {
                        updateState = UpdateState.CHECK
                        tvUpdateStatus.text = "No releases found on GitHub."
                        btnCheckUpdate.text = "Check for Updates"
                        return@withContext
                    }

                    latestReleaseTag = finalTag

                    if (finalApkUrl != null) {
                        latestApkUrl = finalApkUrl
                        val targetDir = externalCacheDir ?: cacheDir
                        val versionedApk = File(targetDir, "simple-audio-stream-$finalTag.apk")

                        if (isNewerVersion(finalTag, BuildConfig.VERSION_NAME)) {
                            if (versionedApk.exists() && versionedApk.length() > 500_000) {
                                downloadedApkFile = versionedApk
                                updateState = UpdateState.INSTALL
                                tvUpdateStatus.text = "Update downloaded ($finalTag).\nTap below to install."
                                btnCheckUpdate.text = "Install APK ($finalTag)"
                            } else {
                                downloadedApkFile = null
                                updateState = UpdateState.DOWNLOAD
                                tvUpdateStatus.text = "New release found: $finalTag\nTap below to download and install."
                                btnCheckUpdate.text = "Download Update ($finalTag)"
                            }
                        } else {
                            downloadedApkFile = null
                            updateState = UpdateState.CHECK
                            tvUpdateStatus.text = "You are on the latest version ($finalTag)."
                            btnCheckUpdate.text = "Check for Updates"

                            // Clean up old cached update APKs to free storage
                            try {
                                targetDir.listFiles { _, name ->
                                    name.startsWith("simple-audio-stream") && name.endsWith(".apk")
                                }?.forEach { it.delete() }
                            } catch (ignored: Exception) {}
                        }
                    } else {
                        updateState = UpdateState.CHECK
                        tvUpdateStatus.text = "Latest release $finalTag found, but no APK asset attached."
                        btnCheckUpdate.text = "Check for Updates"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    updateState = UpdateState.CHECK
                    tvUpdateStatus.text = "Failed checking updates: ${e.localizedMessage}"
                    btnCheckUpdate.text = "Check for Updates"
                }
            }
        }
    }

    private fun downloadAndPromptInstall(apkUrl: String, tagName: String) {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "Downloading..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = false
        pbDownload.progress = 0
        tvUpdateStatus.text = "Downloading APK from GitHub ($tagName)..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targetDir = externalCacheDir ?: cacheDir
                val apkFile = File(targetDir, "simple-audio-stream-$tagName.apk")
                if (apkFile.exists()) apkFile.delete()

                var currentUrl = apkUrl
                var redirectCount = 0
                var conn: HttpURLConnection

                // Follow HTTP redirects (GitHub asset downloads redirect to AWS S3)
                while (true) {
                    val url = URL(currentUrl)
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "SimpleAudioStream-Android")

                    val code = conn.responseCode
                    if (code in 301..308) {
                        currentUrl = conn.getHeaderField("Location")
                        redirectCount++
                        if (redirectCount > 5) throw RuntimeException("Too many redirects")
                    } else if (code == 200) {
                        break
                    } else {
                        throw RuntimeException("HTTP $code on download")
                    }
                }

                val totalLength = conn.contentLength
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloadedBytes * 100) / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    pbDownload.progress = progress
                                    tvUpdateStatus.text = "Downloading: $progress% (${downloadedBytes / 1024 / 1024}MB / ${totalLength / 1024 / 1024}MB)"
                                }
                            }
                        }
                    }
                }

                downloadedApkFile = apkFile

                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "Install APK ($tagName)"
                    updateState = UpdateState.INSTALL
                    tvUpdateStatus.text = "Download complete! Opening package installer..."

                    checkPermissionAndInstall(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "Retry Download"
                    updateState = UpdateState.DOWNLOAD
                    tvUpdateStatus.text = "Download failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun checkPermissionAndInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = true
                Toast.makeText(this, "Allow 'Install unknown apps' permission to update", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
                return
            }
        }
        isWaitingForInstallPermission = false
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        try {
            apkFile.setReadable(true, false)
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            val resolveList = packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveList) {
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            Toast.makeText(this, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
