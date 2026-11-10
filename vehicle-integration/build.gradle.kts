plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// 인계용 의존성 고정 (계획서 인계 패키지: lock/requirements). 갱신: gradlew.bat test assembleDebug assembleDebugAndroidTest --write-locks
subprojects {
    dependencyLocking { lockAllConfigurations() }
}
