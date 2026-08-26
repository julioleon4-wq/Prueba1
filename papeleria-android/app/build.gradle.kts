plugins { id("com.android.application") }

android {
    namespace = "mx.edu.uanl.papeleriaauto"
    compileSdk = 35
    defaultConfig {
        applicationId = "mx.edu.uanl.papeleriaauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "4.0.0-kiosk-commercial"
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
