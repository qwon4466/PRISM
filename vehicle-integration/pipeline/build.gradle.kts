// 03 simulation-engine 출력 → 01 prediction-engine 실행 → 앱 assets (PDF 09장 통합 순서 3, 6)
// 엔진 소스를 고치지 않고, 03 은 samples/ 파일을, 01 은 자체 빌드로 만든 AAR 의 classes.jar 를 읽기만 한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":replay"))
    implementation(files("../libs/prism-prediction-engine.jar"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}

application { mainClass.set("com.prism.vehicle.pipeline.MainKt") }

fun gitShort(path: String): String = providers.exec {
    commandLine("git", "log", "-1", "--format=%h", "--", path)
    workingDir = rootDir
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf(
        "../simulation-engine/samples",
        "app/src/main/assets/scenarios",
        "03 simulation-engine@${gitShort("../simulation-engine")} samples + 01 prediction-engine@${gitShort("../prediction-engine")} (PredictionEngine.predict, 과거 5초 창). 02 risk-engine 미제출",
        "tests/reports/pipeline-report.txt",
    )
}
