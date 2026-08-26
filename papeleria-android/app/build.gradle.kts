plugins { id("com.android.application") }

android {
    namespace = "mx.edu.uanl.papeleriaauto"
    compileSdk = 35
    defaultConfig {
        applicationId = "mx.edu.uanl.papeleriaauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "7.0.0-kiosk-qol"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
