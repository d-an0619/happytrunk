# 현대차그룹 차량용 앱 공모전 — Pleos SDK 분석 & 아이디어 검증

조사일 2026-10-27. 출처: appdevchallenge.auto-hmg.io, document.pleos.ai, Pleos-Playground GitHub 샘플 3종, nexus-playground.pleos.ai 의 실제 aar 디컴파일(javap).

---

## 0. 결론 먼저

**"전기차 차박 코스" 아이디어 = 구현 가능. 오히려 SDK 궁합 최상급.**

| 영우 아이디어 | 가능? | 근거 |
|---|---|---|
| 차박스팟 근처 충전소·주차장 빨리 찾기 | ✅ | 외부 공공 API(환경부 충전소, 고캠핑, 공영주차장) + NaviHelper `requestRoute` 로 순정 내비에 바로 꽂기 |
| 순정 내비 연동 | ✅ | NaviHelper SDK = 순정 내비 원격조종. 목적지·경유지 설정, ETA, **도착 시 예상 SoC** 까지 받음 |
| 충전비 비교 | ✅(정적) | 사업자별 단가표는 공개. 실시간 단가 API 없음 → 테이블 하드코딩 |
| 거리 비교 | ✅ | `requestTrip(lat,lng)` → `TripInfo(distance, eta, estimatedSocAtDest, energyConsumption)` |
| 주차장 추천 | ✅ | 공공 주차장 API + 차박 가능 여부 태그 |
| 폰에 깔린 앱 보고 회원인 충전사업자 우선 | ❌→대체 | 차량이 폰 설치앱 못 봄. 대체: ① 앱 설정에서 보유 회원카드 체크 ② `getChargerOperatorInfo()` = 내비에 설정된 선호 충전사업자 목록 받아옴 (이게 사실상 같은 효과) |
| "네이버지도에 매일 찍어두는데" (스팟 수집) | ✅ | `getBookmarkInfo()` 로 내비 즐겨찾기 읽기 가능 (`BookmarkType.EV_CHARGING_STATION` 구분됨) |

**차량특화성 심사항목 저격 포인트**: 모바일 앱은 "내 배터리 몇 %로 거기까지 가고 밤새 히터 틀고 아침에 충전소까지 갈 수 있나"를 모름. 차량 앱은 `EvBattery.getEvBatteryStateOfCharge` + `Odometer.getRangeRemainingDistance` + 내비 `estimatedSocAtDest` 로 **정확히 계산 가능**. 이게 핵심 셀링포인트.

---

## 1. 공모전 요약

- 접수 2026.10.21~11.15 (이메일, 기획서=접수양식). 온라인 설명회 10.28.
- 1차 서류 발표 11.23 → 2차 오프라인 PT 12.3~4 (**동작하는 앱 필수**) → 최종 12.18 → 2027.02 전시 10팀.
- 상금 총 5,100만: 대상 1,000(대학/일반 각1), 최우수 600, 우수 400, 아이디어특별상 150.
- 심사: 차량특화성 / 창의성 / 기술활용성(Pleos SDK 적절히 썼나) / 사업성(타겟, MAU, 리텐션). 배점 비공개. 영상+GitHub+APK 제출 시 가산점.
- 숏폼 챌린지 별도 50만.
- 플랫폼: **Pleos Connect = AAOS(Android Automotive) 기반**. Android API 28+, Gradle 8+, Kotlin/Java. 에뮬레이터 Android 14(API34) 이미지 제공.

## 2. SDK 목록 & 현재 상태

