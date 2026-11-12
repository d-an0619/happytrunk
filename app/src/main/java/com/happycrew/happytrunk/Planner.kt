package com.happycrew.happytrunk

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** 차박지 (고캠핑) */
data class Spot(val name: String, val lat: Double, val lng: Double, val addr: String, val facilities: String, val location: String = "", val details: String = "")

/** 충전소 (환경부). type: 급속/완속, available: 대기중 충전기 수 */
data class Charger(
    val name: String, val lat: Double, val lng: Double, val operator: String, val operatorId: String,
    val fast: Boolean, val available: Int, val total: Int
)

/** 주차장 (전국주차장정보 표준데이터). slots 0 = 정보 없음. 차박 가능 여부는 데이터에 없음 */
data class Parking(val name: String, val lat: Double, val lng: Double, val addr: String, val slots: Int, val fee: String)

/**
 * 원/kWh. 실시간 단가 API 없음 → 정적 테이블. 키 = 환경부 busiId. 충전 속도 구분 없는 대표값 1개.
 * 2026-11-01 대조: ME = 공공 50~100kW 325.6(2026-08-01 고시, 2차 출처), PW = 완속 289.8~319 중 상한.
 * ponytail: 나머지는 사업자 공시로 확인 못한 값. 속도별 단가가 필요하면 (busiId, kW) 키로 교체.
 */
val PRICE_PER_KWH: Map<String, Int> = mapOf(
    "ME" to 326, "HD" to 347, "EP" to 331, "ST" to 347, "SF" to 347, "CV" to 342, "EV" to 338, "PW" to 319, "KP" to 324
)
const val DEFAULT_PRICE = 350

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/**
 * 야간 전력 소모 추정 (SoC %). 문서 8절 3번. heaterKw 는 [heaterKw] 참고.
 */
fun nightDropPct(batteryKwh: Float, hours: Int = 8, heaterKw: Double = 0.8): Int =
    if (batteryKwh <= 0f) 0 else ceil(heaterKw * hours / batteryKwh * 100).toInt()

/**
 * 기온별 히터 전력. 기온 모르면 0.8kW.
 * ponytail: 실측 없는 선형 가정(대기 0.1kW + 18°C 미만 1도당 0.06kW). 냉방 미반영. 실차 데이터 나오면 계수 교체.
 */
fun heaterKw(tempC: Double?): Double = if (tempC == null) 0.8 else 0.1 + (18 - tempC).coerceAtLeast(0.0) * 0.06

/** 위경도 → 기상청 단기예보 격자(nx, ny). 기상청 공식 LCC 변환. */
fun kmaGrid(lat: Double, lng: Double): Pair<Int, Int> {
    val d = PI / 180; val re = 6371.00877 / 5.0
    val s1 = 30 * d; val s2 = 60 * d; val lon0 = 126 * d; val lat0 = 38 * d
    val sn = ln(cos(s1) / cos(s2)) / ln(tan(PI / 4 + s2 / 2) / tan(PI / 4 + s1 / 2))
    val sf = tan(PI / 4 + s1 / 2).pow(sn) * cos(s1) / sn
    val r0 = re * sf / tan(PI / 4 + lat0 / 2).pow(sn)
    val r = re * sf / tan(PI / 4 + lat * d / 2).pow(sn)
    var th = lng * d - lon0
    if (th > PI) th -= 2 * PI
    if (th < -PI) th += 2 * PI
    th *= sn
    return floor(r * sin(th) + 43 + 0.5).toInt() to floor(r0 - r * cos(th) + 136 + 0.5).toInt()
}

/** 단기예보 최신 발표시각(base_date, base_time). 02시부터 3시간 간격, 발표 후 15분 여유. now = KST */
fun kmaBase(now: LocalDateTime): Pair<String, String> {
    val t = now.minusMinutes(15).let { if (it.hour < 2) it.minusDays(1).withHour(23) else it.withHour((it.hour + 1) / 3 * 3 - 1) }
    return t.format(DateTimeFormatter.BASIC_ISO_DATE) to "%02d00".format(t.hour)
}

/**
 * 도착 SoC. 내비 estimatedSocAtDest 는 내비 엔진 자체 SoC 기준(에뮬에선 항상 100%)이라
 * 차량 실제 SoC 에서 내비 소비량(Wh)을 빼서 계산. 용량 모르면 내비 값 그대로.
 */
fun arrivalSoc(soc: Int, batteryKwh: Float, consumptionWh: Double, naviEstimate: Int): Int =
    if (batteryKwh <= 0f) naviEstimate else (soc - consumptionWh / 1000 / batteryKwh * 100).toInt().coerceIn(0, 100)

