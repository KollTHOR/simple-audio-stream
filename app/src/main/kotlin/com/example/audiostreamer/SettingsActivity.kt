package com.example.audiostreamer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val GITHUB_REPO_URL = "https://github.com/KollTHOR/simple-audio-stream"
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases/latest"
    }

    private lateinit var btnBack: ImageView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnGithubRepo: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton

    private var latestApkUrl: String? = null
    private var downloadedApkFile: File? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                downloadedApkFile?.let { installApk(it) }
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
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        pbDownload = findViewById(R.id.pb_download)
        btnCheckUpdate = findViewById(R.id.btn_check_update)

        btnBack.setOnClickListener { finish() }

        val currentVersion = BuildConfig.VERSION_NAME
        tvAppVersion.text = "Version $currentVersion (Build ${BuildConfig.VERSION_CODE})"

        btnGithubRepo.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }

        btnCheckUpdate.setOnClickListener {
            if (latestApkUrl != null) {
                downloadAndPromptInstall(latestApkUrl!!)
            } else {
                checkForUpdates()
            }
        }
    }

    private fun checkForUpdates() {
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "Checking GitHub for latest release..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_LATEST_RELEASE)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val releaseJson = JSONObject(responseText)
                    val tagName = releaseJson.optString("tag_name", "v1.0.0")
                    val assets = releaseJson.optJSONArray("assets")

                    var apkDownloadUrl: String? = null
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

                    withContext(Dispatchers.Main) {
                        pbDownload.visibility = View.GONE
                        btnCheckUpdate.isEnabled = true

                        val currentVersionClean = BuildConfig.VERSION_NAME.removePrefix("v").trim()
                        val latestVersionClean = tagName.removePrefix("v").trim()

                        if (apkDownloadUrl != null) {
                            latestApkUrl = apkDownloadUrl
                            if (latestVersionClean != currentVersionClean) {
                                tvUpdateStatus.text = "New release found: $tagName\nTap below to download and install."
                                btnCheckUpdate.text = "Download Update ($tagName)"
                            } else {
                                tvUpdateStatus.text = "You are on the latest release ($tagName).\nTap below to re-download APK if needed."
                                btnCheckUpdate.text = "Download Latest APK"
                            }
                        } else {
                            tvUpdateStatus.text = "Latest release $tagName found, but no APK asset attached."
                        }
                    }
                } else if (responseCode == 404) {
                    withContext(Dispatchers.Main) {
                        pbDownload.visibility = View.GONE
                        btnCheckUpdate.isEnabled = true
                        tvUpdateStatus.text = "No GitHub releases found yet."
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        pbDownload.visibility = View.GONE
                        btnCheckUpdate.isEnabled = true
                        tvUpdateStatus.text = "GitHub API returned status $responseCode."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    tvUpdateStatus.text = "Failed checking updates: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun downloadAndPromptInstall(apkUrl: String) {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "Downloading..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = false
        pbDownload.progress = 0
        tvUpdateStatus.text = "Downloading APK from GitHub..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targetDir = externalCacheDir ?: cacheDir
                val apkFile = File(targetDir, "simple-audio-stream-update.apk")
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
                    btnCheckUpdate.text = "Install APK"
                    tvUpdateStatus.text = "Download complete! Opening package installer..."

                    checkPermissionAndInstall(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "Retry Download"
                    tvUpdateStatus.text = "Download failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun checkPermissionAndInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
                return
            }
        }
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
