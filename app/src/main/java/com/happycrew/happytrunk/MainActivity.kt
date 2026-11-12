package com.happycrew.happytrunk

import ai.pleos.playground.navi.data.TripInfo
import android.app.Application
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class Plan(val spot: Spot, val trip: TripInfo?, val arriveSoc: Int, val morningSoc: Int, val tempC: Double?, val chargers: List<Pair<Charger, Double>>, val enRoute: List<Pair<Charger, Double>>, val parkings: List<Pair<Parking, Double>>, val viaEst: ViaEstimate?)

class MainVm(app: Application) : AndroidViewModel(app) {
    val car = CarClient(app)
    val spots = MutableStateFlow<List<Pair<Spot, TripInfo?>>>(emptyList())
    val plan = MutableStateFlow<Plan?>(null)
    private var chargers: List<Charger> = emptyList()
    private var parkings: List<Parking> = emptyList()
    private var allSpots: List<Spot> = emptyList()
    private val tripCache = mutableMapOf<Spot, TripInfo?>()
    private var filterGen = 0
    val spotRegion = MutableStateFlow(SPOT_REGIONS.keys.first())
    private var chargersJob: kotlinx.coroutines.Job? = null
    val chargersLoading = MutableStateFlow(false)
    val parking = MutableStateFlow<Parking?>(null) // 스팟 대신 목적지로 삼을 주차장
    private val prefs = app.getSharedPreferences("plan", Context.MODE_PRIVATE)
    val savedCharger = MutableStateFlow(prefs.getString("charger", null))
    val members = MutableStateFlow(prefs.getStringSet("members", emptySet())!!.toSet())
    val camp = MutableStateFlow(prefs.getString("camp", null)?.let(::decodeSpot))
    val campTemp = MutableStateFlow<Double?>(null)
    val via = MutableStateFlow<Charger?>(null) // 가는 길에 들를 충전소
    private var selected: Pair<Spot, TripInfo?>? = null
    private val temps = mutableMapOf<Spot, Double?>()
    private val tts = TextToSpeech(app, Mode.ON_DEVICE)
    private var ttsReady = false
    private var ttsPending: String? = null

