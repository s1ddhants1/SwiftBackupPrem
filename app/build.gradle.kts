plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
}

import java.util.Properties

run {
    val secretsFile: java.io.File = rootProject.file("keystore.properties")
    if (secretsFile.isFile) {
        val secrets = Properties()
        secretsFile.inputStream().use { stream -> secrets.load(stream) }
        val names: List<String> = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        for (name: String in names) {
            if (!project.hasProperty(name)) {
                val value: String? = secrets.getProperty(name)
                if (!value.isNullOrBlank()) {
                    project.extra.set(name, value)
                }
            }
        }
    }
}

fun getGitCommitHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val hash = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (process.exitValue() == 0 && hash.isNotEmpty()) hash else "unknown"
} catch (_: Throwable) {
    "unknown"
}

val appVersionCode = 310
fun getVersionChannel(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val branch = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (process.exitValue() != 0 || branch.isEmpty() || branch == "HEAD") {
        ""
    } else if (branch == "main" || branch == "master") {
        ""
    } else {
        "-" + branch.replace(Regex("[^A-Za-z0-9._-]"), "-")
    }
} catch (_: Throwable) {
    ""
}
fun getCommitCount(): String = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val count = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (process.exitValue() == 0 && count.isNotEmpty()) count else "0"
} catch (_: Throwable) {
    "0"
}

val channel = getVersionChannel()
val gitHash = getGitCommitHash()
val appVersionName = if (channel == "-testing") "testing${getCommitCount()}-$gitHash" else "3.1.0$channel"

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "io.github.s1ddhants1.swiftbackupprem"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.s1ddhants1.swiftbackupprem"
        minSdk = 27
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = false
        aidl = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("SwiftBackupPrem_${appVersionName}-${variant.name}.apk")
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.preference)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.libxposed.api)
    testImplementation(libs.libxposed.service)
    testImplementation(libs.bundles.unit.test)
}
