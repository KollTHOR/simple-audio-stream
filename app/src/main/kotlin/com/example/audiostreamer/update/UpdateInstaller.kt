package com.example.audiostreamer.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class UpdateInstaller(private val context: Context) {

    companion object {
        private const val TAG = "UpdateInstaller"
    }

    sealed class PreInstallCheck {
        object Ready : PreInstallCheck()
        object MissingInstallPermission : PreInstallCheck()
        data class DowngradeDetected(
            val targetVersionCode: Long,
            val installedVersionCode: Long,
            val targetVersionName: String
        ) : PreInstallCheck()
    }

    /**
     * Verifies system readiness and safety before launching the package installer.
     */
    fun checkPreInstall(
        targetVersionCode: Long,
        targetVersionName: String,
        installed: BuildInfo = BuildInfo.current()
    ): PreInstallCheck {
        // 1. Check install unknown apps permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                return PreInstallCheck.MissingInstallPermission
            }
        }

        // 2. Check for version downgrade
        if (targetVersionCode > 0 && targetVersionCode < installed.versionCode) {
            return PreInstallCheck.DowngradeDetected(
                targetVersionCode = targetVersionCode,
                installedVersionCode = installed.versionCode,
                targetVersionName = targetVersionName
            )
        }

        return PreInstallCheck.Ready
    }

    /**
     * Creates an Intent to prompt the user to grant REQUEST_INSTALL_PACKAGES.
     */
    fun createPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    /**
     * Launches the Android system package installer for the given APK file.
     */
    fun launchInstallIntent(apkFile: File): Result<Unit> {
        return try {
            apkFile.setReadable(true, false)
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            val resolveList = context.packageManager.queryIntentActivities(
                installIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resolveList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching install intent", e)
            Result.failure(e)
        }
    }
}
