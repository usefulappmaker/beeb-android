plugins { id("com.android.application") }
android {
    namespace = "org.beebplayer.android"
    compileSdk = 37
    defaultConfig { applicationId = "org.beebplayer.android"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "0.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    val keyPath = providers.environmentVariable("BEEB_KEYSTORE_PATH").orNull
    signingConfigs {
        if (keyPath != null) create("localRelease") {
            storeFile = file(keyPath)
            storePassword = providers.environmentVariable("BEEB_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("BEEB_KEY_ALIAS").orElse("beeb").get()
            keyPassword = providers.environmentVariable("BEEB_KEYSTORE_PASSWORD").orNull
        }
    }
    buildTypes { release { isMinifyEnabled = false; if (keyPath != null) signingConfig = signingConfigs.getByName("localRelease") } }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
