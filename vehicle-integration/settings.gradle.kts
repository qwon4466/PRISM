pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos 공식 SDK 저장소 (pleos-sample-vehicle-app README 기준)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "prism-vehicle-integration"

include(":replay", ":adapters", ":sdk", ":app", ":pipeline")

// 04 visualization-engine: 팀 저장소의 형제 폴더를 소스 그대로 모듈로 포함 (04 HANDOFF_INTEGRATION.md 1절). 04 파일은 고치지 않는다.
include(":visualization-engine")
project(":visualization-engine").projectDir = file("../visualization-engine")
