package com.happycrew.happytrunk

import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    @Test fun nightDrop() { assertEquals(9, nightDropPct(77.4f)); assertEquals(0, nightDropPct(0f)) }
    @Test fun arrival() { assertEquals(23, arrivalSoc(30, 77.4f, 4933.8, 94)); assertEquals(94, arrivalSoc(30, 0f, 4933.8, 94)) }
    @Test fun heater() { assertEquals(0.8, heaterKw(null), 0.0); assertEquals(0.1, heaterKw(25.0), 1e-9); assertEquals(1.18, heaterKw(0.0), 1e-9) }
    @Test fun grid() { assertEquals(60 to 127, kmaGrid(37.5665, 126.9780)); assertEquals(98 to 76, kmaGrid(35.1796, 129.0756)) }
    @Test fun base() {
        fun b(h: Int, m: Int, d: Int = 2) = kmaBase(java.time.LocalDateTime.of(2026, 11, d, h, m))
        assertEquals("20261101" to "2300", b(2, 14)); assertEquals("20261102" to "0200", b(2, 15))
        assertEquals("20261102" to "0200", b(5, 14)); assertEquals("20261102" to "0500", b(5, 15))
        assertEquals("20261102" to "2300", b(23, 59)); assertEquals("20261101" to "2300", b(0, 30))
    }
    @Test fun enRoute() {
        fun c(n: String, lat: Double, lng: Double, av: Int = 1) = Charger(n, lat, lng, "x", "x", true, av, 1)
        val spot = Spot("s", 37.0, 128.0, "", "")
        val all = listOf(c("중간", 37.0, 127.5), c("옆길", 37.05, 127.5), c("멀리", 37.5, 127.5), c("뒤쪽", 37.0, 126.9), c("지나침", 37.0, 128.2), c("만석", 37.0, 127.4, 0))
        assertEquals(setOf("중간", "옆길"), chargersEnRoute(all, 37.0, 127.0, spot, emptySet()).map { it.first.name }.toSet())
        assertTrue(chargersEnRoute(all, 37.0, 128.0, spot, emptySet()).isEmpty()) // 이미 도착
    }
    @Test fun via() {
        val spot = Spot("s", 37.0, 128.0, "", ""); val c = Charger("c", 37.0, 127.5, "x", "ME", true, 1, 1)
        val e = viaEstimate(50, 100f, 10000.0, 37.0, 127.0, spot, c, 80)!! // 직행 10% → 구간당 약 5%, 내림(보수적)
        assertEquals(44, e.atCharger); assertEquals(80, e.afterCharge); assertEquals(74, e.arrive)
        assertEquals(36.0, e.kwh, 1e-6); assertEquals(36 * 326, e.cost)
        val full = viaEstimate(95, 100f, 10000.0, 37.0, 127.0, spot, c, 80)!! // 목표 초과 → 충전 없음
        assertEquals(89, full.afterCharge); assertEquals(0, full.cost); assertEquals(83, full.arrive)
        assertNull(viaEstimate(50, 0f, 10000.0, 37.0, 127.0, spot, c, 80))
    }
    @Test fun parkingNearestWithinRadius() {
        val r = nearbyParkings(Seed.parkings, Seed.spots[0]).map { it.first.name }
        assertEquals(listOf("두물머리 공영주차장", "양수역 공영주차장"), r)
    }
    @Test fun regions() {
        val r = regionsAlong(37.45, 127.13, Spot("양평", 37.53, 127.31, "", "")).map { it.zscode }
        assertTrue("41830" in r); assertTrue(r.size <= 20)
        assertTrue("51150" in regionsAlong(37.75, 128.87, Spot("강릉", 37.77, 128.95, "", "")).map { it.zscode })
    }
    @Test fun spotFilter() {
        fun s(n: String, lat: Double, addr: String, f: String = "") = Spot(n, lat, 127.0, addr, f)
        val all = listOf(s("먼경기", 38.0, "경기도 연천군", "전기"), s("가까운경기", 37.5, "경기도 광주시"), s("강원", 37.7, "강원특별자치도 홍천군", "전기"), s("충남", 36.5, "충남 공주시"))
        assertEquals(listOf("가까운경기", "강원", "먼경기"), filterSpots(all, 37.4, 127.0, "내 주변", limit = 3).map { it.name })
        assertEquals(listOf("가까운경기", "먼경기"), filterSpots(all, 37.4, 127.0, "수도권").map { it.name })
        assertEquals(listOf("충남"), filterSpots(all, 37.4, 127.0, "충청").map { it.name })
    }
    @Test fun haversine() { assertEquals(325.0, haversineKm(37.5665, 126.9780, 35.1796, 129.0756), 5.0) }
    @Test fun rankMemberFirstThenFastThenPrice() {
        val spot = Seed.spots[0] // 양평 두물머리
        val r = rankChargers(Seed.chargers, spot, setOf("KP")).map { it.first.name }
        assertEquals(listOf("양서면사무소", "두물머리 주차장"), r) // 회원(완속)이 비회원 급속보다 먼저, 양평군청은 10km 밖
        assertTrue(rankChargers(Seed.chargers, Seed.spots[1], emptySet()).none { it.first.name == "가평역" }) // 대기 0 제외
    }
}
