# INTEGRATION_STATUS — PART 05 vehicle-integration

기준: 2026-11-10, 브랜치 `feature/vehicle`. 상태 표기는 지시서 기준(DESIGNED / IMPLEMENTED / TESTED / INTEGRATED / VEHICLE-VERIFIED).

## 엔진 간 통합 결과 (2026-11-12)

공유 저장소 main(9274fab)의 엔진을 소스 수정 없이 연결했다.

| 엔진 | 버전(마지막 커밋) | 자체 빌드·테스트 | 연결 방식 |
|---|---|---|---|
| 03 simulation-engine | a6d177c | `gradlew test` 20/20 PASS | `samples/*.jsonl` + `scenario_manifest.json` 을 `pipeline/` 이 읽어 앱 assets 로 복사 |
| 01 prediction-engine | 22027e5 | `testDebugUnitTest` 20/20 PASS, AAR 빌드 | 자체 빌드한 AAR 의 classes.jar 를 `libs/prism-prediction-engine.jar` 로 고정, `pipeline/` 이 시점마다 `PredictionEngine.predict(과거 5초)` 호출 → `prediction_frames.jsonl` (7종 × 121프레임 = 847, 실패 0) |
| 04 visualization-engine | 46ed5ff | 이 빌드 안에서 단위 테스트 45/45 PASS (05 시나리오를 읽는 호환성 테스트 포함) | `settings.gradle.kts` 에서 `../visualization-engine` 을 모듈로 포함, 앱 레이더를 04 `RadarScreen` 으로 교체 (05 의 PlaceholderRadar 삭제) |
| 02 risk (engine02_preintegration) | d03f3e8 | (Python 연구 스냅샷) | **연결 불가**: RiskFrame 을 만드는 엔진이 아니다(문서: 확정 RiskFrame v1·앱 코드를 바꾸지 않는 연구용). 실제 엔진 시나리오는 위험 정보 없이 재생되고 화면에 "위험 정보 없음 (RiskFrame 없음)" 으로 표시 |

04 를 붙이기 위해 05 빌드 도구를 04 와 같게 올렸다: Gradle 9.6.0, AGP 9.5.1(내장 Kotlin), Kotlin 2.2.10, Compose BOM 2026.02.01, 앱 compileSdk 37.

### 계약 불일치 (01 담당자 확인 필요, 05 는 01 코드를 고치지 않고 어댑터에서 처리)

1. **01 입력이 계약 v1 의 `schema_version` 을 거부한다.** 01 `VehicleFrame` 모델에 `schema_version`·`coordinate_system` 이 없고 `ContractJson` 이 `ignoreUnknownKeys = false` 라, 계약대로 `schema_version` 을 넣은 03 출력의 모든 줄이 `Encountered an unknown key 'schema_version'` 으로 실패한다(01 예제 입력에도 `schema_version` 이 없다). → 05 `pipeline/` 은 05 파서로 읽은 뒤 01 객체로 옮겨 `predict()` 에 직접 넘긴다.
2. **01 출력에 `schema_version` 이 빠진다.** `PredictionFrame.schemaVersion` 기본값이 "1.0" 인데 `encodePredictionFrame` 이 `encodeDefaults = false` 라 출력 JSON 에서 사라진다(계약 필수 필드). 05 검증기가 847 줄 모두 거부했다. → 05 는 01 객체 값을 그대로 두고 `encodeDefaults = true` 로 직렬화한다.
3. 03 의 묶음 manifest(시나리오 7개를 한 파일, `event_annotation` 의 `note`·`vehicle_id`)는 05 시나리오별 manifest(`t_ms`, `label`)로 `pipeline/` 이 변환한다.

01 예측 값 점검: 1초 뒤 예측 이동량이 각 차량 속도와 같고(Δy ≈ vy), 실제 1초 뒤 위치와 약 1m 이내로 맞는다(chain_reaction·normal 표본).

## 1. 빌드

