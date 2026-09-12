import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.audiostreamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.audiostreamer"
        minSdk = 29
        targetSdk = 35
        versionCode = 59
        versionName = "1.8.6"
    }

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

    fun findSigningProp(vararg names: String): String? {
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

    val storeFilePath = findSigningProp("RELEASE_KEYSTORE_PATH", "KEYSTORE_PATH", "releaseKeystorePath")
    val storePassword = findSigningProp("RELEASE_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD", "releaseKeystorePassword")
    val keyAlias = findSigningProp("RELEASE_KEY_ALIAS", "KEY_ALIAS", "releaseKeyAlias")
    val keyPassword = findSigningProp("RELEASE_KEY_PASSWORD", "KEY_PASSWORD", "releaseKeyPassword")

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
    testImplementation(libs.junit)
}
