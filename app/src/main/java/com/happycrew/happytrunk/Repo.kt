package com.happycrew.happytrunk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId

/** 공공데이터포털. 키 없거나 실패 → Seed. ponytail: 캐시 없음, 메모리만. 네트워크 끊김 표시는 UI 에서 Seed 여부로. */
object Repo {
    private val key = BuildConfig.DATA_GO_KR_KEY
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * 고캠핑 전체(약 3100건) 중 자동차·카라반 야영장. 서버는 지역·업종 필터가 없고 위치 조회도 거리순이 아니라
     * (80km 1041건 중 임의 100건) 전부 받아 앱에서 거른다. ponytail: 시작마다 재다운로드, 느리면 디스크 캐시.
     */
    suspend fun spots(): List<Spot> = fetch(Seed.spots) {
        (1..10).asSequence().map { items("https://apis.data.go.kr/B551011/GoCamping/basedList?serviceKey=${enc(key)}&MobileOS=AND&MobileApp=HappyTrunk&_type=json&numOfRows=1000&pageNo=$it") }.takeWhile { it.isNotEmpty() }.flatten()
            .filter { it.optString("induty").contains("자동차") || it.optString("induty").contains("카라반") }
            .mapNotNull {
                val lat = it.optString("mapY").toDoubleOrNull(); val lng = it.optString("mapX").toDoubleOrNull()
                if (lat == null || lng == null) null else Spot(it.optString("facltNm"), lat, lng, it.optString("addr1"), it.optString("sbrsCl"), it.optString("lctCl"), listOfNotNull(
                    it.optString("autoSiteCo").toIntOrNull()?.takeIf { n -> n > 0 }?.let { n -> "자동차 사이트 ${n}면" },
                    it.optString("animalCmgCl").ifBlank { null }?.let { v -> "반려동물 $v" },
                    it.optString("brazierCl").ifBlank { null }?.let { v -> "화로대 $v" },
                    "개인 카라반 가능".takeIf { _ -> it.optString("caravAcmpnyAt") == "Y" },
                    it.optString("tel").ifBlank { null },
                ).joinToString(" · "))
            }.toList()
    }

    /** 시군구 단위 조회. 시도 전체는 경기만 15만 행이라 불가. 일부 실패해도 나머지는 사용. */
    suspend fun chargers(regions: List<Region>): List<Charger> = if (key.isBlank()) Seed.chargers else withContext(Dispatchers.IO) {
        coroutineScope {
            regions.map { r -> async { runCatching { items("https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey=${enc(key)}&pageNo=1&numOfRows=9999&zcode=${r.zcode}&zscode=${r.zscode}&dataType=JSON") }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }.filter { it.optString("limitYn") != "Y" && it.optString("delYn") != "Y" } // 아파트 등 입주민 전용 제외
        .groupBy { it.getString("statId") }.values.map { cs ->
            val f = cs.first()
            Charger(f.getString("statNm"), f.getDouble("lat"), f.getDouble("lng"), f.optString("bnm"), f.optString("busiId"),
                fast = cs.any { it.optString("chgerType") in setOf("01", "03", "04", "05", "06", "08", "09") },
                available = cs.count { it.optString("stat") == "2" }, total = cs.size)
        }.ifEmpty { Seed.chargers }
    }

    /** 전국주차장정보 표준데이터. 위치 검색이 없어 공영 전체(약 1.8만 건, 페이지당 최대 1000)를 받아 앱에서 거리 필터. */
    suspend fun parkings(): List<Parking> = fetch(Seed.parkings) {
        (1..30).asSequence().map { items("https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api?serviceKey=${enc(key)}&pageNo=$it&numOfRows=1000&type=json&prkplceSe=${enc("공영")}") }.takeWhile { it.isNotEmpty() }.flatten().toList().mapNotNull {
            val lat = it.optString("latitude").toDoubleOrNull(); val lng = it.optString("longitude").toDoubleOrNull()
            if (lat == null || lng == null) null
            else Parking(it.optString("prkplceNm"), lat, lng, it.optString("rdnmadr").ifBlank { it.optString("lnmadr") }, it.optString("prkcmprt").toIntOrNull() ?: 0, it.optString("parkingchrgeInfo"))
        }
    }

    /** 앞으로 24시간 최저기온(°C). 키 없음/실패 → null (기본 히터 가정) */
    suspend fun nightLow(lat: Double, lng: Double): Double? = if (key.isBlank()) null else withContext(Dispatchers.IO) {
        runCatching {
            val (nx, ny) = kmaGrid(lat, lng)
            val (d, t) = kmaBase(LocalDateTime.now(ZoneId.of("Asia/Seoul")))
            val u = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst?serviceKey=${enc(key)}&pageNo=1&numOfRows=300&dataType=JSON&base_date=$d&base_time=$t&nx=$nx&ny=$ny"
            items(u).filter { it.optString("category") == "TMP" }.take(24).minOfOrNull { it.getDouble("fcstValue") }
        }.getOrNull()
    }

    private fun items(url: String): List<JSONObject> {
        // 고캠핑·기상청 response.body, 주차장 body, 충전소 최상위
        val root = JSONObject(URL(url).readText())
        val body = root.optJSONObject("response")?.optJSONObject("body") ?: root.optJSONObject("body") ?: root
        val arr = body.optJSONArray("items") ?: body.optJSONObject("items")?.optJSONArray("item") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    private suspend fun <T> fetch(fallback: List<T>, block: () -> List<T>): List<T> =
        if (key.isBlank()) fallback else withContext(Dispatchers.IO) { runCatching(block).getOrElse { fallback }.ifEmpty { fallback } }
}
