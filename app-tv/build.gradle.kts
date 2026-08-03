plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ru9n.sratim"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ru9n.sratim"
        minSdk = 28
        targetSdk = 36
        versionCode = project.property("VERSION_CODE").toString().toInt()
        versionName = project.property("VERSION_NAME").toString()
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            isDefault = true
        }

        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }

        release {
            val isCi = System.getenv("GITHUB_ACTIONS") == "true"
            if (isCi) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core-ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