    init {
        car.start()
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() { ttsReady = true; tts.setLocale(Locale.KOREA); ttsPending?.let(tts::speak); ttsPending = null }
                override fun onFailure(e: ErrorType) { Log.w("MainVm", "TTS init $e") }
            })
        }.onFailure { Log.w("MainVm", "TTS", it) }
        viewModelScope.launch {
            // 위치 받기 전이면 기본 좌표(서울시청) 기준 거리순이 되므로 잠깐 대기
            withTimeoutOrNull(3000) { car.state.first { it.address.isNotBlank() } }
            allSpots = Repo.spots()
            applySpotFilter()
            launch { parkings = Repo.parkings(); refreshPlan() }
            camp.value?.let { campTemp.value = Repo.nightLow(it.lat, it.lng) }
        }
        // 내비 즐겨찾기 일반 장소 → 스팟 목록 맨 위
        viewModelScope.launch {
            car.state.map { it.favorites }.distinctUntilChanged().collect { applySpotFilter() }
        }
        // 안내 시작한 스팟에 도착 + 기어 P → 차박 모드 (충전소 도착은 pendingCamp 없어서 제외)
        viewModelScope.launch {
            car.state.collect { s ->
                if (s.arrived && s.parked && camp.value == null) prefs.getString("pendingCamp", null)?.let { enterCamp(decodeSpot(it)) }
            }
        }
    }

    private fun encodeSpot(s: Spot) = "${s.name}|${s.lat}|${s.lng}|${s.addr}"
    private fun decodeSpot(v: String) = v.split("|").let { Spot(it[0], it[1].toDouble(), it[2].toDouble(), it[3], "") }

    private fun enterCamp(spot: Spot) {
        camp.value = spot
        prefs.edit().putString("camp", encodeSpot(spot)).remove("pendingCamp").apply()
        // 저장한 충전소가 없거나 지난 여행 것(30km 밖)이면 랭킹 1위로 대체
        if (morningKm(spot) ?: Double.MAX_VALUE > 30)
            rankChargers(chargers, spot, members.value).firstOrNull()?.first?.let { saveMorning(it.name, it.lat, it.lng, it.operator) }
        viewModelScope.launch {
            campTemp.value = Repo.nightLow(spot.lat, spot.lng)
            val km = morningKm(spot)?.let { ", 아침 충전소까지 ${it.toInt()}킬로미터" } ?: ""
            speak("차박 모드를 시작합니다. 배터리 ${car.state.value.soc}퍼센트$km 입니다.")
        }
    }

    private fun speak(text: String) { if (ttsReady) runCatching { tts.speak(text) } else ttsPending = text }

    fun morningKm(spot: Spot): Double? = savedCharger.value?.split("|")?.let { haversineKm(spot.lat, spot.lng, it[1].toDouble(), it[2].toDouble()) }

    fun exitCamp() { camp.value = null; prefs.edit().remove("camp").remove("pendingCamp").apply() }

    fun select(spot: Spot, trip: TripInfo?) {
        if (selected?.first != spot) { via.value = null; parking.value = null; loadChargers(spot) }
        selected = spot to trip
        refreshPlan()
        if (spot !in temps) viewModelScope.launch { temps[spot] = Repo.nightLow(spot.lat, spot.lng); refreshPlan() }
    }

    fun setSpotRegion(r: String) { spotRegion.value = r; applySpotFilter() }

    /** 즐겨찾기 + 필터 결과 표시 후 빠진 도착 SoC 만 직렬 요청. 필터가 바뀌면 진행 중 요청만 끝내고 이전 루프 종료 */
    private fun applySpotFilter() {
        val s = car.state.value; val gen = ++filterGen
        val list = (s.favorites + filterSpots(allSpots, s.lat, s.lng, spotRegion.value)).distinct()
        spots.value = list.map { it to tripCache[it] }
        viewModelScope.launch {
            for (sp in list) {
                if (gen != filterGen) return@launch
                if (sp in tripCache) continue
                val t = car.trip(sp); tripCache[sp] = t
                spots.update { l -> l.map { if (it.first == sp) sp to t else it } }
            }
        }
    }

    private fun loadChargers(spot: Spot) {
        chargersJob?.cancel(); chargers = emptyList(); chargersLoading.value = true
        chargersJob = viewModelScope.launch {
            val s = car.state.value
            chargers = Repo.chargers(regionsAlong(s.lat, s.lng, spot)); chargersLoading.value = false
            refreshPlan()
        }
    }

    private fun refreshPlan() {
        val (spot, trip) = selected ?: return
        val s = car.state.value; val t = temps[spot]
        val arrive = trip?.let { arrivalSoc(s.soc, s.batteryKwh, it.energyConsumption, it.estimatedSocAtDest) } ?: s.soc
        val est = via.value?.let { c -> trip?.let { viaEstimate(s.soc, s.batteryKwh, it.energyConsumption, s.lat, s.lng, spot, c, s.chargeLimit ?: 80) } }
        val finalSoc = est?.arrive ?: arrive
        plan.value = Plan(spot, trip, finalSoc, finalSoc - nightDropPct(s.batteryKwh, heaterKw = heaterKw(t)), t, rankChargers(chargers, spot, members.value), chargersEnRoute(chargers, s.lat, s.lng, spot, members.value), nearbyParkings(parkings, spot), est)
    }

    fun toggleVia(c: Charger) { via.update { if (it == c) null else c }; refreshPlan() }
    fun toggleParking(c: Parking) = parking.update { if (it == c) null else c }

    fun toggleMember(id: String) {
        members.update { if (id in it) it - id else it + id }
        prefs.edit().putStringSet("members", members.value).apply()
        refreshPlan()
    }

    /** 내비 사업자 목록. 내비 없으면 시드에서 추출 */
    fun operatorChoices(s: CarState): List<Pair<String, String>> =
        s.operators.map { it.id to it.name }.ifEmpty { Seed.chargers.map { it.operatorId to it.operator }.distinct() }

    fun go() {
        val p = plan.value ?: return
        // ponytail: 주차장 목적지여도 SoC 는 스팟 기준(10km 이내라 오차 작음)
        val dest = parking.value?.let { Spot(it.name, it.lat, it.lng, it.addr, "") } ?: p.spot
        prefs.edit().putString("pendingCamp", encodeSpot(dest)).apply()
        via.value?.let { car.routeVia(dest.name, dest.lat, dest.lng, dest.addr, it) } ?: car.route(dest.name, dest.lat, dest.lng, dest.addr)
    }

    /** 아침 충전소 저장 → 다음날 원터치 */
    fun saveMorning(name: String, lat: Double, lng: Double, addr: String) {
        prefs.edit().putString("charger", "$name|$lat|$lng|$addr").apply(); savedCharger.value = prefs.getString("charger", null)
    }
    fun goMorning() {
        val p = savedCharger.value?.split("|") ?: return
        exitCamp()
        car.route(p[0], p[1].toDouble(), p[2].toDouble(), p[3])
    }
    override fun onCleared() { car.stop(); runCatching { tts.release() } }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Bg, surface = Bg, onSurface = Ink)) { Surface(Modifier.fillMaxSize()) { App(vm) } } }
    }
    // adb shell am start -n com.happycrew.happytrunk/.MainActivity --ez demoArrive true
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demoArrive", false)) vm.car.demoArrive()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demoFavorite", false)) vm.car.demoFavorite()
    }
}

