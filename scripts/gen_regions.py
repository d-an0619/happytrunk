#!/usr/bin/env python3
"""환경부 충전소 전수 스캔 → 시군구 코드별 평균 좌표 표(Regions.kt) 생성. DATA_GO_KR_KEY=... python3 scripts/gen_regions.py"""
import json, os, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
KEY = urllib.parse.quote(os.environ["DATA_GO_KR_KEY"], safe="")
ROWS = 9999
def page(z, p):
    u = f"https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey={KEY}&pageNo={p}&numOfRows={ROWS}&zcode={z}&dataType=JSON"
    for _ in range(4):
        try: return json.load(urllib.request.urlopen(u, timeout=90))
        except Exception as e: err = e
    raise RuntimeError(f"{z}/{p}: {type(err).__name__}")
def scan(z):
    first = page(z, 1)
    pages = [first] + [page(z, p) for p in range(2, first["totalCount"] // ROWS + 2)]
    out = {}
    for d in pages:
        for i in d["items"]["item"]:
            try: la, ln = float(i["lat"]), float(i["lng"])
            except (ValueError, KeyError): continue
            if not (33 < la < 39 and 124 < ln < 132): continue
            a = out.setdefault(i["zscode"], [0, 0.0, 0.0]); a[0] += 1; a[1] += la; a[2] += ln
    print(z, first["totalCount"], len(out), flush=True)
    return {k: (z, v[1] / v[0], v[2] / v[0]) for k, v in out.items()}
zs = [11, 12, 26, 27, 28, 30, 31, 36, 41, 43, 44, 47, 48, 50, 51, 52]
res = {}
with ThreadPoolExecutor(6) as ex:
    for r in ex.map(scan, zs): res.update(r)
rows = "\n".join(f'    Region({z}, "{k}", {la:.3f}, {ln:.3f}),' for k, (z, la, ln) in sorted(res.items()))
open("app/src/main/java/com/happycrew/happytrunk/Regions.kt", "w").write(f"""package com.happycrew.happytrunk

/** scripts/gen_regions.py 생성. 시군구별 충전소 평균 좌표. 환경부 API 는 위치 검색이 없어 좌표 → 시군구 코드 변환에 사용. */
data class Region(val zcode: Int, val zscode: String, val lat: Double, val lng: Double)

val REGIONS = listOf(
{rows}
)
""")
print("regions", len(res))
