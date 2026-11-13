plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}

tasks.register<JavaExec>("evalPrediction") {
    group = "verification"
    description = "Evaluate synthetic prediction candidates against simulated future positions."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.prism.vehicle.replay.tools.PredictionEvaluator")
    workingDir = rootDir
}

tasks.register<JavaExec>("generateMockScenarios") {
    group = "verification"
    description = "Regenerate deterministic integration mock scenarios and parser fixtures."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.prism.vehicle.replay.tools.MockScenarioGenerator")
    workingDir = rootDir
    providers.gradleProperty("mockOutputDir").orNull?.let { args(it) }
    providers.gradleProperty("mockFixturesDir").orNull?.let { args(it) }
}