private val Ink = Color(0xFF16181D)
private val Sub = Color(0xFF6B7078)
private val Bg = Color(0xFFF4F5F8)
private val Line = Color(0xFFE1E4EA)
private val Blue = Color(0xFF1E7BFF)
private val Good = Color(0xFF12985A)
private val Warn = Color(0xFFE08600)
private val Bad = Color(0xFFD93A3A)
private val Corner = RoundedCornerShape(6.dp)

/** 아침에 충전소까지 갈 여유 기준: 20% 이상 안전, 10~20% 주의 */
private fun socColor(soc: Int) = if (soc >= 20) Good else if (soc >= 10) Warn else Bad
private fun tempLabel(t: Double?) = t?.let { "최저 %.0f°C".format(it) } ?: "기온 미확인"

@Composable
fun App(vm: MainVm) {
    val car by vm.car.state.collectAsState()
    val camp by vm.camp.collectAsState()
    camp?.let { CampPane(vm, car, it); return }
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    // Pleos 홈은 앱을 658px 멀티윈도우로 띄움 → 좁으면 세로 스택
    if (wide) Row(Modifier.fillMaxSize().background(Bg).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SpotPane(vm, car, Modifier.weight(1f)); PlanPane(vm, Modifier.weight(1f))
    } else Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SpotPane(vm, car, Modifier.weight(1f)); PlanPane(vm, Modifier.weight(1f))
    }
}

@Composable
private fun Bar(pct: Int, color: Color, m: Modifier = Modifier) {
    Box(m.fillMaxWidth().height(8.dp).background(Line, Corner)) {
        Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight().background(color, Corner))
    }
}

