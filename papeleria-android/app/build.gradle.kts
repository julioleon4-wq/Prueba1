plugins { id("com.android.application") }

android {
    namespace = "mx.edu.uanl.papeleriaauto"
    compileSdk = 35
    defaultConfig {
        applicationId = "mx.edu.uanl.papeleriaauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0-standalone"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
