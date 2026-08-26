plugins { id("com.android.application") }

android {
    namespace = "mx.edu.uanl.papeleriaauto"
    compileSdk = 35
    defaultConfig {
        applicationId = "mx.edu.uanl.papeleriaauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "5.0.0-kiosk-printflow"
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