| SDK | 버전 | 포털 상태 | 용도 |
|---|---|---|---|
| Vehicle (State) | 2.3.2 | ✅ 사용 가능 | 차량 상태 조회 20 도메인 |
| Vehicle (Control) | — | ⏳ 준비 중 | 도어/창문/공조 제어 (set 계열) |
| NaviHelper | 3.0.5 | ✅ 사용 가능 | 순정 내비 제어·정보 |
| FusedLocation | 1.1.3 | ⏳ 준비 중 | 위치 (대체: NaviHelper `getCurrentLocationInfo`) |
| TextToSpeech | 3.3.1 | ⏳ 준비 중 | TTS (ON_DEVICE 모드는 등록 불필요) |
| SpeechToText | 3.3.1 | ⏳ 준비 중 | STT (텍스트만 반환) |
| LLM | 2.1.3.2 | 비공개(nexus에만 존재, 에뮬 2.1.0에서 제거됨) | `generateContent/startChat`, 서버·온디바이스 |
| placesense | V1 | 비공개 jar | 미확인 |

"준비 중" 은 Playground 포털 신청 상태 기준. aar 자체는 nexus에 공개돼 있어 빌드는 됨. 에뮬레이터 동작 여부는 직접 확인 필요.

### Gradle
```kotlin
// settings.gradle.kts
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
// build.gradle.kts
implementation("ai.pleos.playground:Vehicle:2.3.2")
implementation("ai.pleos.playground:NaviHelper:3.0.5")
implementation("ai.pleos.playground:FusedLocation:1.1.3")
implementation("ai.pleos.playground:TextToSpeech:3.3.1")
implementation("ai.pleos.playground:SpeechToText:3.3.1")
```
토큰/인증 불필요. 샘플: github.com/Pleos-Playground/{pleos-sample-vehicle-app, pleos-sample-navi-app, pleos-sample-fusedlocation-app}

### 공통 패턴
`X(context)` → `initialize()` → `addListener()` → API 호출(비동기 IPC, 결과는 콜백/onComplete·onFailed) → `release()`.
Vehicle 은 `checkXxxCapabilities` 로 차종별 지원 여부 먼저 확인.

## 3. Vehicle SDK — 우리한테 쓸모 있는 도메인

`Vehicle.getEvBattery() / getOdometer() / getGear() / getDrivingMode() / getCarInfo() / getFuel() / getHVAC() / getDoor() / getWindow() / getSeat() / getLight() / getSafety() / getTire() / getBrake() / getTurnSignal() / getWiper() / getToll() / getDisplay() / getSideMirror() / getSteeringWheel()`

| 도메인 | 핵심 API | 권한 |
|---|---|---|
| EvBattery | `getEvBatteryStateOfCharge`(Int %), `getEvBatteryLevel`(Float), `getEvBatteryCapacity`, `getEvChargeSwitchState`, `registerEvChargePortOpen`, 효율 4종(순간/현재/충전후/누적), set: `setEvChargePercentLimit`, `setEvChargePortOpen`, `setEvChargeSwitch` | CAR_ENERGY / CONTROL_CAR_ENERGY |
| Odometer | **`getRangeRemainingDistance`** (주행가능거리), `getCurrentDistanceDriven`, trip 거리/시간 (current/cumulative/sinceRefill) | CAR_DRIVE / CAR_MILEAGE |
| Gear | `getCurrentGearSelectionState` (PARK/DRIVE/…) → 주차 상태 판단 | CAR_POWERTRAIN |
| DrivingMode | `getCurrentDrivingModeState`, 크루즈 상태 | CAR_DRIVE |
| CarInfo | `getVINInfo`, `getCapabilityInfo` | CAR_INFO |
| HVAC | 온도/풍량/시트열선/통풍 조회·제어 | CAR_CLIMATE |
| Safety | 경고등, 안전벨트, 에어백 | CAR_SAFETY |
| Tire | 공기압 | CAR_TIRES |

매니페스트 전체 권한 목록(샘플 기준): CAR_CLIMATE, CAR_DOORS, CAR_DRIVE, CAR_ENERGY, CAR_INFO, CAR_LIGHTS, CAR_MILEAGE, CAR_MIRRORS, CAR_POWERTRAIN, CAR_SAFETY, CAR_SEATS, CAR_SPEED, CAR_TIRES, CAR_TOLL, CAR_WINDOWS, CONTROL_CAR_*, READ_ADAS_STATES, READ_CAR_DISPLAY_UNITS, READ_CAR_STEERING, READ_WINDSHIELD_WIPERS, FUSED_LOCATION, NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP, NAVI_CUSTOM_ROUTE, NAVI_CUSTOM_ETC. 프리픽스 `pleos.car.permission.`. **쓰는 것만 선언** (미승인 권한 선언 시 심사 탈락).

