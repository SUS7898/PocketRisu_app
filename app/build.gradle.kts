import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Versioning (docs/DESIGN.md §5)
// CI passes the version name; the code is derived from it so every build that can be
// installed over another has a strictly increasing code:
//   release   1.2.3              -> 1*1_000_000 + 2*10_000 + 3*100 + 0   = 1_020_300
//   dev build 1.2.3-dev.7+abc123 -> ... + 7 (commits since the tag, capped at 99) = 1_020_307
// A dev build after 1.2.3 therefore installs over 1.2.3, and 1.2.4 installs over any
// 1.2.3-dev.N. Local builds fall back to 0.1.0-local (code of 0.1.0).
// ---------------------------------------------------------------------------
fun versionCodeFrom(name: String): Int {
    val core = name.substringBefore('-').substringBefore('+').split('.')
    val major = core.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = core.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = core.getOrNull(2)?.toIntOrNull() ?: 0
    val dev = Regex("""-dev\.(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
    return major * 1_000_000 + minor * 10_000 + patch * 100 + dev
}

val appVersionName: String = (findProperty("appVersionName") as String?)
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: "0.1.0-local"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.trim()?.toIntOrNull()
    ?: versionCodeFrom(appVersionName)
logger.lifecycle("[PocketRisuApp] version $appVersionName (code $appVersionCode)")

// ---------------------------------------------------------------------------
// Signing
// The release keystore is read from keystore.properties (git-ignored, local use)
// or from environment variables (CI: KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD).
// When neither is present the release build falls back to the debug key so the
// project still builds; such an APK must never be distributed.
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    // Reader, not InputStream: Properties.load(InputStream) is ISO-8859-1 and mangles the
    // non-ASCII project path on this machine (docs/TROUBLESHOOTING.md T-3).
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? = signingValue("storeFile", "KEYSTORE_FILE")?.let { path ->
    val f = File(path)
    if (f.isAbsolute) f else rootProject.file(path)
}
val hasReleaseKeystore: Boolean = releaseStoreFile?.exists() == true

if (!hasReleaseKeystore) {
    logger.warn("[PocketRisuApp] No release keystore configured - release builds will be signed with the DEBUG key.")
}

// ---------------------------------------------------------------------------
// Bundled PocketRisu UI (docs/DESIGN.md §4). scripts/build-frontend.sh writes
// app/src/main/assets/bundle/BUNDLE.json; without it the app still builds and
// simply never enters bundled mode (BUNDLED_UPSTREAM_VERSION is empty).
// ---------------------------------------------------------------------------
val bundleManifest = file("src/main/assets/bundle/BUNDLE.json")
val bundledUpstreamVersion: String = if (bundleManifest.exists()) {
    Regex("\"upstreamVersion\"\\s*:\\s*\"([^\"]+)\"").find(bundleManifest.readText())?.groupValues?.get(1) ?: ""
} else ""
if (bundledUpstreamVersion.isEmpty()) {
    logger.warn("[PocketRisuApp] No bundled UI (run scripts/build-frontend.sh) - bundled mode will be unavailable in this build.")
} else {
    logger.lifecycle("[PocketRisuApp] bundled UI upstream v$bundledUpstreamVersion")
}

android {
    namespace = "com.sus7898.pocketrisu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sus7898.pocketrisu"
        // Galaxy Tab S7+ ends at Android 13 (API 33).
        minSdk = 33
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // GitHub repository that hosts the releases used by the in-app updater.
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"${findProperty("updateRepoOwner") ?: "SUS7898"}\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"${findProperty("updateRepoName") ?: "PocketRisu_app"}\"")
        // PocketRisu server versions this app build was verified against (diagnostics banner only).
        buildConfigField("String", "VERIFIED_SERVER_VERSIONS", "\"1.14.0, 1.15.0, 1.15.1, 1.15.2\"")
        // Upstream version of the web UI bundled in this APK ("" = none; bundled mode off).
        buildConfigField("String", "BUNDLED_UPSTREAM_VERSION", "\"$bundledUpstreamVersion\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    // Do not embed the Google-Play-only encrypted dependency metadata block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        sarifReport = true
        htmlReport = true
        xmlReport = false
        textReport = true
        textOutput = File("stdout")
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Dependency locking lets the release workflow write app/gradle.lockfile so that Trivy can
// scan the exact dependency graph that ships in the APK. Only release classpaths are
// resolved on purpose (build tooling never reaches the device).
dependencyLocking {
    lockAllConfigurations()
}

tasks.register("lockReleaseDependencies") {
    description = "Resolves the release classpaths; run with --write-locks to produce gradle.lockfile for vulnerability scanning."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "$path must be run with --write-locks" }
    }
    doLast {
        val shipped = setOf("releaseRuntimeClasspath", "releaseCompileClasspath")
        configurations.filter { it.name in shipped }.forEach { cfg ->
            cfg.resolve()
            logger.lifecycle("lockReleaseDependencies: resolved ${cfg.name} (${cfg.resolvedConfiguration.resolvedArtifacts.size} artifacts)")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
