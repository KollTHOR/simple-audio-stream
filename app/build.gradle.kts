import java.util.Properties
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.audiostreamer"
    compileSdk = 35

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties().apply {
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }

    fun findProp(vararg names: String): String? {
        for (name in names) {
            val envVal = System.getenv(name)
            if (!envVal.isNullOrBlank()) return envVal
            val ksVal = keystoreProps.getProperty(name)
            if (!ksVal.isNullOrBlank()) return ksVal
            val locVal = localProps.getProperty(name)
            if (!locVal.isNullOrBlank()) return locVal
            val projVal = project.findProperty(name) as? String
            if (!projVal.isNullOrBlank()) return projVal
        }
        return null
    }

    val baseVersionCode = 118
    val baseVersionName = "1.8.15"

    val channelProp = findProp("BUILD_CHANNEL", "RELEASE_CHANNEL", "channel") ?: "stable"
    val buildChannel = channelProp.lowercase()

    fun resolveGitCommit(): String {
        val envSha = System.getenv("GITHUB_SHA") ?: System.getenv("GIT_COMMIT_SHA")
        if (!envSha.isNullOrBlank()) return envSha.take(7)
        return try {
            val stdout = ByteArrayOutputStream()
            exec {
                commandLine("git", "rev-parse", "--short=7", "HEAD")
                standardOutput = stdout
                isIgnoreExitValue = true
            }
            val trimmed = stdout.toString().trim()
            if (trimmed.isNotEmpty()) trimmed else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    val gitCommitSha = resolveGitCommit()

    fun resolveVersionCode(): Int {
        val envCode = findProp("VERSION_CODE", "versionCode")?.toIntOrNull()
        if (envCode != null) return envCode
        return try {
            val stdout = ByteArrayOutputStream()
            exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                standardOutput = stdout
                isIgnoreExitValue = true
            }
            val count = stdout.toString().trim().toIntOrNull()
            if (count != null && count >= baseVersionCode) count else baseVersionCode
        } catch (e: Exception) {
            baseVersionCode
        }
    }
    val finalVersionCode = resolveVersionCode()

    val buildTimestamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    val envVersionName = findProp("VERSION_NAME", "versionName")
    val finalVersionName = if (!envVersionName.isNullOrBlank()) {
        envVersionName
    } else if (buildChannel == "nightly") {
        "$baseVersionName-nightly.$buildTimestamp+$gitCommitSha"
    } else {
        baseVersionName
    }

    defaultConfig {
        applicationId = "com.example.audiostreamer"
        minSdk = 29
        targetSdk = 35
        versionCode = finalVersionCode
        versionName = finalVersionName

        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$gitCommitSha\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "BASE_VERSION_NAME", "\"$baseVersionName\"")
    }

    val storeFilePath = findProp("RELEASE_KEYSTORE_PATH", "KEYSTORE_PATH", "releaseKeystorePath")
    val storePassword = findProp("RELEASE_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD", "releaseKeystorePassword")
    val keyAlias = findProp("RELEASE_KEY_ALIAS", "KEY_ALIAS", "releaseKeyAlias")
    val keyPassword = findProp("RELEASE_KEY_PASSWORD", "KEY_PASSWORD", "releaseKeyPassword")

    val resolvedKeystoreFile = storeFilePath?.let {
        val f = file(it)
        if (f.exists()) f else rootProject.file(it).takeIf { rf -> rf.exists() }
    }

    val hasReleaseSigning = resolvedKeystoreFile != null &&
        !storePassword.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() &&
        !keyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                this.storeFile = resolvedKeystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.concentus)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
