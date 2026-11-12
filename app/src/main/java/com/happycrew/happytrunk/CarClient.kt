package com.happycrew.happytrunk

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class CarState(
    val soc: Int = 0, val rangeM: Float = 0f /* SDK 단위 m */, val batteryKwh: Float = 0f,
    val lat: Double = 37.5665, val lng: Double = 126.9780, val address: String = "",
    val parked: Boolean = false, val operators: List<ChargerOperator> = emptyList(), val bookmarks: List<BookmarkInfo> = emptyList(), val favorites: List<Spot> = emptyList(), val chargeLimit: Int? = null,
    val arrived: Boolean = false, val vehicleMock: Boolean = false, val naviMock: Boolean = false, val log: String = ""
)

const val FAVORITE = "내비 즐겨찾기"

/** Vehicle + NaviHelper 래퍼. 초기화 실패(일반 에뮬) 시 mock 값으로 UI 데모 가능. */
class CarClient(context: Context) : NaviHelperEventListener {
    private val tag = "CarClient"
    val state = MutableStateFlow(CarState())
    private val vehicle = Vehicle(context.applicationContext)
    private val navi = NaviHelper(context.applicationContext)
    @Volatile private var tripCallback: ((TripInfo?) -> Unit)? = null
    private val tripLock = Mutex() // 콜백 1개라 동시 요청 불가 (시드 스팟·즐겨찾기 스팟)