## 4. NaviHelper SDK — 순정 내비 전체 API (aar 디컴파일 결과)

### 요청
| 메서드 | 권한 | 설명 |
|---|---|---|
| `requestRoute(RouteInfo)` | NAVI_ROUTE_SEARCH | 목적지 설정. `RouteInfo(lat, lng, displayLat?, displayLng?, poiName, poiId, poiSubId, address, routeOption?)` |
| `requestRouteWithWaypoints(RouteWithWaypointsRequest)` | " | 목적지+경유지 리스트 한 번에 |
| `addWaypoint(RequestWaypointInfo)` / `removeWaypoint(WaypointIndex)` | " | 경유지 추가/삭제 |
| `changeRouteOption(RouteOption)` | " | RECOMMENDED/SHORT_DISTANCE/SHORT_TIME/FREE/HIGHWAY/COMFORTABLE/SCHOOL_ZONE |
| `requestReRoute()` / `cancelRoute()` | " | |
| **`requestTrip(TripRequest(lat,lng,poiId,poiName,poiSubId))`** | NAVI_ROUTE | 경로 안 바꾸고 "거기까지 가면?" 시뮬 → `onTripInfo` |
| `getCurrentLocationInfo()` | NAVI_ROUTE | → lat/lng/heading/address |
| `getDestinationInfo()` / `getWaypointInfo()` | " | 남은거리·시간·통행료 |
| `getRouteStateInfo()` | " | naviMode, isRouting, routeOption, **remainDestinationChargingInt**, trafficStatus |
| `getTBTInfo()` | " | 턴바이턴 |
| `getBookmarkInfo()` / `getRecentDestinationInfo()` | " | 즐겨찾기(HOME/OFFICE/EV_CHARGING_STATION/DEFAULT)·최근목적지 |
| **`getChargerOperatorInfo()`** | " | 내비에 설정된 충전사업자 목록 `[ChargerOperator(id,name)]` |
| `requestNavigationScreen(NavigationScreenRequest)` | NAVI_CUSTOM_MAP | 내비 화면 띄우기 |

### 이벤트 (NaviHelperEventListener, 전부 default 메서드라 필요한 것만 override)
`onRouteStarted(RouteStartInfo{duration,distance,tollFare,name,remainDestinationChargingValue})`, `onRoutingCompleted`, `onRouteEnded`, `onReRouteEnded`, `onRouteCancelled`, `onRouteOptionChanged`, `onWaypointChanged`, `onDestinationArrived(DestinationArrivedInfo)`, `onWayPointArrived`, `onBookMarkChanged`, `onDestinationChanged`, `onError(NaviErrorCode)`, `onCurrentLocationInfo`, `onRouteStateInfo`, `onDestinationInfo`, `onWayPointInfo`, `onRecentDestinationInfo`, `onBookmarkInfo`, `onCurrentRoadSpeedLimit(int)`, `onCameraAlert`, **`onDrivingInfo(DrivingInfo)`**, `onNavigationStatus(INACTIVE/INITIALIZING/FREE_DRIVE/ROUTE_DRIVE)`, `onTBTInfo`, `onChargerOperatorInfo`, `onCautionPointInfo`, **`onTripInfo(TripInfo)`**

### EV 전용 데이터 (이거 보고 아이디어 확신함)
```
TripInfo      { distance, eta, estimatedSocAtDest, energyConsumption, summary }
DrivingState  { name, duration, distance, remainDestinationChargingValue,
                isChargingStation, isRecommendedChargingStation,
                maxChargingPower, targetSoc, chargerCount, chargingPlan }
ChargingPlan  { duration, startSoc, endSoc }
RoutePointPoiType { NORMAL, EV_CHARGING }
```
순정 내비가 이미 EV 충전 경로계획을 하고 있고 그 결과를 전부 노출함. 우리 앱은 "차박" 컨텍스트(숙박·주차·아침 충전)만 얹으면 됨.