/** 내 회원 사업자 우선 → 급속 → 단가 → 거리 */
private fun chargerOrder(memberOperatorIds: Set<String>) =
    compareBy<Pair<Charger, Double>> { if (it.first.operatorId in memberOperatorIds) 0 else 1 }
        .thenBy { if (it.first.fast) 0 else 1 }
        .thenBy { PRICE_PER_KWH[it.first.operatorId] ?: DEFAULT_PRICE }
        .thenBy { it.second }

fun rankChargers(chargers: List<Charger>, spot: Spot, memberOperatorIds: Set<String>, radiusKm: Double = 10.0): List<Pair<Charger, Double>> =
    chargers.map { it to haversineKm(spot.lat, spot.lng, it.lat, it.lng) }
        .filter { it.second <= radiusKm && it.first.available > 0 }
        .sortedWith(chargerOrder(memberOperatorIds))

/**
 * 현재 위치 → 스팟 사이 가는 길 충전소. 거리 값은 현재 위치 기준.
 * ponytail: 직선 복도(±corridorKm) 근사. 내비가 실제 경로 좌표를 안 주므로 산길에선 어긋날 수 있음.
 */
fun chargersEnRoute(chargers: List<Charger>, lat: Double, lng: Double, spot: Spot, memberOperatorIds: Set<String>, corridorKm: Double = 10.0): List<Pair<Charger, Double>> {
    val kx = 111.32 * cos(Math.toRadians((lat + spot.lat) / 2)); val ky = 110.57
    val dx = (spot.lng - lng) * kx; val dy = (spot.lat - lat) * ky
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return emptyList()
    return chargers.filter {
        val px = (it.lng - lng) * kx; val py = (it.lat - lat) * ky
        val t = (px * dx + py * dy) / len2
        it.available > 0 && t > 0 && t < 1 && hypot(px - t * dx, py - t * dy) <= corridorKm
    }.map { it to haversineKm(lat, lng, it.lat, it.lng) }.sortedWith(chargerOrder(memberOperatorIds))
}

/** 경유 충전 결과. atCharger = 충전소 도착 SoC, cost = 원 */
data class ViaEstimate(val atCharger: Int, val afterCharge: Int, val arrive: Int, val kwh: Double, val cost: Int)

/**
 * 경유 충전 후 도착 SoC. 충전은 targetPct 까지(이미 넘으면 안 함).
 * ponytail: 내비가 구간별 소비량을 안 주므로 직행 소비량 ÷ 직선거리를 양 구간 직선거리에 곱한 근사.
 */
fun viaEstimate(soc: Int, batteryKwh: Float, directWh: Double, lat: Double, lng: Double, spot: Spot, via: Charger, targetPct: Int): ViaEstimate? {
    val direct = haversineKm(lat, lng, spot.lat, spot.lng)
    if (batteryKwh <= 0f || direct <= 0.0) return null
    fun pct(km: Double) = directWh / direct * km / 1000 / batteryKwh * 100
    val at = (soc - pct(haversineKm(lat, lng, via.lat, via.lng))).toInt().coerceIn(0, 100)
    val after = maxOf(at, targetPct)
    val kwh = (after - at) * batteryKwh / 100.0
    return ViaEstimate(at, after, (after - pct(haversineKm(via.lat, via.lng, spot.lat, spot.lng))).toInt().coerceIn(0, 100), kwh, (kwh * (PRICE_PER_KWH[via.operatorId] ?: DEFAULT_PRICE)).toInt())
}

/**
 * 스팟 주변 + 현재 위치 → 스팟 경로 위 시군구. 15km 간격 지점마다 가까운 2곳, 스팟은 8곳.
 * ponytail: 평균 좌표 기준 근사. 큰 군 경계의 충전소는 빠질 수 있음.
 */
fun regionsAlong(lat: Double, lng: Double, spot: Spot, regions: List<Region> = REGIONS): List<Region> {
    val n = (haversineKm(lat, lng, spot.lat, spot.lng) / 15).toInt().coerceIn(1, 20)
    return (0..n).flatMap { i ->
        val la = lat + (spot.lat - lat) * i / n; val ln = lng + (spot.lng - lng) * i / n
        regions.sortedBy { haversineKm(la, ln, it.lat, it.lng) }.take(if (i == n) 8 else 2) // 큰 군은 평균 좌표가 멀어서 스팟 주변은 넉넉히 (두물머리-양평군 17km)
    }.distinct()
}

