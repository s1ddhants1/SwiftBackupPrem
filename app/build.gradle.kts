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

val appVersionCode = 320
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
val appVersionName = if (channel == "-testing") "testing${getCommitCount()}-$gitHash" else "3.2.0$channel"

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

    androidResources {
        localeFilters += listOf("en")
    }

    flavorDimensions += "framework"
    productFlavors {
        create("normal") {
            dimension = "framework"
        }
        create("legacy") {
            dimension = "framework"
        }
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
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += listOf(
            "DiscouragedApi",
            "DiscouragedPrivateApi",
            "PrivateApi",
            "SetWorldReadable",
            "SetWorldWritable",
            "SdCardPath",
            "QueryPermissionsNeeded",
            "UseKtx"
        )
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/**/LICENSE*",
                "/META-INF/**/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/DebugProbesKt.bin",
                "**/*.kotlin_builtins"
            )
        }
    }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val flavorName = variant.flavorName.orEmpty()
            val suffix = if (flavorName == "legacy") "-legacy" else ""
            output.outputFileName.set("SwiftBackupPrem_${appVersionName}${suffix}-${variant.buildType}.apk")
        }
    }
}

val copyReleaseApks = tasks.register<Copy>("copyReleaseApks") {
    mustRunAfter(tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") })
    mustRunAfter(tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") })
    from(
        layout.buildDirectory.dir("outputs/apk/normal/release"),
        layout.buildDirectory.dir("outputs/apk/legacy/release")
    )
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk/release"))
}

val copyDebugApks = tasks.register<Copy>("copyDebugApks") {
    mustRunAfter(tasks.matching { it.name.startsWith("package") && it.name.endsWith("Debug") })
    mustRunAfter(tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Debug") })
    from(
        layout.buildDirectory.dir("outputs/apk/normal/debug"),
        layout.buildDirectory.dir("outputs/apk/legacy/debug")
    )
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk/debug"))
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        setDependsOn(listOf("assembleNormalRelease"))
    }
    tasks.named("assembleDebug").configure {
        setDependsOn(listOf("assembleNormalDebug"))
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApks)
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApks)
}

tasks.matching { it.name == "assembleLegacyRelease" }.configureEach {
    finalizedBy(copyReleaseApks)
}

tasks.matching { it.name == "assembleLegacyDebug" }.configureEach {
    finalizedBy(copyDebugApks)
}

tasks.register("assembleAll") {
    group = "build"
    description = "Assembles all variants across all flavors."
    dependsOn("assembleNormalDebug", "assembleNormalRelease", "assembleLegacyDebug", "assembleLegacyRelease")
    finalizedBy(copyReleaseApks, copyDebugApks)
}

tasks.register("assembleAllDebug") {
    group = "build"
    description = "Assembles all debug variants across all flavors."
    dependsOn("assembleNormalDebug", "assembleLegacyDebug")
    finalizedBy(copyDebugApks)
}

tasks.register("assembleAllRelease") {
    group = "build"
    description = "Assembles all release variants across all flavors."
    dependsOn("assembleNormalRelease", "assembleLegacyRelease")
    finalizedBy(copyReleaseApks)
}

tasks.register("installDebug") {
    group = "install"
    description = "Installs the Debug build of the default variant."
    dependsOn("installNormalDebug")
}

tasks.register("installRelease") {
    group = "install"
    description = "Installs the Release build of the default variant."
    dependsOn("installNormalRelease")
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs unit tests for the default variant."
    dependsOn("testNormalDebugUnitTest")
}

tasks.register("testReleaseUnitTest") {
    group = "verification"
    description = "Runs unit tests for the default release variant."
    dependsOn("testNormalReleaseUnitTest")
}

tasks.register("lintDebug") {
    group = "verification"
    description = "Runs lint for the default variant."
    dependsOn("lintNormalDebug")
}

tasks.register("lintRelease") {
    group = "verification"
    description = "Runs lint for the default release variant."
    dependsOn("lintNormalRelease")
}



dependencies {
    "normalCompileOnly"(libs.libxposed.api)
    "normalImplementation"(libs.libxposed.service)

    "legacyCompileOnly"(files("libs/api-82.jar"))

    compileOnly(libs.androidx.preference)
    implementation(libs.dexkit)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.bundles.unit.test)
}
