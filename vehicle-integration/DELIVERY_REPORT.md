# DELIVERY_REPORT — PART 05 vehicle-integration

- 담당: PART 05 Android/Pleos SDK 통합 (`vehicle-integration/`)
- 공유 저장소: https://github.com/qwon4466/PRISM · 브랜치 `feature/vehicle` → `main` PR #2
- 인계 기준 버전: 태그 `vehicle-integration-v0.1.0`. 공유 저장소에 5개 엔진이 같이 있어 계획서의 `v0.1.0` 앞에 폴더 이름을 붙였다
- 작성일: 2026-11-10

> 이 앱은 실시간 충돌 방지 장치가 아니다. 주변 차량 위험은 미리 생성된 시뮬레이션 결과의 오프라인 재생이며, 차량을 제어하지 않는다.

## 1. 기능별 결과 (DONE / PARTIAL / BLOCKED)

| 기능 | 결과 | 근거 |
|---|---|---|
| 앱 실행·시나리오 선택·재생 | DONE | UI 테스트, 에뮬레이터 화면 |
| 위험도 표시 (NORMAL/WATCH/WARNING/UNKNOWN, 색+텍스트) | DONE | UI 테스트 `riskLevelChangesDuringPlayback` |
| 정차 후 리플레이 (seek, 0.5·1·2배) | DONE | UI 테스트 `replayOnlyAfterParking` |
| 오류 상태 (missing / invalid / stale JSON) | DONE | fixture 11종, UI 테스트 `unknownScenarioShowsErrorScreen` |
| 데이터 경계 4종 (Scenario/Prediction/Risk/VehicleSdk Provider) | DONE | `adapters/` |
| Simulation Mode | DONE | SDK 미호출 |
| Pleos Context Mode — Vehicle 연결·기어·위치·내비·TTS | DONE (에뮬레이터) | `SDK_COMPATIBILITY.md` |
| NaviHelper 상태 이벤트 수신 | PARTIAL | 초기화 성공. 경로 안내 중이 아니라 이벤트 응답은 미수신 |
| ADAS Mode | BLOCKED (의도적 비활성) | 공개 SDK 없음, 공식 권한 미확인 |
| 04 UI 라이브러리 연결 | BLOCKED | 04 산출물 미도착 → `PlaceholderRadar` |
| 01·02·03 실제 결과 통합 | BLOCKED | 산출물 미도착 → Mock 6종 |
| Release APK / GitHub Releases | PARTIAL | Debug APK 만. 서명 키는 저장소에 두지 않음 → 팀장 서명 필요 |
| 실차 | BLOCKED | VEHICLE-VERIFIED 아님 |

## 2. 테스트 (실제 실행 결과)

| 구분 | 환경 | 결과 |
|---|---|---|
| JVM 단위·통합 (`gradlew test`) | Windows, JDK 17 | PASS 35 / FAIL 0 / SKIP 0 |
| Compose UI (`connectedDebugAndroidTest`) | `Medium_Phone_API_36.0` (Android 16) | PASS 7 / FAIL 0 |
| Compose UI (`am instrument --user 10`) | `Pleos_Connect_v2.1.0` (Android 14) | PASS 4~5 / FAIL 2~3 (실행마다 다름) — 차량 홈이 테스트 창을 숨기는 환경 문제 (`INTEGRATION_STATUS.md` 4절) |
| Pleos SDK 실호출 | `Pleos_Connect_v2.1.0` | 수동 확인, `SDK_COMPATIBILITY.md` |

로그: `tests/reports/unit-tests-2026-11-10.txt`, 스크린샷: `tests/reports/screenshots/`

## 3. 최소 재현 명령 (설치 / 데모 / 테스트)

저장소는 영문 경로(예: `C:\dev\prism`)에 clone. JDK 17 필요.

