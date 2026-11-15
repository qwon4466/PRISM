# PRISM

**PRISM**은 교통 시뮬레이션의 차량 위치, 예측 궤적, 위험 등급을 Pleos 차량 화면에서 재생하는 Android 앱입니다. 기본 Simulation Mode는 저장된 데이터를 사용하며, 실시간 주변 차량 감지나 제동·조향 기능은 제공하지 않습니다.

## 실행 방법

1. [서명된 release APK](apk/PRISM_PRISM_release.apk)를 내려받습니다.
2. Android 9(API 28) 이상 기기 또는 Pleos Connect 에뮬레이터에 설치합니다.
   ```powershell
   adb install -r apk/PRISM_PRISM_release.apk
   ```
3. 런처에서 **PRISM**을 열고 기본 **Simulation Mode**에서 시나리오를 선택해 재생합니다. Pleos 에뮬레이터에서는 차량 홈의 PRISM 아이콘을 통해 앱 화면을 엽니다.

## 테스트 환경과 필요한 설정

| 항목 | 환경 |
|---|---|
| Pleos Connect 에뮬레이터 | `Pleos_Connect_v2.1.0`, Android 14(API 34), x86_64, **2560×1440 가로**, 차량 홈과 앱의 다중 창 |
| 앱 빌드 | JDK 17 이상, Android SDK Platform 37, `ANDROID_HOME`, `vehicle-integration/gradlew.bat` (Gradle 9.6.0) |
| 기본 실행 | 네트워크나 Pleos 서비스 설정 없이 Simulation Mode 실행 가능 |
| Pleos Context Mode | Pleos Connect 에뮬레이터의 차량·위치·내비·TTS 서비스와 필요한 위치 권한 사용 |

전체 소스를 clone한 뒤 저장소의 `vehicle-integration` 폴더에서 빌드하고 테스트합니다. `vehicle-integration`은 형제 폴더의 `visualization-engine`과 `simulation-engine`을 참조하므로 폴더 구조를 유지해야 합니다.

```powershell
cd vehicle-integration
.\gradlew.bat :app:assembleRelease
.\gradlew.bat test
```

Gradle의 `app-release-unsigned.apk`는 서명 전 파일입니다. 설치 가능한 제출 APK는 별도 보관한 release 키로 서명해 저장소의 [`apk/PRISM_PRISM_release.apk`](apk/PRISM_PRISM_release.apk)에 두었습니다. 서명 키와 암호는 저장소에 포함하지 않습니다. 같은 앱의 업데이트 APK를 만들 때는 동일한 서명 키가 필요합니다.

각 엔진과 테스트 기록은 [vehicle-integration/README.md](vehicle-integration/README.md), [docs/SRS.md](docs/SRS.md), [docs/QA.md](docs/QA.md)를 참고하세요. 이 앱은 검증된 차량 안전 장치가 아닙니다.