/** 지역 칩 → 주소 앞글자. "내 주변"은 전국 거리순 */
val SPOT_REGIONS: Map<String, List<String>> = linkedMapOf(
    "내 주변" to emptyList(), "수도권" to listOf("서울", "경기", "인천"), "강원" to listOf("강원"),
    "충청" to listOf("충청", "충남", "충북", "대전", "세종"),
    "전라" to listOf("전라", "전남", "전북", "광주"), "경상" to listOf("경상", "경남", "경북", "부산", "대구", "울산"),
)

/** 지역 필터 후 가까운 순 limit 개. requestTrip 이 스팟당 1회라 개수 제한 */
fun filterSpots(all: List<Spot>, lat: Double, lng: Double, region: String, limit: Int = 20): List<Spot> {
    val prefixes = SPOT_REGIONS[region].orEmpty()
    return all.filter { s -> prefixes.isEmpty() || prefixes.any { s.addr.startsWith(it) } }
        .sortedBy { haversineKm(lat, lng, it.lat, it.lng) }.take(limit)
}

fun nearbyParkings(parkings: List<Parking>, spot: Spot, radiusKm: Double = 10.0): List<Pair<Parking, Double>> =
    parkings.map { it to haversineKm(spot.lat, spot.lng, it.lat, it.lng) }.filter { it.second <= radiusKm }.sortedBy { it.second }

/** 시드 데이터: API 키 없을 때 / 오프라인. 수도권 근교 차박 명소 + 근처 충전소 */
object Seed {
    val spots = listOf(
        Spot("양평 두물머리 차박지", 37.5329, 127.3122, "경기 양평군 양서면", "화장실,전기"),
        Spot("가평 자라섬 오토캠핑장", 37.8181, 127.5163, "경기 가평군 가평읍", "화장실,샤워장,전기"),
        Spot("당진 왜목마을 해변", 36.9530, 126.5270, "충남 당진시 석문면", "화장실"),
        Spot("강릉 안목해변 차박", 37.7723, 128.9476, "강원 강릉시 견소동", "화장실,편의점"),
        Spot("홍천 비발디파크 인근 차박지", 37.6484, 127.6896, "강원 홍천군 서면", "화장실,전기,샤워장"),
    )
    // 데모용. 좌표 근사, 구획수·요금은 지어내지 않고 비움
    val parkings = listOf(
        Parking("두물머리 공영주차장", 37.5350, 127.3090, "경기 양평군 양서면", 0, ""),
        Parking("양수역 공영주차장", 37.5456, 127.3293, "경기 양평군 양서면", 0, ""),
        Parking("자라섬 공영주차장", 37.8170, 127.5190, "경기 가평군 가평읍", 0, ""),
        Parking("왜목마을 공영주차장", 36.9515, 126.5250, "충남 당진시 석문면", 0, ""),
        Parking("안목해변 공영주차장", 37.7720, 128.9460, "강원 강릉시 견소동", 0, ""),
        Parking("비발디파크 주차장", 37.6470, 127.6880, "강원 홍천군 서면", 0, ""),
    )
    val chargers = listOf(
        Charger("양평군청 공영주차장", 37.4917, 127.4876, "환경부", "ME", true, 2, 2),
        Charger("두물머리 주차장", 37.5345, 127.3080, "현대 E-pit", "HD", true, 1, 4),
        Charger("양서면사무소", 37.5401, 127.3295, "한국전력", "KP", false, 3, 3),
        Charger("자라섬 주차장", 37.8166, 127.5201, "SK일렉링크", "ST", true, 2, 2),
        Charger("가평역", 37.8146, 127.5106, "채비", "CV", true, 0, 2),
        Charger("왜목마을 공영주차장", 36.9510, 126.5241, "환경부", "ME", true, 1, 1),
        Charger("안목해변 주차장", 37.7715, 128.9455, "에버온", "EV", false, 2, 4),
        Charger("강릉시청", 37.7519, 128.8760, "현대 E-pit", "HD", true, 4, 6),
        Charger("비발디파크 메이플", 37.6460, 127.6870, "파워큐브", "PW", false, 5, 10),
        Charger("서면 주민센터", 37.6525, 127.6950, "환경부", "ME", true, 1, 2),
        // 가는 길 데모용 (좌표 근사)
        Charger("하남드림휴게소", 37.5000, 127.2000, "현대 E-pit", "HD", true, 3, 6),
        Charger("남양주 화도", 37.6500, 127.3100, "SK일렉링크", "ST", true, 2, 4),
        Charger("횡성휴게소", 37.5000, 128.0000, "현대 E-pit", "HD", true, 4, 8),
        Charger("행담도휴게소", 36.9300, 126.7500, "환경부", "ME", true, 2, 4),
    )
}
