plugins {
    alias(libs.plugins.android.application)
    // AGP 9 는 Kotlin 을 내장하므로 org.jetbrains.kotlin.android 를 따로 적용하지 않는다
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.prism.vehicle.app"
    compileSdk = 37 // 04 visualization-engine 과 같은 값 (Compose BOM 2026.02 요구)

    defaultConfig {
        applicationId = "com.prism.vehicle.app"
        minSdk = 28
        targetSdk = 34 // Pleos Connect 에뮬레이터 = Android 14 (API 34)
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 서명 키는 저장소에 두지 않는다. 서명 없는 release 는 설치 불가 → 시연·제출은 debug APK 사용.
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":adapters"))
    implementation(project(":sdk"))
    implementation(project(":visualization-engine")) // 04 레이더·리플레이 화면

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    reports.html.required.set(false)
    // AGP still writes an HTML report for Android unit tests; remove that generated JS after the test.
    doLast {
        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
        val report = layout.buildDirectory.dir("reports/tests/$name").get().asFile.canonicalFile.toPath()
        check(report.startsWith(buildRoot)) { "Test report path escaped the app build directory" }
        project.delete(report.toFile())
    }
}
