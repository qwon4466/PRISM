# 통합 시뮬레이션 실행 기록 (2026-11-12)

- 환경: AVD `Pleos_Connect_v2.1.0` (Android 14), 앱 Simulation Mode, 정차 후 리플레이(04 `RadarScreen` DETAIL 모드) 1.0배속
- 데이터: 03 simulation-engine a6d177c 시나리오 7종 + 01 prediction-engine 22027e5 예측 (`vehicle-integration/pipeline/`)
- 02 위험 결과 없음 → 모든 화면에 "위험 정보 없음 (RiskFrame 없음)", 위험 등급 강조 없음
- 파일: `<시나리오>.mp4` (adb screenrecord 14초, 1280×720), `<시나리오>_2.png` (재생 중 캡처 1장)

| 시나리오 | 03 이벤트 (manifest) | 화면에서 확인한 것 |
|---|---|---|
| sudden_braking | 4.0s truck_01 전방 화물차 급감속 | 화물차가 감속해 자차 앞으로 다가오고 자차가 따라 감속, 01 후보 궤적 표시 |
| chain_reaction | 4.0s truck_01 급감속, 5.0s car_11 L2→L3 회피 | 선두 화물차 급감속 후 앞차가 오른쪽으로 회피 |
| lane_change | 5.5s car_08 L1→L2 끼어들기 | 왼쪽 차선 차량이 자차 앞으로 진입 |
| merge | 2.0s car_07 가속, 3.0s car_07 L3→L2 합류 | 오른쪽 차량이 자차 차선 전방으로 합류 |
| normal | 없음 | 정속 주행, 01 의 차선 유지·좌우 변경 후보 궤적 |
| rear_approach | 0.0s car_06 후방 급접근 | 후방 차량 접근 |
| sensor_dropout | 3.0s car_01 2초 관측 누락, 7.0s car_03 0.5초 누락, car_05 위치 노이즈 | 누락 구간에서 차량이 사라지고 그 차량의 예측도 없음 |

01 예측 정확도(합성 데이터, 화면과 별개): `../prediction-eval-2026-11-12.txt`

녹화 중 에뮬레이터의 Pleos 하드웨어 서비스가 재시작되며 `/sdcard` 저장소가 깨져 두 번 다시 녹화했다(`/data/local/tmp` 사용). 앱 crash 는 없었다.
