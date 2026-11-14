import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val nearbyApiOverride = providers.gradleProperty("binteumNearbyApiBaseUrl").orNull?.trim().orEmpty()
fun quotedBuildValue(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
if (nearbyApiOverride.isNotEmpty()) {
    val parsed = URI(nearbyApiOverride)
    require(parsed.scheme == "https" && parsed.host != null && parsed.rawUserInfo == null &&
        parsed.rawQuery == null && parsed.rawFragment == null) {
        "binteumNearbyApiBaseUrl must be an HTTPS server origin or base path without credentials/query/fragment"
    }
}
android {
    namespace = "com.binteum.app"
    compileSdk = 34
    buildToolsVersion = "36.1.0"
    useLibrary("android.car")
    buildFeatures { buildConfig = true }
    // Keep both languages available for offline in-app language switching.
    bundle { language { enableSplit = false } }
    defaultConfig {
        applicationId = "com.binteum.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = providers.gradleProperty("binteumTestRunner")
            .getOrElse("com.binteum.app.ChargeExperienceProbe")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".friend"
            buildConfigField("String", "NEARBY_API_BASE_URL", quotedBuildValue(nearbyApiOverride.ifEmpty { "http://127.0.0.1:3210" }))
        }
        getByName("release") {
            // A production backend is configured explicitly; localhost is never shipped in release.
            buildConfigField("String", "NEARBY_API_BASE_URL", quotedBuildValue(nearbyApiOverride))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("com.google.zxing:core:3.5.3")
}