/** 흰 카드 행. selected 면 내비처럼 왼쪽 강조선 */
@Composable
private fun RowCard(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(Color.White, Corner)
        .border(1.dp, if (selected) Blue else Line, Corner).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(104.dp).background(if (selected) Blue else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Cta(text: String, onClick: () -> Unit, m: Modifier = Modifier) {
    Button(onClick, m.fillMaxWidth().height(84.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Secondary(text: String, enabled: Boolean = true, m: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, m.height(72.dp), enabled = enabled, shape = Corner, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
        Text(text, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SpotPane(vm: MainVm, car: CarState, m: Modifier) {
    val spots by vm.spots.collectAsState()
    val plan by vm.plan.collectAsState()
    val region by vm.spotRegion.collectAsState()
    Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().background(Color.White, Corner).border(1.dp, Line, Corner).padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${car.soc}%", fontSize = 60.sp, fontWeight = FontWeight.Bold, lineHeight = 62.sp)
                Spacer(Modifier.width(14.dp))
                Text("${(car.rangeM / 1000).toInt()}km 주행 가능", fontSize = 26.sp, color = Sub, modifier = Modifier.padding(bottom = 8.dp))
            }
            Bar(car.soc, socColor(car.soc), Modifier.padding(vertical = 10.dp))
            Text(car.address.ifBlank { "위치 확인 중" } + (if (car.parked) " · 주차 중" else " · 주행 중") + if (car.vehicleMock || car.naviMock) " · 데모 모드" else "", color = Sub, fontSize = 20.sp)
        }
        Text("차박 스팟", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SPOT_REGIONS.keys.forEach { r -> Tab(r, region == r, Modifier.weight(1f)) { vm.setSpotRegion(r) } }
        }
        if (spots.isEmpty()) Text("조건에 맞는 스팟이 없습니다", fontSize = 22.sp, color = Sub)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(spots) { (sp, trip) ->
                val soc = trip?.let { arrivalSoc(car.soc, car.batteryKwh, it.energyConsumption, it.estimatedSocAtDest) }
                RowCard(plan?.spot == sp, { vm.select(sp, trip) }) {
                    Column(Modifier.weight(1f)) {
                        Text(sp.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (sp.facilities == FAVORITE) sp.addr + " · " + FAVORITE else sp.addr, color = Sub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(soc?.let { "$it%" } ?: "계산 중", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = soc?.let(::socColor) ?: Sub)
                        trip?.let { Text("${it.distance / 1000}km · 도착 시", fontSize = 18.sp, color = Sub) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SocStep(label: String, value: Int, m: Modifier) {
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 20.sp, color = Sub)
        Text("$value%", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = socColor(value))
    }
}

@Composable
fun PlanPane(vm: MainVm, m: Modifier) {
    val plan by vm.plan.collectAsState()
    val members by vm.members.collectAsState()
    val saved by vm.savedCharger.collectAsState()
    val car by vm.car.state.collectAsState()
    val via by vm.via.collectAsState()
    val parking by vm.parking.collectAsState()
    val loading by vm.chargersLoading.collectAsState()
    var tab by remember { mutableStateOf("아침에") }
    var showMembers by remember { mutableStateOf(false) }
    val p = plan
    if (p == null) {
        Box(m.fillMaxSize().background(Color.White, Corner).border(1.dp, Line, Corner), contentAlignment = Alignment.Center) {
            Text("왼쪽에서 스팟을 고르면\n아침 배터리까지 계산합니다", color = Sub, fontSize = 24.sp, lineHeight = 34.sp)
        }
        return
    }
    val savedName = saved?.substringBefore("|")
    Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().background(Color.White, Corner).border(1.dp, Line, Corner).padding(20.dp)) {
            Text(p.spot.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOf(listOfNotNull(p.spot.location.ifBlank { null }?.let { "위치 $it" }, p.spot.details.ifBlank { null }).joinToString(" · "), p.spot.facilities).filter { it.isNotBlank() && it != FAVORITE }.forEach {
                Text(it.replace(",", ", "), fontSize = 20.sp, color = Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SocStep("지금", car.soc, Modifier.weight(1f)); Text("→", fontSize = 28.sp, color = Sub)
                SocStep("도착", p.arriveSoc, Modifier.weight(1f)); Text("→", fontSize = 28.sp, color = Sub)
                SocStep("아침", p.morningSoc, Modifier.weight(1f))
            }
            p.viaEst?.let { Text("충전소 도착 %d%% → %d%% 충전 · %.1fkWh · 약 %,d원".format(it.atCharger, it.afterCharge, it.kwh, it.cost), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = socColor(it.atCharger), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("밤새 히터 8시간 가정 · ${tempLabel(p.tempC)}" + if (p.viaEst != null) " · 거리 근사" else "", fontSize = 20.sp, color = Sub)
        }
        Cta(listOfNotNull(via?.let { "${it.name} 들렀다" }, parking?.let { "${it.name}으로" }).joinToString(" ").ifEmpty { "내비에서" } + " 경로 보기", vm::go)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("가는 길에", "아침에", "주차장").forEach { Tab(it, tab == it, Modifier.weight(1f)) { tab = it } }
            // 설정은 주차 중에만 (CarUxRestrictions NO_SETUP)
            Secondary("회원 ${members.size}", car.parked, Modifier.weight(1f)) { showMembers = true }
        }
        if (showMembers) MemberDialog(vm, car, members) { showMembers = false }
        if (tab == "주차장") {
            if (p.parkings.isEmpty()) Text("10km 안에 주차장 정보가 없습니다", fontSize = 22.sp, color = Sub)
            else Text("차박 가능 여부는 데이터에 없어 현장 확인 필요", fontSize = 20.sp, color = Sub)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.parkings) { (c, km) ->
                    RowCard(parking == c, { vm.toggleParking(c) }) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(c.addr.ifBlank { null }, c.slots.takeIf { it > 0 }?.let { "${it}면" }, c.fee.ifBlank { null }).joinToString(" · "), color = Sub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text((if (parking == c) "✓ 선택 · " else "") + "%.1fkm".format(km), fontSize = 26.sp, color = if (parking == c) Blue else Ink)
                    }
                }
            }
            return@Column
        }
        if (tab == "가는 길에") {
            if (loading) Text("충전소 불러오는 중", fontSize = 22.sp, color = Sub)
            else if (p.enRoute.isEmpty()) Text("가는 길에 사용 가능한 충전소가 없습니다", fontSize = 22.sp, color = Sub)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.enRoute) { (c, km) -> ChargerRow(c, km, c.operatorId in members, via == c, "여기서부터 ") { vm.toggleVia(c) } }
            }
            return@Column
        }
        if (loading) Text("충전소 불러오는 중", fontSize = 22.sp, color = Sub)
        else if (p.chargers.isEmpty() && car.bookmarks.isEmpty()) Text("10km 안에 사용 가능한 충전소가 없습니다", fontSize = 22.sp, color = Bad)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(car.bookmarks.sortedBy { haversineKm(p.spot.lat, p.spot.lng, it.latitude, it.longitude) }) { b ->
                RowCard(savedName == b.name, { vm.saveMorning(b.name, b.latitude, b.longitude, b.address) }) {
                    Column(Modifier.weight(1f)) {
                        Text("♥ " + b.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("내비 즐겨찾기", color = Sub, fontSize = 19.sp)
                    }
                    Text((if (savedName == b.name) "✓ 선택 · " else "") + "%.1fkm".format(haversineKm(p.spot.lat, p.spot.lng, b.latitude, b.longitude)), fontSize = 26.sp, color = if (savedName == b.name) Blue else Ink)
                }
            }
            items(p.chargers) { (c, km) -> ChargerRow(c, km, c.operatorId in members, savedName == c.name) { vm.saveMorning(c.name, c.lat, c.lng, c.operator) } }
        }
    }
}

@Composable
private fun Tab(text: String, on: Boolean, m: Modifier, onClick: () -> Unit) {
    Button(onClick, m.height(72.dp), shape = Corner, contentPadding = PaddingValues(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Blue else Line),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) Blue else Color.White, contentColor = if (on) Color.White else Ink)) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ChargerRow(c: Charger, km: Double, member: Boolean, selected: Boolean, kmPrefix: String = "", onClick: () -> Unit) {
    RowCard(selected, onClick) {
        Column(Modifier.weight(1f)) {
            Text((if (member) "★ " else "") + c.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.operator} · ${if (c.fast) "급속" else "완속"} · ${PRICE_PER_KWH[c.operatorId] ?: DEFAULT_PRICE}원/kWh · 빈자리 ${c.available}/${c.total}", color = Sub, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text((if (selected) "✓ 선택 · " else "") + kmPrefix + "%.1fkm".format(km), fontSize = if (kmPrefix.isEmpty()) 26.sp else 22.sp, color = if (selected) Blue else Ink)
    }
}

@Composable
fun MemberDialog(vm: MainVm, car: CarState, members: Set<String>, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color.White, shape = Corner,
        confirmButton = { TextButton(onClick = close, Modifier.height(72.dp)) { Text("완료", fontSize = 26.sp, color = Ink) } },
        title = { Text("내 회원 사업자", fontSize = 30.sp, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                items(vm.operatorChoices(car)) { (id, name) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).toggleable(id in members, role = Role.Checkbox) { vm.toggleMember(id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in members, null, colors = CheckboxDefaults.colors(checkedColor = Blue)); Spacer(Modifier.width(16.dp)); Text(name, fontSize = 26.sp)
                    }
                }
            }
        })
}

@Composable
fun CampPane(vm: MainVm, car: CarState, spot: Spot) {
    val saved by vm.savedCharger.collectAsState()
    val temp by vm.campTemp.collectAsState()
    // ponytail: 현재 SoC 에서 8시간치를 빼므로 밤중에 다시 열면 이중 차감. 진입 SoC 저장하면 해결.
    val morning = car.soc - nightDropPct(car.batteryKwh, heaterKw = heaterKw(temp))
    val name = saved?.substringBefore("|")
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(Color.White, Corner).border(1.dp, Line, Corner).padding(24.dp)) {
            Text("차박 모드", fontSize = 22.sp, color = Blue, fontWeight = FontWeight.Bold)
            Text(spot.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SocStep("지금", car.soc, Modifier.weight(1f)); Text("→", fontSize = 28.sp, color = Sub)
                SocStep("아침 예상", morning, Modifier.weight(1f))
            }
            Text("밤새 히터 8시간 가정 · ${tempLabel(temp)}", fontSize = 20.sp, color = Sub)
        }
        Text(if (name == null) "반경 내 충전소 없음" else "아침 충전소  $name · %.1fkm".format(vm.morningKm(spot)), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (name != null) Cta("아침 충전소로 출발", vm::goMorning)
        Text("충전 목표", fontSize = 22.sp, color = Sub)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(80, 90, 100).forEach { pct ->
                val on = car.chargeLimit == pct
                Button({ vm.car.setChargeLimit(pct) }, Modifier.weight(1f).height(76.dp), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = if (on) Blue else Color.White, contentColor = if (on) Color.White else Ink),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Blue else Line)) { Text("$pct%", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Secondary("지도에서 주변 충전소", m = Modifier.weight(1f), onClick = vm.car::openNearbyChargers)
            Secondary("차박 종료", m = Modifier.weight(1f), onClick = vm::exitCamp)
        }
    }
}
