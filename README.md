# 해피트렁크

> 전기차 차박 도우미 — 지금 배터리로 갈 수 있는 차박지, 밤새 쓸 배터리, 아침 충전소까지 한 번에 순정 내비로 안내.

- 팀: 해피크루 / 패키지: `com.happycrew.happytrunk` / Pleos Connect(AAOS) 앱
- APK: `apk/해피크루_해피트렁크_release.apk` (release 서명)

## 실행 방법
1. 아래 "Pleos 에뮬레이터" 절차로 AVD(`Pleos_Connect_v2_0`) 생성·부팅
2. `adb install -r apk/해피크루_해피트렁크_release.apk`
3. 앱 실행. 차량 값은 Virtual Console(아래 curl)로 바꿔 확인

## 테스트 환경 및 설정
- Pleos Connect 에뮬레이터 2.1.0 (android-34, arm64), 2560×1440 @160dpi
- 공공데이터포털 키 없으면 내장 `Seed` 데이터로 동작. 소스 빌드 시 `local.properties` 에 `DATA_GO_KR_KEY=...`
- 사용 권한: `CAR_ENERGY`, `CAR_INFO`, `CAR_DRIVE`, `TTS_SERVICE`, `NAVI_CUSTOM_MAP`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`

근거 자료: `PLEOS_SDK_분석.md`.

## 빌드
```
./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # Planner 로직 테스트
```
`local.properties` 에 `DATA_GO_KR_KEY=공공데이터포털키` 넣으면 고캠핑·환경부 충전소 실데이터, 없으면 `Seed` 데이터.

## 구조 (app/src/main/java/com/happycrew/happytrunk)
- `CarClient.kt` — Vehicle(EvBattery/Odometer/Gear) + NaviHelper(requestTrip/requestRoute/ChargerOperator/Location). SDK 초기화 실패 시 mock 모드로 UI 데모.
- `Planner.kt` — 야간 소모 추정(기온별 히터), 기상청 격자/발표시각, 충전소 랭킹(회원사업자→급속→단가→거리), 단가표, Seed.
- `Repo.kt` — 공공 API(HttpURLConnection+org.json).
- `MainActivity.kt` — Compose UI 2분할(스팟 리스트 / 플랜·충전소) + ViewModel.

## Pleos 에뮬레이터 (검증 완료 2026-11-01)
이미지: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_arm64_emulator.zip` (4.7GB) →
`$ANDROID_HOME/system-images/android-34/pleos_car_emulator/arm64-v8a/` 에 풀고 package.xml 추가. 이미지 안의 devices.xml 을 `~/.android/devices.xml` 로 복사 → 디바이스 `Pleos_Connect_v2_0`(2560×1440 @160dpi) 로 AVD 생성. **범용 automotive 디바이스로 만들면 시스템 UI 가 겹침.**
```
avdmanager create avd -n pleos -k "system-images;android-34;pleos_car_emulator;arm64-v8a" -d Pleos_Connect_v2_0
# config.ini: hw.ramSize=4096, fastboot.forceColdBoot=yes, hw.gpu.mode=host
```
```
emulator -avd pleos -no-snapshot -gpu host
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8088 tcp:8088   # Virtual Console. 쓰기는 /browser/session heartbeat(1s) 있어야 emulator_active
curl -X POST "http://127.0.0.1:8088/vehicle/ev-battery?field=soc&value=45"
curl -X POST "http://127.0.0.1:8088/vehicle/odometer?remaining_range_m=180000"
curl -X POST "http://127.0.0.1:8088/vehicle/gear?gear=Park"
```

| 검증 항목 | 결과 |
|---|---|
| `requestRoute` 빈 poiId + 임의 좌표 | ✅ 순정 내비 경로 안내 시작, `onRouteStarted` 수신 (docs/emul-navi-route.png) |
| `requestTrip` → `estimatedSocAtDest` | ✅ 5개 스팟 모두 값 수신. 단 내비 엔진 SoC 는 Virtual Console 과 별개(항상 100% 기준) → 앱은 `energyConsumption`(Wh)÷배터리 용량으로 차량 실제 SoC 에서 차감 (`arrivalSoc`) |
| Vehicle SoC/주행거리/기어 리스너 | ✅ Virtual Console 변경 즉시 반영. 주행거리 단위 **m**, 배터리 용량 **Wh**(77400) |
| `getChargerOperatorInfo` | ✅ 내비가 사업자 전체 목록(35개) 반환(회원 필터 아님) → 앱의 회원 사업자 설정 선택지로 사용. id 는 환경부 busiId 체계(E-pit=HD, SK일렉링크=ST) |
| 차박 모드 | ✅ 디버그 주입 도착(`am start ... --ez demoArrive true`) + 기어 P 로 진입, 재시작 복원. 실제 `onDestinationArrived` 수신은 미검증 |
| TTS(ON_DEVICE) | ✅ 재생 완료 로그 확인. `TTS_SERVICE` 권한, 로케일은 `Locale.KOREA` 필수(`ko` 는 unsupported) |
| `setEvChargePercentLimit` | ✅ 콜백 90.0 수신. 단 `CONTROL_CAR_ENERGY` 미승인이라 제출본은 앱 계산에만 반영 |
| `getBookmarkInfo` | ⚠ 에뮬엔 빈 HOME/OFFICE 만 있어 EV 즐겨찾기 행 표시는 미검증 |
| 기상청 단기예보 | ✅ 실키 호출 확인. 실패 시 히터 0.8kW 가정 |
| 내비 배경 지도 | ⚠ 에뮬 네트워크 정상인데 경로선·라벨·바다만 표시, 도로·지명 없음. 내비는 Mapbox 렌더링, 로그에 타일 오류 없음. 원인 미확정 |
| `requestNavigationScreen(NearbyChargingStations)` | 차박 모드 버튼. `NAVI_CUSTOM_MAP` 필요 |
| `requestRouteWithWaypoints` | ✅ 빈 poiId 충전소를 경유지로, 스팟을 목적지로 경로 생성 |
| 경유 충전 SoC | ✅ 단위 테스트·에뮬 화면. 구간 소비량은 직행 소비량 비례 근사, 충전 목표 기본 80% |
| 공공 API 키 | ✅ 고캠핑·충전소·기상청·주차장 실호출 확인(2026-11-01). 충전소는 시군구별 조회(`Regions.kt`, `scripts/gen_regions.py`로 생성) |
| 내비 즐겨찾기 → 스팟 | ⚠ 디버그 주입(`am start ... --ez demoFavorite true`)으로만 확인. 실제 DEFAULT 즐겨찾기 미검증 |
| 주차장 추천 | ⚠ 공영 전체 조회 후 거리 필터. 차박 가능 여부 데이터 없음 |
| 단가표 | ⚠ ME·PW 만 2차 출처로 갱신(2026-11-01). 나머지 사업자 공시 미확인, 충전 속도별 구분 없음 |
| 권한 | `CAR_ENERGY`(SoC), `CAR_INFO`(배터리 용량), `CAR_DRIVE`(주행거리·기어). 에뮬 2.1.0에 CAR_MILEAGE/POWERTRAIN 없음 |
| 창 크기 | 앱 영역 약 1330×950px(160dpi). 900dp 미만이면 세로 배치 폴백 |
