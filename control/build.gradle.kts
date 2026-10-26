import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi") // JVM screenshot rendering for ControlScreen; no device needed.
}
android {
    namespace = "com.rollwerk.n65i.control"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rollwerk.n65i.control"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.1.2"
        buildConfigField("boolean", "REGIONAL_PROFILE_MIGRATION_ENABLED", "false")
        buildConfigField("String", "BUILD_ID", "\"${Instant.now()}\"")
        // Owner-specific local build input; never committed. Absent on a fresh clone, where the app
        // falls back to the anonymous challenge/response session (SessionAuth).
        val ownerAuthFile = rootProject.file("control/owner-auth.local.txt")
        val ownerAuth = if (ownerAuthFile.exists()) ownerAuthFile.readText().trim() else ""
        require(ownerAuth.isEmpty() || ownerAuth.matches(Regex("[0-9A-F ]+")))
        buildConfigField("String", "OWNER_AUTH", "\"$ownerAuth\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    applicationVariants.all {
        outputs.all { (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "N65i-Control.apk" }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

