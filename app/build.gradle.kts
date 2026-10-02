plugins {
    id("com.android.application")
}

android {
    namespace = "life.airen.haminn"
    compileSdk = 37

    defaultConfig {
        applicationId = "life.airen.haminn"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("haminnVersionCode").orNull?.toInt() ?: 92
        versionName = providers.gradleProperty("haminnVersionName").orNull ?: "1.12.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val keyStorePath = providers.environmentVariable("HAMINN_KEYSTORE_PATH").orNull
        val storePasswordValue = providers.environmentVariable("HAMINN_STORE_PASSWORD").orNull
        val keyPasswordValue = providers.environmentVariable("HAMINN_KEY_PASSWORD").orNull
        if (keyStorePath != null && storePasswordValue != null && keyPasswordValue != null) {
            create("haminnRelease") {
                storeFile = file(keyStorePath)
                storePassword = storePasswordValue
                keyAlias = providers.environmentVariable("HAMINN_KEY_ALIAS").orNull ?: "haminn-v1"
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "WEBVIEW_DEBUGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "WEBVIEW_DEBUGGING", "false")
            signingConfig = signingConfigs.findByName("haminnRelease")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md"
        )
    }
}

dependencyLocking {
    lockAllConfigurations()
}

val verifyAgentGuidance by tasks.registering {
    val pairs = listOf(
        rootProject.file("docs/webapp-authoring.md") to file("src/main/assets/agent/webapp-authoring.md"),
        rootProject.file("sdk/haminn-api.d.ts") to file("src/main/assets/agent/haminn-api.d.ts"),
    )
    inputs.files(pairs.flatMap { listOf(it.first, it.second) })
    doLast {
        pairs.forEach { (source, packaged) ->
            check(packaged.exists() && source.readBytes().contentEquals(packaged.readBytes())) {
                "Stale phone guidance: run node tools/sync-agent-assets.mjs"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyAgentGuidance) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.2")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
