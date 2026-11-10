plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
}
