plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.kapt") }
android { namespace = "com.example.pixelpackagetracker"; compileSdk = 35
 defaultConfig { applicationId = "com.example.pixelpackagetracker"; minSdk = 26; targetSdk = 35; versionCode = 29; versionName = "1.0.5-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("com.google.android.gms:play-services-auth:21.3.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
 implementation("androidx.datastore:datastore-preferences:1.1.7")
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("androidx.room:room-runtime:2.7.1")
 implementation("androidx.room:room-ktx:2.7.1")
 kapt("androidx.room:room-compiler:2.7.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 // Avoid reflective InputManager access removed in Android 17.
 androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}