### 한계
- **POI 검색 API 없음.** 좌표는 우리가 외부 API로 구해서 넣어야 함. `poiId` 가 내비 내부 ID인데 임의 좌표로 되는지 에뮬에서 **1순위 검증 필요**.
- 내비 앱이 실제 설치돼 있어야 함. 에뮬레이터 2.1.0 에 내비 포함 여부 미확인 → 설치 후 1순위 검증.
- 지도 렌더링 SDK 없음. 자체 지도 쓰려면 Naver/Kakao Map Android SDK (AAOS에서 동작 여부 확인) 또는 지도 없이 리스트/카드 UI + 내비로 넘기기 (주행 중 UX 규제 생각하면 후자가 안전).

## 5. FusedLocation / TTS / STT / LLM

- FusedLocation: `isSupported` → `initialize` → `getLastKnownLocation` / `registerFusedLocationCallback` / `addProximityAlert(ProximityAlertRequest)` (지오펜스!). `LocationData{lat,lng,altitude,heading,confidence,timestampMillis}`. 권한 FUSED_LOCATION. 포털 "준비 중" → NaviHelper 위치로 대체 가능.
- TTS `ai.pleos.playground.tts.TextToSpeech(ctx, Mode.HYBRID|SERVER|ON_DEVICE)` → `speak()`. ON_DEVICE 는 앱 등록 불필요. 차박 도착 안내/아침 충전 알림 음성에 유용.
- STT `SpeechToText(ctx, mode)` → `request()` → ResultListener 텍스트. 주행 중 검색은 음성 권장(가이드라인).
- LLM: 문서 없음, 에뮬레이터에서 제거. 기획서에 넣지 말 것.

## 6. 주행 규제 (기획서에 써야 할 제약)

- 상태: Parking / Idling / Driving. 속도·기어·브레이크로 판단. `CarUxRestrictions` 플래그: NO_KEYBOARD(8), NO_DIALPAD(1), NO_FILTERING(2), LIMIT_STRING_LENGTH(4), NO_VIDEO(16), LIMIT_CONTENT(32), NO_SETUP(64).
- 카테고리 **Mobility Services** 가 주행 중 허용 예시로 "**EV 충전소 검색 및 경유지 설정, 주차장 위치 확인**"을 명시. 우리 앱 딱 이 카테고리.
- 한국은 "그외 지역" → 주행 중 키보드·검색 허용. 그래도 음성/프리셋/즐겨찾기 우선, 텍스트 최소, 큰 터치타겟, 동영상 금지, 애니메이션 최소.
- 네트워크 끊김 대비 캐시·재시도·오프라인 표시 필수(가이드 명시).

## 7. 외부 데이터 (공공, 무료)

| 데이터 | 출처 |
|---|---|
| 전기차 충전소 위치·충전기 타입·상태(실시간) | 공공데이터포털 환경부 "전기자동차 충전소 정보" API |
| 사업자별 충전 단가 | 각사 공시 단가표 → 정적 테이블 (환경부/한전/현대 E-pit/SK일렉링크/채비/에버온 등) |
| 캠핑장·차박지 | 한국관광공사 고캠핑 API (차박 가능 여부, 전기 사용 가능, 화장실 등) |
| 주차장 | 공공데이터포털 전국주차장정보 표준데이터 |
| 날씨(차박 야간 기온 → 히터 전력 추정) | 기상청 단기예보 API |

## 8. 앱 아이디어 구체화: "차박 EV 플래너" (가칭)

**한 줄**: 현재 배터리로 갈 수 있는 차박 스팟을 골라주고, 밤새 쓸 전력까지 계산해 **아침에 충전소까지 안전하게 도착하는 코스**를 순정 내비에 바로 꽂아주는 앱.