```powershell
cd vehicle-integration; $env:JAVA_HOME="<JDK 17 경로>"; .\gradlew.bat assembleDebug
```
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk; adb shell am start -n com.prism.vehicle.app/.MainActivity
```
```powershell
.\gradlew.bat test
```

## 4. 입력·출력 경로

| 구분 | 경로 |
|---|---|
| 입력 (엔진 결과) | `app/src/main/assets/scenarios/<scenario_id>/{scenario_manifest.json, vehicle_frames.jsonl, prediction_frames.jsonl|.json, risk_frames.jsonl|.json}` + `scenarios/index.json` |
| 출력 (APK) | `app/build/outputs/apk/debug/app-debug.apk` |
| Mock 생성 | `python -I tests/tools/make_mock_scenarios.py` → 위 입력 경로에 덮어씀 |

## 5. 통합 주의사항

1. 예측·위험의 `timestamp_ms` 는 같은 시점 VehicleFrame 과 정확히 같아야 표시된다. 짝 없는 프레임은 버리고 경고로 보고한다.
2. 좌표는 각 프레임의 자차 기준(x 오른쪽+, y 전방+, m). 예측 점·위험 영역은 source timestamp 좌표계 고정.
3. `risk_level` 은 NORMAL/WATCH/WARNING/UNKNOWN 외 값이면 파일 전체를 거부한다. `ttc_s`·`confidence` 는 근거 없으면 null.
4. 공통 `contracts/` JSON Schema 가 아직 없어 지시서 02장 예시로 DTO 를 만들었다(`replay/src/main/kotlin/.../Contracts.kt`). Schema 가 나오면 대조 필요.
5. 04 UI 는 `app/.../ui/Screens.kt` 의 `RadarSlot` 한 곳에서 교체.
6. Mock 은 예측·위험을 계산하지 않는다(실제 미래 위치 복사, 사람이 정한 구간 등급, ttc_s=null). 05번 역할 경계.
7. 권한: Pleos 앱 심사는 승인된 권한만 허용. 현재 선언 `CAR_POWERTRAIN`(AAOS), `FUSED_LOCATION`, `NAVI_ROUTE`, `TTS_SERVICE` — 심사 전 승인 확인.
8. Pleos 에뮬레이터 UI 테스트는 Gradle 이 아니라 `adb shell am instrument -w -r --user 10 com.prism.vehicle.app.test/androidx.test.runner.AndroidJUnitRunner`.

## 6. 의존성 고정

- 버전: `gradle/libs.versions.toml`
- lock: `adapters/`, `replay/`, `sdk/`, `app/` 의 `gradle.lockfile` (`subprojects { dependencyLocking { lockAllConfigurations() } }`). 갱신은 `--write-locks`.
- Pleos SDK: Vehicle 2.3.2, FusedLocation 1.1.3, NaviHelper 3.0.5, TextToSpeech 3.3.1 (nexus-playground.pleos.ai)

## 7. 외부 출처·라이선스

`THIRD_PARTY_NOTICES.md`. 외부 코드 복사 없음(모두 Gradle 의존성). LICENSE 없는 Pleos 샘플은 참고만.

## 8. 변경 파일 목록

공유 저장소 `main`(a8a3968) 대비 변경은 전부 `vehicle-integration/` 아래이며 그 밖의 파일은 0개다. 정확한 목록:

```bash
git diff --name-status a8a3968..vehicle-integration-v0.1.0
```

| 폴더 | 내용 |
|---|---|
| `replay/` | 계약 DTO·파서·타임라인·재생 시계 + 단위 테스트 |
| `adapters/` | Provider 4종·로더 + 통합 테스트 |
| `sdk/` | Pleos/AAOS 어댑터, 매니페스트 |
| `app/` | Compose 앱, assets Mock 시나리오 6종, UI 테스트 |
| `tests/` | Mock 생성기, fixture 11종, 테스트 로그·스크린샷 |
| 루트 | Gradle 설정·wrapper, lock 파일, 문서 5종 |

## 9. 버전

| 항목 | 값 |
|---|---|
| 태그 | `vehicle-integration-v0.1.0` |
| 커밋 SHA | 태그가 가리키는 커밋. 보고서가 그 커밋 안에 있어 여기 적을 수 없으므로 `git rev-list -n 1 vehicle-integration-v0.1.0` 로 확인하고, PR #2 댓글에 기록 |