| 항목 | 결과 |
|---|---|
| `gradlew test` (JVM 단위·통합) | **PASS** 38/38 (05) + 45/45 (04 visualization-engine, 같은 빌드) |
| Compose UI 테스트 (`connectedDebugAndroidTest`, 7개) | 휴대폰 AVD `Medium_Phone_API_36.0` (Android 16): **PASS 7/7**. Pleos 에뮬레이터: 아래 4절 |
| `gradlew assembleDebug` | **성공** |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` (약 47MB) |
| 에뮬레이터 설치 | **성공** (`adb install -r`, Pleos_Connect_v2.1.0) |
| 에뮬레이터 실행·화면 동작 | **성공** — 스크린샷 `tests/reports/screenshots/` |
| Release APK | **미생성**. 서명 키를 저장소에 두지 않으므로 서명 없는 release 는 설치 불가. 제출·시연은 Debug APK 사용, Release 는 팀장이 서명 키로 별도 빌드 |

빌드 환경: Windows 11, JDK 17 (`ms-17.0.17`), Gradle 9.6.0, AGP 9.5.1(내장 Kotlin), Kotlin 2.2.10, 앱 compileSdk 37, minSdk 28, targetSdk 34. 저장소 위치 `C:\dev\prism` (영문 경로, 클라우드 동기화 폴더 밖).

## 2. 실제 실행한 Android 환경

- AVD `Pleos_Connect_v2.1.0` — Android 14 (API 34), `pleos_car_emulator` x86_64, 2560×1440, user 10
- AVD `Medium_Phone_API_36.0` — Android 16, 1080×2400 세로 (Simulation Mode UI 테스트·좁은 화면 레이아웃 확인용)
- 실차: 미실행

## 3. 기능별 상태

| 기능 | 상태 | 근거 |
|---|---|---|
| 앱 실행 | TESTED | 에뮬레이터 실행, `01_select_simulation.png` |
| 시뮬레이션 선택 | TESTED | 시나리오 6종 목록, 선택 시 로드 |
| 차량 데이터 로딩 (JSON/JSONL) | TESTED | `AssetScenarioIntegrationTest`, `ContractParserTest` |
| 시뮬레이션 재생 | TESTED | `03_drive_chain_reaction_watch.png` (5.2s, WATCH) |
| 위험도 표시 | TESTED | NORMAL 회색 / WATCH 노랑 / WARNING 빨강 / UNKNOWN 점선+`?`, 텍스트 라벨 병기 |
| 정차 후 리플레이 (seek, 0.5·1·2배) | TESTED | `04_replay_seek_5_5s.png`, `ReplayClockTest`. 정차 전에는 버튼 비활성 확인 |
| 오류 상태 표시 | TESTED | `FixtureErrorHandlingTest` 12개 + UI 테스트 `unknownScenarioShowsErrorScreen` |
| 좁은 화면(분할·휴대폰) 레이아웃 | TESTED | 폭이 부족하면 위아래 배치. `05_phone_portrait_select.png` |
| 단일 JSON 결과 파일 | TESTED | `singleJsonFilesAreAccepted` |
| Simulation Mode | TESTED | SDK 미호출 확인 (상태 UNTESTED 유지) |
| Pleos Context Mode | TESTED (에뮬레이터) | 기어(AAOS `GEAR_SELECTION`)·브레이크 페달(Pleos Vehicle SDK, capability 확인 후 읽기)·위치·내비·TTS CONFIRMED (`SDK_COMPATIBILITY.md`) |
| ADAS Mode | 비활성 (설계대로) | 공개 SDK 없음 |
| 라이프사이클 리소스 해제 | TESTED | 백그라운드 전환 시 logcat `released all Pleos SDK sessions` |
| 앱 재시작 | TESTED | `am force-stop` 후 재실행, Pleos 세션 재연결 확인 |
| 04 UI 라이브러리 연결 | INTEGRATED | 04 `RadarScreen` 사용 (주행=DRIVING, 리플레이=DETAIL). `07_integrated_drive_engine04_radar.png`, `08_integrated_replay_engine01_trajectories.png` |
| 03→01 결과 재생 | INTEGRATED | 03 시나리오 7종 + 01 예측. `06_integrated_scenario_list.png` |
| 02 위험 결과 | BLOCKED | RiskFrame 생성 엔진 없음 |
| 실차 | 미진행 | VEHICLE-VERIFIED 아님 |

## 4. 통합 테스트 PASS/FAIL (지시문 7항)

| 검증 항목 | 테스트 | 결과 |
|---|---|---|
| JSON 파싱 | `ContractParserTest.parsesContractExamples`, `reportsLineNumberOfBrokenLine`, `missingRequiredFieldIsError` | PASS |
| 모든 Vehicle ID 연결 | `AssetScenarioIntegrationTest.everyScenarioParsesAndAllIdsLink`, `FixtureErrorHandlingTest.unknownVehicleRefFails` | PASS |
| 좌표계 일치 | `AssetScenarioIntegrationTest.egoIsOriginFacingForwardInEveryFrame`, `ScenarioTimelineTest.nonFiniteNumberIsError` | PASS |
| 시간 동기화 | `predictionAndRiskShareVehicleTimestamps`, `stalePredictionIsDroppedNotMixed`, `replayWalksWholeScenarioMonotonically` | PASS |
| 위험 등급 표시 | `riskScenariosActuallyRaiseRisk`, `unknownLevelOutranksNormalButNotWatch`, 에뮬레이터 화면 | PASS |
| 시나리오 전환 | 에뮬레이터에서 목록 → 시나리오 → 목록 | PASS (수동) |
| 데이터 누락 | `dropoutScenarioHasStaleWindowAndUnknown`, `gapIsPlayableButStale`, `missingVehicleFileFails`, `missingPredictionFileStillPlaysWithWarning` | PASS |
| 앱 재시작 | 에뮬레이터 force-stop → 재실행 | PASS (수동) |
| 리플레이 재생 | `ReplayClockTest` 4개, 에뮬레이터 seek | PASS |
| SDK 연결 실패 처리 | 권한 없는 상태에서 4개 SDK `SecurityException` → FAILED 표시, 앱 crash 없음 | PASS (수동) |
| 라이프사이클 리소스 해제 | onStop → `stop()` 로그 | PASS (수동) |

FAIL: 없음 (JVM). UI 테스트 결과는 아래.

### Compose UI 테스트 (`app/src/androidTest/.../PrismAppUiTest.kt`)

| 테스트 | 휴대폰 AVD (Android 16) |
|---|---|
| listsAllScenarios | PASS |
| playAdvancesTimeAndPauses | PASS |
| riskLevelChangesDuringPlayback | PASS |
| replayOnlyAfterParking | PASS |
| dropoutShowsMissingData | PASS |
| unknownScenarioShowsErrorScreen | PASS |
| adasModeIsDisabled | PASS |

Pleos 에뮬레이터 (`am instrument --user 10`): **4~5/7 PASS** (2026-11-10 5/7, 2026-11-11 Mock·기어 변경 후 4/7). 실패한 테스트는 모두 `No compose hierarchies found` — 차량 홈(`ai.umos.homescreen`)의 TaskView 가 테스트가 띄운 Activity 를 숨겨 화면이 없는 상태. 같은 테스트가 휴대폰 AVD 에서 통과하므로 앱 로직 문제는 아닌 것으로 판단. 실패 테스트는 실행할 때마다 바뀐다(불안정).
- Gradle `connectedDebugAndroidTest` 는 Pleos 에뮬레이터에서 진행되지 않음(테스트를 user 0 에 띄움). Pleos 에서는 APK 2개 설치 후 `adb shell am instrument -w -r --user 10 com.prism.vehicle.app.test/androidx.test.runner.AndroidJUnitRunner` 로 실행.

UI 테스트로 찾아 고친 앱 버그 2건:
1. 레이더 라벨 crash — 표시 범위(-20~60m) 밖 차량의 라벨 좌표에서 `drawText` 가 음수 높이 제약을 만들어 `IllegalArgumentException` 으로 앱 종료 (`sudden_brake`·`dropout`·`rear_approach` 재생 시). 범위 밖 라벨은 그리지 않도록 수정.
2. 좁은 창 레이아웃 — 우측 패널 고정 폭(520~640dp)이 창보다 넓으면 시나리오 목록·레이더 폭이 0. 폭이 패널의 2배 미만이면 위아래 배치로 수정.

## 5. 통합된 엔진 버전

| 엔진 | 버전 | 비고 |
|---|---|---|
| 01 prediction | **통합** 22027e5 | `pipeline/` 로 시점별 예측 생성 (Mock 시나리오는 `mock_*` 로 별도 유지) |
| 02 risk | **미통합** d03f3e8 | RiskFrame 생성기 없음. `mock_*` 시나리오만 사람이 정한 구간 등급 |
| 03 simulation | **통합** a6d177c | 시나리오 7종 (normal, sudden_braking, rear_approach, lane_change, merge, chain_reaction, sensor_dropout) |
| 04 visualization | **통합** 46ed5ff | 모듈 포함, `RadarScreen` |
| 05 vehicle-integration | `feature/vehicle` 최신 커밋 (태그 미생성) | 이 문서 |

## 6. 오픈소스 조사 (실험 폴더 `현대 공모전/opensource-lab/`, PRISM 저장소 밖)

| 저장소 | commit | 라이선스 | 원본 실행 결과 | 판정 | 가져온 방식과 이유 |
|---|---|---|---|---|---|
| Pleos-Playground/pleos-sample-vehicle-app | 959981d | **없음** | 그대로는 빌드 실패: `Could not find ai.pleos.playground:Vehicle:2.3.1` (Maven 에 2.0.3/2.3.2 만 존재). 실험 폴더에서 2.3.2 로 고치면 `assembleDebug` 성공. 단위 테스트 소스 없음(NO-SOURCE) | reference only | 라이선스가 없어 코드 복사 불가 → SDK 는 Maven 패키지로 설치, 수명주기만 참고해 자체 어댑터 작성 |
| androidx/androidx | 73a8b7b | Apache-2.0 | 전용 빌드 시스템이 필요해 원본 빌드 생략(sparse clone 으로 Compose/Lifecycle 경로만 확인) | adopt (패키지) | Google Maven 공식 배포본이 같은 소스의 릴리스 → 패키지 설치가 가장 안전 |
| Kotlin/kotlinx.serialization | 6f6a2e6 | Apache-2.0 | `:kotlinx-serialization-json:jvmTest` 실패: 빌드가 `IBM_SEMERU` JDK 툴체인을 요구, 이 PC 에 없음 | adopt (패키지) | Maven Central 1.7.3 (Kotlin 2.0.21 호환). 원본 빌드 실패는 툴체인 문제로 라이브러리 품질과 무관 |
| android/architecture-samples | ee66e15 | Apache-2.0 | `testDebugUnitTest assembleDebug` 성공, 단위 테스트 46개 PASS | reference only | ViewModel+StateFlow 구조만 참고. 코드 복사 없음 |

submodule / vendoring 은 쓰지 않았다. 네 저장소 모두 필요한 부분이 공식 배포 패키지로 제공되거나(androidx, serialization, Pleos SDK) 참고만 하면 되기 때문이다.

## 7. Git

- 공유 저장소: https://github.com/qwon4466/PRISM — 이 모듈은 `vehicle-integration/` 만 수정. 작업 브랜치 `feature/vehicle` → `main` PR (main 직접 push 없음)
- 개인 백업 저장소: https://github.com/ftre235/prism-android-app (private, 같은 커밋 내용)

## 8. 남은 문제

1. 03/01/02/04 실제 산출물 미도착 → Mock 으로만 통합 검증.
2. `FUSED_LOCATION` 런타임 동의 창이 차량 홈 화면에서 앞에 보이지 않음 → 실차/심사 환경 확인 필요.
3. ADAS 객체 권한(`ADAS_TRACKED_OBJECT`)이 에뮬레이터에 있으나 공개 SDK 없음 → 주최 측 문의 필요.
4. 에뮬레이터 Pleos HAL 서비스 반복 crash (앱과 무관).
5. 공통 `contracts/` JSON Schema 미배포 → 계약 v1 은 지시서 예시 기준. 배포되면 `replay/Contracts.kt` 와 대조.
6. 예측·위험 파일 형식을 JSONL(프레임당 한 줄)로 가정. 01/02 가 단일 JSON 으로 주면 어댑터 추가 필요.
7. Release 서명·GitHub Releases 미진행.
8. Pleos 차량 홈 화면이 테스트용 Activity 창을 숨기거나 터치 주입을 막는 경우가 있어 Pleos 에뮬레이터 UI 테스트는 불안정.
