plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.prism.vehicle.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    // AAOS Car API (CarPropertyManager GEAR_SELECTION). 기기에 있는 공유 라이브러리라 APK 에 포함되지 않는다.
    useLibrary("android.car")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":adapters"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.tts)
}
