plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseCode = System.getenv("APP_VERSION_CODE")?.toInt() ?: 1000
val releaseName = System.getenv("APP_VERSION_NAME") ?: "1.3.$releaseCode-local"
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: providers.gradleProperty("updateRepo").orNull.orEmpty()
require(releaseCode in 1..2100000000)
require(updateRepo.isEmpty() || updateRepo.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")))
android {
    namespace = "com.aicommandcenter.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aicommandcenter.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseCode
        versionName = releaseName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("distribution") {
            val path = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!path.isNullOrEmpty()) {
                storeFile = file(path)
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("distribution") } }
}
dependencies { implementation("androidx.core:core:1.15.0") }
