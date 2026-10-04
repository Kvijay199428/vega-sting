plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}








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
        versionCode = 5
        versionName = "1.0.4"

        
        
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

    applicationVariants.all {
        if (name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "vega-sting-${versionName}.apk"
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