**플로우**
1. 앱 켜면 `getEvBatteryStateOfCharge` + `getRangeRemainingDistance` + `getCurrentLocationInfo` 로 "지금 SoC 62%, 230km" 표시.
2. 고캠핑 API 로 반경 내 차박 스팟 리스트. 각 스팟에 `requestTrip` 날려 `estimatedSocAtDest` 받음 → "도착 시 41%" 카드에 표시. (주행 중엔 음성/큰 카드, 주차 중엔 상세)
3. 스팟 선택 → 야간 소비 추정(기상청 기온 × HVAC 사용 가정, `EvBatteryCapacity` 로 kWh 환산) → "아침 SoC 예상 28%".
4. 스팟 반경 N km 충전소(환경부 API) 중 `getChargerOperatorInfo` 로 받은 **내 회원 사업자 우선** + 단가 + 충전기 타입(급속/완속) + 실시간 가용 → 정렬.
5. "코스 확정" → `requestRouteWithWaypoints(목적지=차박스팟, 경유지=없음)` 지금 출발, 아침용 충전소는 `getBookmarkInfo`/앱 내 저장 → 다음날 원터치 `requestRoute`.
6. 도착(`onDestinationArrived`) + 기어 P(`getCurrentGearSelectionState`) → "차박 모드": 남은 SoC, 아침 충전소까지 거리, TTS 안내. 옵션: `setEvChargePercentLimit` 로 아침 충전 목표 SoC 세팅(Control SDK 열리면).

**차별화 (심사 기준 매핑)**
- 차량특화: 폰 앱은 절대 모르는 실시간 SoC·주행가능거리·내비 EV 경로계획 활용.
- 창의성: "차박 + EV 야간 전력 + 아침 충전" 묶은 앱 없음.
- 기술활용: Vehicle(EvBattery/Odometer/Gear/HVAC) + NaviHelper(requestTrip/Route/Bookmark/ChargerOperator) + TTS. SDK 5개 중 4개.
- 사업성: 차박 인구 + EV 보급 교차. 캠핑장 예약/충전사업자 제휴.

**MVP 범위 (PT 전까지)**: 1,2,4,5 번. 3번은 단순 가정치로.

## 9. 리스크 / 1주차 검증 과제

1. 에뮬레이터에 내비 앱 포함되는지, `requestRoute` 가 임의 lat/lng + 빈 poiId 로 동작하는지.
2. `requestTrip` → `onTripInfo.estimatedSocAtDest` 가 에뮬에서 값 나오는지 (내비 구현 의존이라 문서가 "보장 못함"이라 함).
3. Playground 계정 만들고 Vehicle State / NaviHelper 신청. TTS 는 "준비 중" → ON_DEVICE 모드 빌드만 해보기.
4. Virtual Console(adb forward 8088 → 127.0.0.1:8088) 에서 Energy 도메인(주행가능거리/SoC/충전상태) 조작해서 UI 반응 확인.
5. 공공데이터포털 API 키 3개(환경부 충전소, 고캠핑, 주차장) 발급. 승인 1~2일.

## 10. 기타 아이디어 후보 (같은 SDK로 가능한 것들)

- **EV 충전 대기 알림**: 충전소 도착 전 `requestTrip` 으로 ETA 계산 + 실시간 가용 충전기 → 꽉 찼으면 다음 충전소 자동 경유지.
- **장거리 가족 휴게 플래너**: 주행시간(`getTripCurrentDrivingTime`) + 아이 탑승(Seat 도메인) → 2시간마다 휴게소 경유지 자동 제안 + TTS.
- **안전운전 리포트**: 급제동(Brake), 과속(`onCurrentRoadSpeedLimit` vs 속도), 카메라 알림 → 점수. 공모전 예시와 겹쳐서 비추.
- **타이어/경고등 선제 정비 예약**: Safety/Tire → 근처 서비스센터 `requestRoute`. 차별성 약함.
