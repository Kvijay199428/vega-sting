import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// ---------------------------------------------------------------------------
// Centralised build metadata
//
// version.properties is the single source of truth for the Android version
// code/name and the channel defaults. The release scripts (release.bat /
// debug.bat) pass the exact values for a build through -PvegaVersionName and
// -PvegaVersionCode so that a failed build never burns a version number; the
// properties file is only advanced once the APK has been built and verified.
// ---------------------------------------------------------------------------
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

fun versionProp(key: String, default: String): String =
    versionProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun buildProp(key: String): String? =
    (project.findProperty(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

val nextVersionCode = versionProp("next.versionCode", "1").toIntOrNull() ?: 1
val minSupportedVersionCode = versionProp("minSupported.versionCode", "1").toIntOrNull() ?: 1

// Version names. The values in version.properties describe the LAST released
// build on each channel, but versionCode is taken from next.versionCode (the
// code reserved for the NEXT release). A build must therefore report the NEXT
// release's *name* too, or the two halves of the pair come from different
// points in the version timeline. Deriving both from one "next" computation
// makes a locally built APK report exactly the pair the following release will
// carry, so the in-app About screen always agrees with the published
// update.json.
fun bumpPatch(version: String): String {
    val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return "$major.$minor.${patch + 1}"
}

val nextStableVersionName = bumpPatch(versionProp("release.baseVersion", "1.0.0"))
val nextDevVersionName = run {
    val base = versionProp("debug.baseVersion", "1.0.0")
    val build = (versionProp("debug.buildNumber", "0").toIntOrNull() ?: 0) + 1
    "$base-dev.$build"
}

// Values injected by the release scripts for the build currently in progress.
val overrideVersionName = buildProp("vegaVersionName")
val overrideVersionCode = buildProp("vegaVersionCode")?.toIntOrNull()

/**
 * True for a build that did NOT go through release.bat (no -P overrides). Such
 * a build keeps the Android debug keystore and must never be published, so the
 * About screen labels it explicitly.
 */
val isLocalBuild = overrideVersionName == null

// ---------------------------------------------------------------------------
// Signing configuration (unchanged policy)
//
// The production keystore is required for the release build type only, and the
// credentials stay outside the repository (~/.gradle/gradle.properties). Both
// publishable channels (stableRelease / devRelease) are release builds, so a
// DEBUG-channel APK is signed with the SAME certificate as the STABLE-channel
// APK. That is what makes an in-place channel switch possible on Android.
// Local *Debug variants keep the Android debug keystore and are never published.
// ---------------------------------------------------------------------------
val releaseStorePath = providers.gradleProperty("VEGA_STING_STORE_FILE").orNull
val releaseStorePass = providers.gradleProperty("VEGA_STING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("VEGA_STING_KEY_ALIAS").orNull
val releaseKeyPass = providers.gradleProperty("VEGA_STING_KEY_PASSWORD").orNull

val releaseSigningConfigured = listOf(
    releaseStorePath, releaseStorePass, releaseKeyAlias, releaseKeyPass
).all { !it.isNullOrBlank() }

android {
    namespace = "com.vega.sting"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vega.sting"
        minSdk = 26
        targetSdk = 35
        versionCode = overrideVersionCode ?: nextVersionCode
        versionName = overrideVersionName ?: nextStableVersionName

        buildConfigField("boolean", "IS_LOCAL_BUILD", isLocalBuild.toString())
        buildConfigField("int", "MIN_SUPPORTED_VERSION_CODE", minSupportedVersionCode.toString())
        buildConfigField("String", "GITHUB_OWNER", "\"Kvijay199428\"")
        buildConfigField("String", "GITHUB_REPO", "\"VEGA-STING\"")
        buildConfigField("String", "GITHUB_REPO_URL", "\"https://github.com/Kvijay199428/VEGA-STING\"")
        buildConfigField("String", "DEV_NAME", "\"VIJAYKRSHA.ONLINE\"")
        buildConfigField("String", "DEV_WEBSITE", "\"https://app.vijaykrsha.online\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two publishable channels share one package id and one signing certificate.
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            versionName = overrideVersionName ?: nextStableVersionName
            buildConfigField("String", "DEFAULT_UPDATE_CHANNEL", "\"STABLE\"")
            manifestPlaceholders["defaultUpdateChannel"] = "STABLE"
        }
        create("dev") {
            dimension = "channel"
            versionName = overrideVersionName ?: nextDevVersionName
            buildConfigField("String", "DEFAULT_UPDATE_CHANNEL", "\"DEBUG\"")
            manifestPlaceholders["defaultUpdateChannel"] = "DEBUG"
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Published APK naming:
    //   stableRelease -> vega-sting-stable-<versionName>.apk
    //   devRelease    -> vega-sting-debug-<versionName>.apk
    // Local debug variants keep their default names so they can never be
    // mistaken for a publishable artifact.
    applicationVariants.all {
        if (buildType.name == "release") {
            val channelTag = if (flavorName == "dev") "debug" else "stable"
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "vega-sting-$channelTag-${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.0.0")

    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-video:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")

    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:${room_version}")
    implementation("androidx.room:room-ktx:${room_version}")
    kapt("androidx.room:room-compiler:${room_version}")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.sqlite:sqlite-framework:2.4.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails if release signing credentials are missing."
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                """
                |Release signing is NOT configured - refusing to build an unsigned APK.
                |
                |Add the following to ~/.gradle/gradle.properties (NOT to this repository):
                |  VEGA_STING_STORE_FILE=<path to vega-sting.keystore>
                |  VEGA_STING_STORE_PASSWORD=<password>
                |  VEGA_STING_KEY_ALIAS=vega-sting
                |  VEGA_STING_KEY_PASSWORD=<password>
                |
                |See README.md -> "Building a signed release" for details.
                """.trimMargin()
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}
