plugins { id("com.google.gms.google-services"); id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseSigningNames = listOf("GENOA_KEYSTORE", "GENOA_KEYSTORE_PASSWORD", "GENOA_KEY_ALIAS", "GENOA_KEY_PASSWORD")
val releaseSigning = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull }
require(releaseSigning.values.all { it == null } || releaseSigning.values.all { !it.isNullOrBlank() }) {
    "Provide all four GENOA signing environment variables, or none for an unsigned build."
}
android {
    namespace = "app.gather"
    compileSdk = 36
    defaultConfig { applicationId = "app.genoa"; minSdk = 29; targetSdk = 36; versionCode = 13; versionName = "0.2.9"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    if (releaseSigning.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("production") {
            storeFile = file(releaseSigning.getValue("GENOA_KEYSTORE")!!)
            storePassword = releaseSigning.getValue("GENOA_KEYSTORE_PASSWORD")
            keyAlias = releaseSigning.getValue("GENOA_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("GENOA_KEY_PASSWORD")
        }
    }
    buildTypes { release {
        signingConfig = signingConfigs.findByName("production")
        isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("androidx.camera:camera-video:1.5.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.emoji2:emoji2-emojipicker:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
