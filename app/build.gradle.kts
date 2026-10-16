import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Versioning
// Release builds receive their version from the git tag:
//   ./gradlew assembleRelease -PappVersionName=1.2.3 -PappVersionCode=1002003
// (see .github/workflows/release.yml). Local builds fall back to 0.1.0.
// ---------------------------------------------------------------------------
fun versionCodeFrom(name: String): Int {
    val core = name.substringBefore('-').split('.')
    val major = core.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = core.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = core.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 1_000_000 + minor * 1_000 + patch
}

val appVersionName: String = (findProperty("appVersionName") as String?)
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: "0.1.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.trim()?.toIntOrNull()
    ?: versionCodeFrom(appVersionName)

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
        buildConfigField("String", "VERIFIED_SERVER_VERSIONS", "\"1.14.0\"")
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