    fun start() {
        try {
            vehicle.initialize()
            val ev = vehicle.getEvBattery(); val odo = vehicle.getOdometer()
            ev.getEvBatteryStateOfCharge({ v -> v?.let { x -> state.update { it.copy(soc = x) } } }, ::err)
            // 에뮬 2.1.0 은 Wh(77400) 로 줌. 1000 넘으면 Wh 로 간주
            ev.getEvBatteryCapacity({ v -> v?.let { x -> Log.i(tag, "capacity $x"); state.update { it.copy(batteryKwh = if (x > 1000f) x / 1000f else x) } } }, ::err)
            odo.getRangeRemainingDistance({ v -> v?.let { x -> state.update { it.copy(rangeM = x) } } }, ::err)
            ev.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(v: Int?) { Log.i(tag, "soc $v"); v?.let { s -> state.update { it.copy(soc = s) } } }
                override fun onFailed(e: Exception) = err(e)
            })
            odo.registerRangeRemainingListener(object : RangeRemainingListener {
                override fun onRangeRemainingChanged(v: Float?) { Log.i(tag, "range $v"); v?.let { r -> state.update { it.copy(rangeM = r) } } }
                override fun onFailed(e: Exception) = err(e)
            })
            vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(g: VehicleGear?) { Log.i(tag, "gear $g"); state.update { it.copy(parked = g == VehicleGear.GEAR_PARK) } }
                override fun onFailed(e: Exception) = err(e)
            })
            vehicle.getGear().getCurrentGearSelectionState({ g -> state.update { it.copy(parked = g == VehicleGear.GEAR_PARK) } }, ::err)
        } catch (e: Exception) {
            Log.w(tag, "Vehicle init failed → mock", e)
            state.update { it.copy(vehicleMock = true, soc = 62, rangeM = 230000f, batteryKwh = 77.4f, parked = true, log = "Vehicle 미연결(mock)") }
        }
        try {
            navi.initialize(); navi.addListener(this)
            navi.getCurrentLocationInfo(); navi.getChargerOperatorInfo(); navi.getBookmarkInfo()
        } catch (e: Exception) {
            Log.w(tag, "Navi init failed → mock", e)
            state.update { it.copy(naviMock = true, address = "서울 중구 세종대로 110", log = "Navi 미연결(mock)") }
        }
    }
    private fun err(e: Exception) { Log.w(tag, "sdk error", e); state.update { it.copy(log = e.message ?: "error") } }

    /** 경로 안 바꾸고 "거기까지 가면 SoC 몇 %?" 시뮬. 내비 없으면 거리 기반 추정. */
    suspend fun trip(spot: Spot): TripInfo? {
        if (state.value.naviMock) return estimateTrip(spot)
        return tripLock.withLock {
            withTimeoutOrNull(8000) {
                suspendCancellableCoroutine { cont ->
                    tripCallback = { cont.resume(it) }
                    navi.requestTrip(TripRequest(spot.lat, spot.lng, "", spot.name, "0"))
                }
            } ?: estimateTrip(spot) // 내비 무응답이면 "계산 중"에 멈추지 않게
        }
    }

    /** 내비 없을 때 거리 기반 추정: 도로 계수 1.3, 180Wh/km */
    private fun estimateTrip(spot: Spot): TripInfo {
        val s = state.value
        val km = haversineKm(s.lat, s.lng, spot.lat, spot.lng) * 1.3
        val soc = if (s.rangeM > 0f) s.soc - (km * 1000 / s.rangeM * s.soc).toInt() else s.soc
        return TripInfo((km * 1000).toInt(), (km / 70 * 3600).toInt(), soc, km * 180, TripSummary(emptyList(), emptyList()))
    }

    fun route(name: String, lat: Double, lng: Double, addr: String) {
        state.update { it.copy(arrived = false) } // 이전 도착 상태로 새 안내가 차박 모드 오진입하지 않게
        if (state.value.naviMock) { state.update { it.copy(log = "[mock] 내비 목적지 → $name") }; return }
        // poiId 빈값으로 임의 좌표 동작 여부 = 에뮬 1순위 검증 항목 (문서 9절)
        navi.requestRoute(RouteInfo(latitude = lat, longitude = lng, poiName = name, poiId = "", poiSubId = "0", address = addr))
    }

    /** 충전소 경유 후 목적지 */
    fun routeVia(name: String, lat: Double, lng: Double, addr: String, via: Charger) {
        state.update { it.copy(arrived = false) }
        if (state.value.naviMock) { state.update { it.copy(log = "[mock] ${via.name} 경유 → $name") }; return }
        navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(
            RouteInfo(latitude = lat, longitude = lng, poiName = name, poiId = "", poiSubId = "0", address = addr),
            listOf(RequestWaypointInfo(latitude = via.lat, longitude = via.lng, poiName = via.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = via.operator, poiSubId = "0"))))
    }

    /** 순정 내비 지도 화면 열기 (NAVI_CUSTOM_MAP) */
    fun openNearbyChargers() {
        if (state.value.naviMock) { state.update { it.copy(log = "[mock] 내비 주변 충전소") }; return }
        navi.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations)
    }

    /** 충전 목표 SoC. 앱 계산에만 사용 (차량 반영은 CONTROL_CAR_ENERGY 승인 필요해 제외) */
    fun setChargeLimit(pct: Int) = state.update { it.copy(chargeLimit = pct, log = "충전 목표 $pct%") }

    /** 디버그 전용: 에뮬에서 실제 도착 이벤트 재현이 어려워 동일 상태만 주입 */
    fun demoArrive() = state.update { it.copy(arrived = true) }

    /** 디버그 전용: 에뮬에 일반 즐겨찾기가 없어 주입 */
    fun demoFavorite() = onBookmarkInfo(listOf(BookmarkInfo(37.6, 127.45, null, null, "내가 찍어둔 차박지", "", "0", "경기 가평군 설악면", BookmarkType.DEFAULT, "")))

    fun stop() { runCatching { navi.removeListener(this); navi.release() }; runCatching { vehicle.release() } }

    // 먼저 비운다: invoke 가 재개한 코루틴이 다음 요청 콜백을 등록하므로, 나중에 비우면 그 콜백이 지워져 응답을 잃음
    private fun takeTripCallback() = tripCallback.also { tripCallback = null }

    override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
        state.update { it.copy(lat = info.latitude, lng = info.longitude, address = info.address ?: "") }
    }
    override fun onChargerOperatorInfo(ops: List<ChargerOperator>) { Log.i(tag, "operators $ops"); state.update { it.copy(operators = ops) } }
    override fun onBookmarkInfo(list: List<BookmarkInfo>) { Log.i(tag, "bookmarks $list"); state.update { it.copy(bookmarks = list.filter { b -> b.category == BookmarkType.EV_CHARGING_STATION },
        favorites = list.filter { b -> b.category == BookmarkType.DEFAULT }.map { b -> Spot(b.name, b.latitude, b.longitude, b.address, FAVORITE) }) } }
    override fun onBookMarkChanged() = navi.getBookmarkInfo()
    override fun onTripInfo(tripInfo: TripInfo) { Log.i(tag, "onTripInfo $tripInfo"); takeTripCallback()?.invoke(tripInfo) }
    override fun onDestinationArrived(info: DestinationArrivedInfo) { state.update { it.copy(arrived = true, log = "도착: ${info.name}") } }
    override fun onRouteStarted(info: RouteStartInfo) { state.update { it.copy(arrived = false, log = "안내 시작: ${info.name} ${info.distance / 1000}km") } }
    override fun onError(code: ai.pleos.playground.navi.constants.NaviErrorCode) { takeTripCallback()?.invoke(null); state.update { it.copy(log = "navi $code") } }
}
