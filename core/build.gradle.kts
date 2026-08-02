plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ru9n.sratim.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.retrofit)
    api(libs.retrofit.gson)
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)
}
