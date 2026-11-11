plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// 인계용 의존성 고정 (계획서 인계 패키지: lock/requirements). 갱신: gradlew.bat test assembleDebug assembleDebugAndroidTest --write-locks
// 04 visualization-engine 처럼 이 폴더 밖에서 포함한 모듈에는 lock 파일을 만들지 않는다(남의 폴더를 수정하지 않기 위해).
subprojects {
    if (projectDir.toPath().startsWith(rootDir.toPath())) {
        dependencyLocking { lockAllConfigurations() }
    }
}
