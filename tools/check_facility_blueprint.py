"""飛行場・泊地の設計図が、コードの前提にしている形の約束を守っているかを確かめる(読むだけ)。

    python tools/check_facility_blueprint.py <設計図のファイル> <施設> <レベル>

施設は airfield(小型飛行場)、mediumairfield、largeairfield、anchorage。約束は
ColonyPatchServer/design/classes-2026-10-14/facility-design-brief.md の「形の約束」:

1. 小屋ブロックが設計図の基準点(primary_offset)にある。
2. 台 1 の床の中心 = 小屋から 東へ 1、下へ 1、北へ(h + 8)。床は中心から各方向に h の正方形で、空気が無い。
3. 台 2(レベル 2 から)= 台 1 から東へ(2h + 5)。
4. 竪穴: 各台の上、床から 2 上より上で、中心から水平に R 以内に、固いブロックが無い。
5. 建物は、台の床から 8 上までに収まる。

h と R は Facility.java と同じ数字(小型 6・8、中型 10・12、大型と泊地 15・17)。
nbtlib が要る。読むだけで、ファイルは書かない。
"""
import math
import sys

import nbtlib

HALF = {"airfield": 6, "mediumairfield": 10, "largeairfield": 15, "anchorage": 15}
# 竪穴の半径: 小型はいまの管(RouteRules.tubeRadius = ceil(hypot(4.5, 3.5) + 2))、中型・大型は級の枠の対角の半分 + 2 を切り上げ。
RADIUS = {"airfield": 8, "mediumairfield": 12, "largeairfield": 17, "anchorage": 17}
GUEST_PAD_LEVEL = 2
# 竪穴の中にあってよいもの(固くないもの)。
SOFT = ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light", "structurize:blocksubstitution",
        "structurize:blocksolidsubstitution")
SOFT_SUFFIX = ("_carpet", "torch", "_button", "_pressure_plate", "rail", "_sign", "_banner")


def blocks_of(path):
    """{(x, y, z): 名前} と、基準点、大きさ。並びは y → z → x、short を 2 個ずつ int に詰めたもの(上位が先)。"""
    bp = nbtlib.load(path)
    sx, sy, sz = int(bp["size_x"]), int(bp["size_y"]), int(bp["size_z"])
    names = [str(entry["Name"]) for entry in bp["palette"]]
    shorts = []
    for value in bp["blocks"]:
        value = int(value) & 0xFFFFFFFF
        shorts.append(value >> 16)
        shorts.append(value & 0xFFFF)
    out = {}
    i = 0
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                name = names[shorts[i]]
                i += 1
                if name not in ("minecraft:air",):
                    out[(x, y, z)] = name
    offset = bp["optional_data"]["structurize"]["primary_offset"]
    anchor = (int(offset["x"]), int(offset["y"]), int(offset["z"]))
    return out, anchor, (sx, sy, sz)


def soft(name):
    return name in SOFT or name.endswith(SOFT_SUFFIX)


def check(path, facility, level):
    h, radius = HALF[facility], RADIUS[facility]
    blocks, (ax, ay, az), (sx, sy, sz) = blocks_of(path)
    faults = []
    hut = blocks.get((ax, ay, az), "minecraft:air")
    if hut != "airline:blockhut" + facility:
        faults.append("1. 基準点 %s にあるのは %s(小屋ブロック airline:blockhut%s のはず)" % ((ax, ay, az), hut, facility))
    if facility == "anchorage":
        return check_pier(blocks, (ax, ay, az), (sx, sy, sz), faults, radius)
    pads = [(ax + 1, ay - 1, az - (h + 8))]
    if level >= GUEST_PAD_LEVEL:
        pads.append((pads[0][0] + 2 * h + 5, pads[0][1], pads[0][2]))
    for n, (px, py, pz) in enumerate(pads, 1):
        holes = []
        for x in range(px - h, px + h + 1):
            for z in range(pz - h, pz + h + 1):
                if not (0 <= x < sx and 0 <= z < sz and 0 <= py < sy) or (x, py, z) not in blocks:
                    holes.append((x - ax, py - ay, z - az))
        if holes:
            faults.append("2/3. 台 %d の床に穴が %d 個(小屋から %s など)" % (n, len(holes), holes[:3]))
        hard = []
        for (x, y, z), name in blocks.items():
            if y < py + 2 or soft(name):
                continue
            if math.hypot(x + 0.5 - (px + 0.5), z + 0.5 - (pz + 0.5)) <= radius + 0.5:  # (as the air network measures it: Tube.touchesColumn)
                hard.append(((x - ax, y - ay, z - az), name))
        if hard:
            faults.append("4. 台 %d の竪穴(半径 %d、床から 2 上より上)に固いブロックが %d 個: %s" % (n, radius, len(hard), hard[:4]))
    top = max(y for (_, y, _) in blocks) if blocks else 0
    floor = pads[0][1]
    if top > floor + 8:
        high = sorted({name for (x, y, z), name in blocks.items() if y > floor + 8})
        faults.append("5. 建物が台の床から %d 上まである(8 まで): %s" % (top - floor, high[:5]))
    return faults, len(blocks), (sx, sy, sz), [(x - ax, y - ay, z - az) for x, y, z in pads]


def check_pier(blocks, anchor, size, faults, radius):
    """泊地(桟橋 1 本): 台は小屋から (9, -2, -20)、床は無い。桟橋の床は台の西 8、1 段上に、大型機の長さぶん続く。
    機体の居る所(台の中心から東西 7・南北 12、桟橋の床の高さから上)は空。竪穴の中は、桟橋の床より上に固いものが無い。"""
    ax, ay, az = anchor
    px, py, pz = ax + 9, ay - 2, az - 20
    gaps = [(px - 8 - ax, py + 1 - ay, z - az) for z in range(pz - 12, pz + 13) if (px - 8, py + 1, z) not in blocks]
    if gaps:
        faults.append("2. 桟橋の床が、機体の脇で途切れている所が %d 個(小屋から %s など)" % (len(gaps), gaps[:3]))
    inside = [((x - ax, y - ay, z - az), name) for (x, y, z), name in blocks.items()
              if px - 7 <= x <= px + 7 and pz - 12 <= z <= pz + 12 and y >= py + 1]
    if inside:
        faults.append("3. 機体の居る所にブロックがある: %s" % inside[:4])
    hard = [((x - ax, y - ay, z - az), name) for (x, y, z), name in blocks.items()
            if y >= py + 2 and not soft(name) and math.hypot(x + 0.5 - (px + 0.5), z + 0.5 - (pz + 0.5)) <= radius]
    if hard:
        faults.append("4. 竪穴(半径 %d、桟橋の床より上)に固いブロックが %d 個: %s" % (radius, len(hard), hard[:4]))
    return faults, len(blocks), size, [(px - ax, py - ay, pz - az)]


def main():
    if len(sys.argv) != 4 or sys.argv[2] not in HALF:
        print(__doc__)
        return 2
    path, facility, level = sys.argv[1], sys.argv[2], int(sys.argv[3])
    faults, count, size, pads = check(path, facility, level)
    print("%s: %s レベル %d、ブロック %d 個、大きさ %s、台(小屋から)%s" % (path, facility, level, count, size, pads))
    for fault in faults:
        print("  ×", fault)
    print("  約束どおり" if not faults else "  約束から外れている所が %d 件" % len(faults))
    return 1 if faults else 0


if __name__ == "__main__":
    sys.exit(main())
