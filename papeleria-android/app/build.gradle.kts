plugins { id("com.android.application") }

android {
    namespace = "mx.edu.uanl.papeleriaauto"
    compileSdk = 35
    defaultConfig {
        applicationId = "mx.edu.uanl.papeleriaauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
