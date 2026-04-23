android {
    namespace = "com.google.ai.edge.gallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.google.ai.edge.gallery"
        minSdk = 29
        targetSdk = 35 // BUMPED UP - Native execution bypass is dead.
        versionCode = 1
        versionName = "1.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MAKE SURE TO REMOVE any externalNativeBuild { cmake { ... } } blocks 
    // that were previously pointing to libproot or libbash compilation.
}
