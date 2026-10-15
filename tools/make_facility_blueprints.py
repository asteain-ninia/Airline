"""中型飛行場・大型飛行場・泊地の設計図(Structurize の .blueprint)を、ゲームを起動せずに作る。

小型飛行場(tools/make_blueprint.py)と同じ作り方(2026-10-15 のご指示「小型飛行場の時と同じ方法で」)。見た目は
MineColonies の Original パックに似合うもの(2026-10-14 のご裁定 7「完全新規デザインで。Minecolonies Originalに似合うように」)。
材料の移り方は、Original パックの大きめの建物(町役場、倉庫、兵舎、大学など)のレベル 1〜5 を読んで合わせた:

    レベル 1: オークの板と原木、丸石。柵の窓、壁の松明、丸石の階段の軒。
    レベル 2: 窓がガラス板に、軒が石レンガの階段に、松明がランタンに。2 つ目の台ができる。
    レベル 3: トウヒと瓦屋根、石レンガ。 レベル 4: ダークオークと木組み。 レベル 5: レンガと、皮をはいだダークオーク。

いまは、レベル 1 だけを見ていただく段階(2026-10-15)。レベル 2〜5 は、材料を替えただけの下書き。

形の約束(コードの Facility.java と ColonyPatchServer/design/classes-2026-10-14/facility-design-brief.md。小屋を回していない向きで、北が -z):
- 台 1 の床の中心は、小屋から 東へ 1、下へ 1、北へ (h + 8)。台 2(レベル 2 から)は、台 1 から東へ (2h + 5)。h は台の半分の幅。
- 台の上の竪穴: 中心から水平に R 以内は、床から 2 段め以上に何も置かない。建物は床から 8 段めまで。
- 敷地と小屋の位置は、どのレベルも同じ(レベルは改築)。

    北(-z)
    ┌──────────────┬──────────────┐  z=0   柵(高さ 1)
    │   台 1        │   台 2(Lv2)  │        中型 21×21、大型・泊地 31×31
    │              │              │
    ├──────────────┴──────────────┤  竪穴の外
    │  建物(小屋ブロックはこの中) │        中型: 管制所と両脇の荷置き場 / 大型: 長い荷役場 / 泊地: 詰め所だけ
    └─────────────────────────────┘

連結器(Create Aeronautics に同梱の Simulated の `simulated:docking_connector`): 中型飛行場は台の床に(上向き)、大型飛行場と泊地は
台の上の左右に(台の中心を向く)。位置は下の CONNECTORS の数字で、機体の連結器の位置が決まったら直す。いまは伸ばしていない(通電なし)。

使い方: python tools/make_facility_blueprints.py [施設 ...] [--levels 1,2,...] [--show]
施設は mediumairfield、largeairfield、anchorage(書かなければ 3 つとも)。design() は、レビュー用のビューアーも読む。
"""
import math
import os
import sys

import nbtlib
from nbtlib import Byte, Compound, Int, IntArray, List, Short, String

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_blueprint as mb  # noqa: E402  (瓦・木組み・階段の書き方、ブロックの状態の読み方を借りる)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZE_Y = 9
HIGHEST = 8
SHAFT_FREE_FROM = 2
GUEST_PAD_LEVEL = 2
LEVELS = (1, 2, 3, 4, 5)

# 施設ごとの寸法: 台の半分の幅 h、竪穴の半径 R、飛行場か(泊地は棚・回収箱・飛行士の場所を持たない)
FACILITIES = {
    "mediumairfield": {"half": 10, "shaft": 12, "airfield": True, "title": "中型飛行場"},
    "largeairfield": {"half": 15, "shaft": 17, "airfield": True, "title": "大型飛行場"},
    "anchorage": {"half": 15, "shaft": 17, "airfield": False, "title": "泊地"},
    # 泊地の石の別版(2026-10-15 のご指示「桟橋は石タイプも欲しいです。別バリエで、アーチ状の高級目な柱があると良いです。柱の下端は虚空前提で」)。
    # 同じ小屋ブロック・同じ建物で、設計図の名前だけが違う(MineColonies の別版)。
    "anchoragestone": {"half": 15, "shaft": 17, "airfield": False, "title": "泊地(石)"},
}
PILLAR_DEEPEST = 14                             # 石の桟橋の、根元の柱の長さ(床の下から)。先へ行くほど短い
ARCH_DEPTH = PILLAR_DEEPEST + 3                 # 石の桟橋の、床より下の深さ(柱と、垂れた先の塀・鎖・ランタン)
PIER_BEYOND = 21                                # 桟橋の先は、台(機体の真ん中)からこれだけ先まで(大型機の半分の長さ 12 と、8 余分に)
PIER_SPACING = 4                                # 石の桟橋の柱の間
# Domum Ornamentum のパネル(厚さ 3/16 の板)。天井側(half=top、閉じた形)に置いて、弧の細かい段にする。材料は石レンガ。
STONE_PANEL = "domum_ornamentum:panel[facing=north,half=top,open=false,type=full,waterlogged=false]|minecraft:block/oak_planks=minecraft:stone_bricks"
PIERS = ("anchorage", "anchoragestone")
# 連結器の位置(台の中心から)。床置き: 上向き、台の中心から南へ 3(機体の下部連結器の位置が決まったら直す)。
# 横型: 台の床の 1 段上、台の中心から東西へ 8(大型機の胴の幅 15 のすぐ外。中心を向く)。
CONNECTORS = {
    "mediumairfield": [((0, 0, 3), "up")],
    "largeairfield": [((-8, 1, 0), "east"), ((8, 1, 0), "west")],
    "anchorage": [((-8, 1, 0), "east"), ((8, 1, 0), "west")],
}
CONNECTOR = "simulated:docking_connector[extended=false,facing=%s,powered=false]"


class Site:
    """施設の敷地の寸法。台 1 は、竪穴がちょうど敷地の北西の角に収まる所。"""

    def __init__(self, name):
        spec = FACILITIES[name]
        self.name = name
        self.h, self.r, self.airfield = spec["half"], spec["shaft"], spec["airfield"]
        if name in PIERS:
            # 泊地は桟橋 1 本(2026-10-15 のご裁定)。係船柱(小屋ブロック)が根元に立ち、桟橋は北へ空中に突き出す。
            # 機体は桟橋の東に、空中で横付け。「台」は大型機の機体ブロックの 1 つ下(床は無い): 小屋から (9, -2, -20)。
            # 設計図の箱は、竪穴(台の中心から半径 17)の東西南北と、根元まで。高さは 4(桟橋の裏・桟橋・係船柱・松明)。
            below = ARCH_DEPTH if name == "anchoragestone" else 0
            # 北の縁は、竪穴(半径 17)と桟橋の先の、遠い方まで
            self.pad1 = (self.r, below, max(self.r, PIER_BEYOND))
            self.pad2 = self.pad1
            self.anchor = (self.pad1[0] - 9, below + 2, self.pad1[2] + 20)
            self.size = (2 * self.r + 1, below + 4, self.anchor[2] + 1)
            self.north_end = self.anchor[2]
            return
        self.pad1 = (self.r, 0, self.r)
        self.pad2 = (self.r + 2 * self.h + 5, 0, self.r)
        self.anchor = (self.pad1[0] - 1, 1, self.pad1[2] + self.h + 8)
        self.size = (self.pad2[0] + self.r + 1, SIZE_Y, self.anchor[2] + 8)
        self.north_end = self.pad1[2] + self.r          # 竪穴の南の端(ここまでが台の側)

    def pads(self, level):
        if self.name in PIERS:
            return [self.pad1]
        return [self.pad1] + ([self.pad2] if level >= GUEST_PAD_LEVEL else [])


def anchorage_design(mod_id="airline", name="anchorage"):
    """泊地(桟橋 1 本、レベル 1 だけ)。返すのは design() と同じ形(棚と回収箱は無い)。name が anchoragestone なら石の別版。"""
    site = Site(name)
    stone = name == "anchoragestone"
    sx, sy, sz = site.size
    b = {}
    px, py, pz = site.pad1
    ax, ay, az = site.anchor
    deck = py + 1                                                       # 桟橋の床(機体の甲板と同じ高さ)

    def put(x, y, z, state):
        assert 0 <= x < sx and 0 <= y < sy and 0 <= z < sz, (name, x, y, z)
        b[(x, y, z)] = state

    tip = pz - PIER_BEYOND
    if not stone:
        # 桟橋: 幅 3(係船柱の列を中心に)、根元から北へ、大型機の長さと少しを越えるまで
        for z in range(tip, az + 1):
            for x in range(ax - 1, ax + 2):
                put(x, deck, z, mb.PLANKS)
        for z in range(az - 2, az + 1):                                 # 根元の踊り場(島の上)
            for x in range(ax - 2, ax + 3):
                put(x, deck, z, mb.COBBLE)
        for z in range(tip + 2, az - 2, 6):                             # 桟橋の裏の梁と、吊ったランタン
            for x in range(ax - 1, ax + 2):
                put(x, py, z, mb.LOG % "x")
        for z in range(tip + 5, az - 2, 6):
            put(ax, py, z, mb.LANTERN % "true")
        for x in (ax - 2, ax + 2):                                      # 踊り場の柵の柱と松明(竪穴の外)
            put(x, ay, az, mb.FENCE)
            put(x, ay + 1, az, "minecraft:torch")
    else:
        # 石の桟橋: 床は石レンガ。床の下は 4 ブロックおきの柱と、その間の小さな弧。
        # 柱は根元がいちばん長く、根元のすぐ先で急に短くなり、先のほうはなだらかに床へ寄る。柱の先を結ぶと、
        # 根元から跳ね上がって桟橋の下に沿っていく 4 分の 1 の楕円になる(2026-10-15 のご意見「根本の足をさらに長く、
        # 外側に行くにつれて短く…足の先端を取ったときに、それらがちょうど半アーチを描く」「もっと根元の方での減少が急激に」)。
        # 柱の間の弧は、柱の脇に逆さの階段、真ん中に天井側のパネル(Domum Ornamentum)で、細かい段にする
        # (同じ日のご意見「Domumのパネル使ったらもっと滑らかなアーチに出来ませんか？」)。
        # 柱の先は細い塀になり、鎖で吊ったランタンで終わる(下は虚空のまま)。
        brick, chiseled, wall = mb.STONE_BRICKS, "minecraft:chiseled_stone_bricks", "minecraft:stone_brick_wall"
        for z in range(tip, az + 1):
            for x in range(ax - 1, ax + 2):
                put(x, deck, z, brick)
        for z in range(az - 2, az + 1):                                 # 根元の踊り場(島の上)、市松
            for x in range(ax - 2, ax + 3):
                put(x, deck, z, mb.DEEPSLATE if (x + z) % 2 else brick)
        pillars = list(range(az - 3, tip, -PIER_SPACING))               # 柱の位置(根元から北へ)
        span = (az - 3) - tip                                           # 楕円の横の半径: 根元の柱から桟橋の先まで
        lengths = {}
        for at in pillars:
            along = (az - 3) - at
            # 楕円の中心は (桟橋の先, 根元の柱の先の深さ)。根元では真下へ落ち、先では床と平らに並ぶ。
            lengths[at] = max(1, int(round(PILLAR_DEEPEST * (1.0 - math.sqrt(max(0.0, 1.0 - (1.0 - along / span) ** 2))))))
            for x in range(ax - 1, ax + 2):
                for y in range(deck - lengths[at], deck):
                    put(x, y, at, chiseled if (deck - y) % 4 == 3 else brick)
            put(ax, deck - lengths[at] - 1, at, wall)                   # 垂れた先: 塀、鎖、ランタン
            put(ax, deck - lengths[at] - 2, at, "minecraft:chain[axis=y,waterlogged=false]")
            put(ax, deck - lengths[at] - 3, at, mb.LANTERN % "true")
        for south, north in zip(pillars, pillars[1:]):                  # 柱の間: 床のすぐ下の梁と、なめらかな小さな弧
            deep = min(lengths[south], lengths[north]) >= 2
            for x in range(ax - 1, ax + 2):
                for z in range(north + 1, south):
                    put(x, deck - 1, z, brick)
                if not deep:
                    continue
                put(x, deck - 2, south - 1, mb.stairs("south", "top", "stone_brick"))   # 柱の脇: 逆さの階段(45 度)
                put(x, deck - 2, north + 1, mb.stairs("north", "top", "stone_brick"))
                for z in range(north + 2, south - 1):                                   # 真ん中: 天井側のパネル(3/16)
                    put(x, deck - 2, z, STONE_PANEL)
            if deep:
                put(ax, deck - 3, (south + north) // 2, mb.LANTERN % "true")   # 弧の頂から吊ったランタン
        for x in (ax - 2, ax + 2):                                      # 踊り場の灯籠(竪穴の外): 塀の柱にランタン
            put(x, ay, az, wall)
            put(x, ay + 1, az, mb.LANTERN % "false")
    put(ax + 1, deck, pz, CONNECTOR % "east")                          # 横型の連結器(桟橋の東の縁、機体の脇)
    put(ax, ay, az, mod_id + ":blockhutanchorage[facing=north]")       # 係船柱(小屋ブロック)
    # 柵と塀のつながり
    for (x, y, z), state in list(b.items()):
        if state == "minecraft:stone_brick_wall":
            b[(x, y, z)] = state + "[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"
            continue
        if state == mb.FENCE:
            sides = {"north": (x, y, z - 1), "south": (x, y, z + 1), "west": (x - 1, y, z), "east": (x + 1, y, z)}
            b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "true" if b.get(spot, "").split("[")[0] in mb.FULL else "false") for side, spot in sorted(sides.items())) + ",waterlogged=false]"
    # 約束: 竪穴(台の中心から半径 17)の中は、桟橋の床より上に何も無い。台の上(機体の居る所)は空。
    for (x, y, z), state in b.items():
        assert not (y >= py + 2 and math.hypot(x - px, z - pz) <= site.r), (name + ": in the shaft", state, (x, y, z))
    for x in range(px - 7, px + 8):
        for z in range(pz - 12, pz + 13):
            for y in range(deck, sy):
                assert (x, y, z) not in b, (name + ": something where the aircraft is held", (x, y, z))
    return b, [], None


def design(name, level=1, mod_id="airline"):
    """座標 (x, y, z) → ブロックの状態。返すのは (ブロック, 棚の位置, 回収箱の位置か None)。"""
    if name in PIERS:
        return anchorage_design(mod_id, name)
    site = Site(name)
    sx, _, sz = site.size
    east = sx - 1
    b = {}
    improved = level >= 2
    tiled = level >= 3
    wood = ("oak", "oak", "spruce", "dark_oak", "dark_oak")[level - 1]
    planks = "minecraft:%s_planks" % wood
    log = "minecraft:stripped_dark_oak_log[axis=%s]" if level >= 5 else "minecraft:" + wood + "_log[axis=%s]"
    fence = "minecraft:%s_fence" % wood
    floor = mb.COBBLE if level <= 2 else mb.STONE_BRICKS if level <= 4 else mb.DEEPSLATE
    border = "minecraft:brick_wall" if level >= 5 else fence
    plate = mb.BRICKS if level >= 5 else planks
    course = mb.BEIGE if level >= 5 else planks
    apron = mb.COBBLE if level <= 3 else mb.STONE_BRICKS
    mark = mb.COBBLE if level <= 2 else mb.STONE_BRICKS if level == 3 else mb.DEEPSLATE
    frame_wood = "minecraft:stripped_dark_oak_wood" if level >= 5 else "minecraft:dark_oak_planks"
    frame_centre = "minecraft:smooth_quartz" if level >= 5 else mb.PAPER
    torch_on = (lambda facing: mb.LANTERN % "false") if improved else (lambda facing: "minecraft:wall_torch[facing=%s]" % facing)

    def put(x, y, z, state):
        assert 0 <= x < sx and 0 <= y < SIZE_Y and 0 <= z < sz, (name, x, y, z)
        b[(x, y, z)] = state

    def box(x0, y0, z0, x1, y1, z1, state):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    put(x, y, z, state)

    def ring(x0, z0, x1, z1, y, state_of):
        for x in range(x0, x1 + 1):
            put(x, y, z0, state_of("south"))
            put(x, y, z1, state_of("north"))
        for z in range(z0 + 1, z1):
            put(x0, y, z, state_of("east"))
            put(x1, y, z, state_of("west"))

    def roof_ring(x0, z0, x1, z1, y, piece):
        ring(x0, z0, x1, z1, y, lambda facing: piece(facing, False))
        if tiled:
            put(x0, y, z0, piece("east", True))
            put(x1, y, z0, piece("south", True))
            put(x1, y, z1, piece("west", True))
            put(x0, y, z1, piece("north", True))

    def eaves_piece(facing, corner):
        if tiled:
            return mb.shingle("shingle_flat", facing, corner, mb.STONE_BRICKS)
        return mb.stairs(facing, kind="stone_brick" if improved else "cobblestone")

    def slope_piece(facing, corner):
        return mb.shingle("shingle", facing, corner, planks) if tiled else mb.stairs(facing)

    def roof_top(x0, z0, x1, z1, y):
        if not tiled:
            box(x0, y, z0, x1, y, z1, planks)
        elif z0 == z1:
            for x in range(x0, x1 + 1):
                put(x, y, z0, mb.ridge("two_way", "east", planks))
            put(x0, y, z0, mb.ridge("one_way", "west", planks))
            put(x1, y, z0, mb.ridge("one_way", "east", planks))
        else:
            assert z1 == z0 + 1, (name, "the top of a tiled roof is one or two rows", z0, z1)
            for x in range(x0 + 1, x1):
                put(x, y, z0, mb.ridge("three_way", "south", planks))
                put(x, y, z1, mb.ridge("three_way", "north", planks))
            put(x0, y, z0, mb.ridge("curved", "north", planks))
            put(x1, y, z0, mb.ridge("curved", "east", planks))
            put(x0, y, z1, mb.ridge("curved", "west", planks))
            put(x1, y, z1, mb.ridge("curved", "south", planks))

    def hip_roof(x0, z0, x1, z1, y, steps):
        """寄棟屋根。(x0..x1, z0..z1) は壁の外周。軒は壁から 1 ブロック張り出す。"""
        roof_ring(x0 - 1, z0 - 1, x1 + 1, z1 + 1, y, eaves_piece)
        ring(x0, z0, x1, z1, y, lambda facing: course)
        for i in range(steps):
            roof_ring(x0 + i, z0 + i, x1 - i, z1 - i, y + 1 + i, slope_piece)
            if i + 1 < steps:
                ring(x0 + i + 1, z0 + i + 1, x1 - i - 1, z1 - i - 1, y + 1 + i, lambda facing: planks)
        roof_top(x0 + steps, z0 + steps, x1 - steps, z1 - steps, y + steps)

    def walls(x0, z0, x1, z1, top, band_from, lower, front=False):
        """柱の間の壁。奥(z1)と両側。front なら前(z0)も。band_from 段めから上は、レベル 4・5 では木組み。"""
        for y in range(1, top + 1):
            framed = level >= 4 and y >= band_from
            for x in range(x0 + 1, x1):
                put(x, y, z1, mb.frame("south", frame_wood, frame_centre) if framed else lower)
                if front:
                    put(x, y, z0, mb.frame("north", frame_wood, frame_centre) if framed else lower)
            for z in range(z0 + 1, z1):
                put(x0, y, z, mb.frame("west", frame_wood, frame_centre) if framed else lower)
                put(x1, y, z, mb.frame("east", frame_wood, frame_centre) if framed else lower)

    def posts(xs, zs, top):
        for x in xs:
            for z in zs:
                box(x, 1, z, x, top, z, log % "y")

    def in_shaft(x, y, z):
        return y >= SHAFT_FREE_FROM and any(math.hypot(x - cx, z - cz) <= site.r for cx, _, cz in site.pads(level))

    def pad_floor(cx, cz):
        h = site.h
        box(cx - h, 0, cz - h, cx + h, 0, cz + h, planks)
        if level >= 3:
            for i in range(-h, h + 1):
                put(cx + i, 0, cz - h, mark)
                put(cx + i, 0, cz + h, mark)
                put(cx - h, 0, cz + i, mark)
                put(cx + h, 0, cz + i, mark)
        for i in range(-h + 1, h):                                      # 台の中心を示す十字
            put(cx + i, 0, cz, mark)
            put(cx, 0, cz + i, mark)
        for i in (-h // 2, h // 2):                                     # 大きい台は、半分の所にも短い印
            for j in range(-1, 2):
                put(cx + i, 0, cz + j, mark)
                put(cx + j, 0, cz + i, mark)
        for (dx, dy, dz), facing in CONNECTORS[name]:                   # 連結器
            if dy == 0:
                put(cx + dx, 0, cz + dz, CONNECTOR % facing)
            else:
                put(cx + dx, 0, cz + dz, floor)                         # 横型の連結器の台座(床の 1 段)
                put(cx + dx, dy, cz + dz, CONNECTOR % facing)

    def future_pad(cx, cz):
        """まだ無い台の予定地: 土の道でふちどり、資材を積む。"""
        h = site.h
        for i in range(-h, h + 1):
            put(cx + i, 0, cz - h, mb.PATH)
            put(cx + i, 0, cz + h, mb.PATH)
            put(cx - h, 0, cz + i, mb.PATH)
            put(cx + h, 0, cz + i, mb.PATH)

    p1x, _, p1z = site.pad1
    p2x, _, p2z = site.pad2
    ax, ay, az = site.anchor
    split = p1x + site.r                                                # 台 1 の側と台 2 の側の境
    north = site.north_end                                              # ここまでが台の側(竪穴の南の端)

    # ---- 地面 ----
    if site.airfield:
        box(0, 0, 0, split, 0, north, apron)                            # 台 1 のまわりの前庭
    else:
        box(0, 0, 0, split, 0, north, planks)                           # 泊地: 板の桟橋
    pad_floor(p1x, p1z)
    box(0, 0, north + 1, east, 0, sz - 1, mb.PATH)                      # 南の通り道
    if improved:
        box(split + 1, 0, 0, east, 0, north, apron if site.airfield else planks)
        pad_floor(p2x, p2z)
    else:
        box(split + 1, 0, 0, east, 0, north, mb.DIRT)                   # 台 2 の予定地は、まだ空き地
        future_pad(p2x, p2z)

    # ---- 北の三方を囲む柵(高さ 1)と、竪穴の外の明かり ----
    for x in range(0, sx):
        put(x, 1, 0, border)
    for z in range(0, north + 1):
        put(0, 1, z, border)
        put(east, 1, z, border)
    if not site.airfield:                                               # 泊地: 南の縁にも、係留の杭(原木)を並べる
        for x in range(2, sx - 1, 6):
            if not in_shaft(x, 1, north):
                put(x, 1, north, log % "y")
    for x, z in [(0, 0), (east, 0), (0, north), (east, north), (split, 0), (split + 1, 0)] + [(x, 0) for x in range(8, sx - 4, 8)] + \
                [(0, z) for z in range(8, north, 8)] + [(east, z) for z in range(8, north, 8)]:
        if in_shaft(x, 2, z):
            continue
        if improved:
            put(x, 1, z, log % "y")
            put(x, 2, z, mb.LANTERN % "false")
        else:
            put(x, 2, z, "minecraft:torch")

    racks, stash = [], None

    def guest_shed(g0, g1, gz0, gz1):
        """来客の荷置き場(レベル 2 から)。柱と低い屋根、奥の壁。レベル 3 から三方に壁。棚はレベルごとに 2 つずつ。"""
        box(g0, 0, gz0, g1, 0, gz1, floor)
        posts((g0, g1), (gz0, gz1), 3)
        if tiled:
            walls(g0, gz0, g1, gz1, 3, 3, mb.BEIGE if level >= 5 else planks)
            for z in range(gz0 + 1, gz1):
                if (z - gz0) % 2 == 0:
                    put(g0, 2, z, mb.PANE)
                    put(g1, 2, z, mb.PANE)
        else:
            box(g0 + 1, 1, gz1, g1 - 1, 2, gz1, planks)
        for x in range(g0 + 1, g1):                                     # 前は、いちばん上の段だけ板を渡す
            put(x, 3, gz0, planks)
        hip_roof(g0, gz0, g1, gz1, 4, 2)
        for i in range(2 * (level - 1)):
            spot = (g0 + 1 + i, 1, gz1 - 1)
            put(*spot, mb.RACK % "north")
            racks.append(spot)
        cx = (g0 + g1) // 2
        for x in (cx + 2, cx + 3):                                      # 長いす(北を向いて座る)
            put(x, 1, gz0 + 2, mb.stairs("south", kind=wood))
        put(cx, 4, gz0 + 2, mb.LANTERN % "true")                       # 屋根の裏から下げたランタン
        put(g0, 2, gz0 - 1, torch_on("north"))
        put(g1, 2, gz0 - 1, torch_on("north"))

    # ---- 建物 ----
    if name == "mediumairfield":
        # 管制所(中央。小屋ブロック、飛行士の机、扉)と、両脇の屋根つきの荷置き場(棚と回収箱)。
        z0, z1 = north + 2, north + 9                                   # 奥行き 8(寄棟の頂が 2 列)
        hx0, hx1 = ax - 5, ax + 5                                       # 管制所の幅 11
        top = 4
        box(hx0, 0, z0, hx1, 0, z1, plate)
        box(hx0 + 1, 0, z0 + 1, hx1 - 1, 0, z1 - 1, floor)
        walls(hx0, z0, hx1, z1, top, 3, mb.BRICKS if level >= 5 else planks, front=True)
        posts((hx0, ax - 2, ax + 2, hx1), (z0, z1), top)
        for x in (ax - 1, ax, ax + 1):                                  # 台の側の大きな戸口(3 マス)
            box(x, 1, z0, x, 2, z0, "minecraft:air")
        for x in (hx0 + 2, hx0 + 3, hx1 - 3, hx1 - 2):                  # 前の窓
            put(x, 2, z0, mb.PANE if improved else mb.FENCE)
        for z in (z0 + 3, z0 + 4):                                      # 横の窓
            put(hx0, 2, z, mb.PANE if improved else mb.FENCE)
            put(hx1, 2, z, mb.PANE if improved else mb.FENCE)
        hip_roof(hx0, z0, hx1, z1, top + 1, 3)
        put(ax, ay, az, mod_id + ":blockhut" + name + "[facing=north]")
        put(ax - 3, 1, z1 - 2, fence)                                   # 机といす
        put(ax - 3, 2, z1 - 2, "minecraft:%s_pressure_plate[powered=false]" % wood)
        put(ax - 4, 1, z1 - 2, mb.stairs("east", kind=wood))
        put(ax - 2, 1, z1 - 2, mb.stairs("west", kind=wood))
        put(ax + 3, 1, z1 - 1, "minecraft:crafting_table")
        put(ax, 1, z1, "minecraft:%s_door[facing=south,half=lower,hinge=left,open=false,powered=false]" % wood)
        put(ax, 2, z1, "minecraft:%s_door[facing=south,half=upper,hinge=left,open=false,powered=false]" % wood)
        for x in (hx0, hx1):                                            # 外の明かり(台の側の柱)
            put(x, 2, z0 - 1, torch_on("north"))
        # 東の屋根つきの荷置き場(柱と低い屋根。前は開いている): 棚と回収箱、積んだ樽
        s0, s1 = hx1 + 2, hx1 + 10
        box(s0, 0, z0 + 1, s1, 0, z1 - 1, floor)
        posts((s0, s0 + 4, s1), (z0 + 1, z1 - 1), 3)
        box(s0 + 1, 1, z1 - 1, s1 - 1, 2, z1 - 1, planks)               # 奥の壁(低い)
        hip_roof(s0, z0 + 1, s1, z1 - 1, 4, 2)
        for i in range(4):
            spot = (s0 + 1 + i, 1, z1 - 2)
            put(*spot, mb.RACK % "north")
            racks.append(spot)
        stash = (s1 - 1, 1, z1 - 2)
        put(*stash, "minecolonies:blockstash[facing=north]")
        for x in (s1 - 3, s1 - 2):                                      # 積んだ樽(荷置き場らしく)
            put(x, 1, z1 - 2, "minecraft:barrel[facing=up,open=false]")
        put(s0 + 4, 2, z0, torch_on("north"))
        if not improved:                                                # 台 2 の予定地の南に、資材を積む
            for x in (p2x - 1, p2x, p2x + 1):
                put(x, 1, z0 + 2, log % "x")
                put(x, 1, z0 + 3, log % "x")
            put(p2x, 2, z0 + 2, log % "x")
        # 棚は、レベルごとに 2 つずつ増える(前のレベルの棚は、同じ場所に残る)
        more = []
        if level >= 2:
            more += [((s0 + 1, 1, z0 + 3), "south"), ((s0 + 2, 1, z0 + 3), "south")]
        if level >= 3:
            more += [((s0 + 3, 1, z0 + 3), "south"), ((s0 + 4, 1, z0 + 3), "south")]
        if level >= 4:
            more += [((hx1 - 1, 1, z1 - 2), "west"), ((hx1 - 1, 1, z1 - 3), "west")]
        if level >= 5:
            more += [((hx1 - 1, 1, z1 - 4), "west"), ((hx1 - 1, 1, z1 - 5), "west")]
        for spot, facing in more:
            put(*spot, mb.RACK % facing)
            racks.append(spot)
        if improved:
            # レベル 2 から: 台 2 の南に、来客の荷置き場(レベル 3 から三方に壁)。棚は 2・4・6・8。
            guest_shed(p2x - 5, p2x + 5, z0 + 1, z1 - 1)
        if level >= 4:
            # 管制所と荷置き場の間の、屋根つきの渡り
            box(hx1 + 1, 0, z0 + 2, s0 - 1, 0, z1 - 2, floor)
            box(hx1 + 1, 3, z0 + 2, s0 - 1, 3, z1 - 2, "minecraft:%s_slab[type=bottom,waterlogged=false]" % wood)

    elif name == "largeairfield":
        # 長い荷役場(寄棟屋根の大きな建物)。中央に小屋ブロック、西に棚、東に回収箱。前は柱だけ。
        z0, z1 = north + 2, north + 9
        hx0, hx1 = ax - 14, ax + 14                                     # 幅 29
        top = 4
        box(hx0, 0, z0, hx1, 0, z1, plate)
        box(hx0 + 1, 0, z0, hx1 - 1, 0, z1 - 1, floor)
        walls(hx0, z0, hx1, z1, top, 3, mb.BRICKS if level >= 5 else planks)
        cols = list(range(hx0, hx1 + 1, 7))                             # 柱は 7 ブロックおき
        posts(cols, (z0, z1), top)
        for x in range(hx0 + 1, hx1):                                   # 前は、いちばん上の段だけ板を渡す
            if x not in cols:
                put(x, top, z0, planks)
        for x in (hx0 + 3, hx0 + 4, hx1 - 4, hx1 - 3, ax - 4, ax + 4):  # 奥の窓
            put(x, 2, z1, mb.PANE if improved else mb.FENCE)
        hip_roof(hx0, z0, hx1, z1, top + 1, 3)
        put(ax, ay, az, mod_id + ":blockhut" + name + "[facing=north]")
        for i in range(6):                                              # 西の棚 6 つ
            spot = (hx0 + 2 + i, 1, z1 - 1)
            put(*spot, mb.RACK % "north")
            racks.append(spot)
        stash = (hx1 - 2, 1, z1 - 1)
        put(*stash, "minecolonies:blockstash[facing=north]")
        put(ax + 3, 1, z1 - 1, fence)                                   # 机といす
        put(ax + 3, 2, z1 - 1, "minecraft:%s_pressure_plate[powered=false]" % wood)
        put(ax + 2, 1, z1 - 1, mb.stairs("east", kind=wood))
        put(ax + 4, 1, z1 - 1, mb.stairs("west", kind=wood))
        put(ax - 3, 1, z1 - 1, "minecraft:crafting_table")
        put(ax, 1, z1, "minecraft:%s_door[facing=south,half=lower,hinge=left,open=false,powered=false]" % wood)
        put(ax, 2, z1, "minecraft:%s_door[facing=south,half=upper,hinge=left,open=false,powered=false]" % wood)
        for x in (hx1 - 6, hx1 - 5, hx1 - 4):                           # 積んだ樽
            put(x, 1, z1 - 1, "minecraft:barrel[facing=up,open=false]")
            put(x, 2, z1 - 1, "minecraft:barrel[facing=up,open=false]")
        for x in cols:                                                  # 柱の明かり(台の側)
            put(x, 2, z0 - 1, torch_on("north"))
        # 台 2 の側(東)の予定地にも、資材を積む
        if not improved:
            for x in (p2x - 1, p2x, p2x + 1):
                put(x, 1, z0 + 3, log % "x")
                put(x, 1, z0 + 4, log % "x")
            put(p2x, 2, z0 + 3, log % "x")
        # 棚は、レベルごとに 2 つずつ増える
        more = []
        if level >= 2:
            more += [(hx0 + 8, 1, z1 - 1), (hx0 + 9, 1, z1 - 1)]
        for lv, first in ((3, 2), (4, 4), (5, 6)):
            if level >= lv:
                more += [(hx0 + first, 1, z1 - 2), (hx0 + first + 1, 1, z1 - 2)]
        for spot in more:
            put(*spot, mb.RACK % "north")
            racks.append(spot)
        if improved:
            # レベル 2 から: 台 2 の南に、来客の荷役場。棚は 2・4・6・8。
            guest_shed(p2x - 7, p2x + 7, z0 + 1, z1 - 1)
        if level >= 4:
            # 荷役場と来客の荷役場の間の、屋根つきの渡り(柱は 3 ブロックおき)
            w0, w1 = hx1 + 1, p2x - 8
            box(w0, 0, z0 + 3, w1, 0, z0 + 4, floor)
            for x in range(w0, w1 + 1, 3):
                for z in (z0 + 2, z0 + 5):
                    box(x, 1, z, x, 2, z, fence)
            box(w0, 3, z0 + 2, w1, 3, z0 + 5, "minecraft:%s_slab[type=bottom,waterlogged=false]" % wood)

    # ---- 柵とガラス板のつながり(make_blueprint と同じ決まり) ----
    def joins(spot, fences):
        other = b.get(spot)
        if other is None:
            return False
        nm = other.split("[")[0].split("|")[0]
        return nm in mb.FULL or (nm.endswith("_fence") if fences else nm == mb.PANE)

    for (x, y, z), state in list(b.items()):
        sides = {"north": (x, y, z - 1), "south": (x, y, z + 1), "west": (x - 1, y, z), "east": (x + 1, y, z)}
        if state == "minecraft:brick_wall":
            linked = {side: (b.get(spot, "").split("[")[0].split("|")[0] in mb.FULL + ("minecraft:brick_wall",)) for side, spot in sides.items()}
            straight = (linked["north"] and linked["south"] and not linked["east"] and not linked["west"]) or (linked["east"] and linked["west"] and not linked["north"] and not linked["south"])
            up = not straight or (x, y + 1, z) in b
            b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "low" if linked[side] else "none") for side in sorted(sides)) + ",up=%s,waterlogged=false]" % ("true" if up else "false")
            continue
        if state == "minecraft:air":
            del b[(x, y, z)]
            continue
        if state != mb.PANE and not (state.endswith("_fence") and "[" not in state):
            continue
        b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "true" if joins(spot, state != mb.PANE) else "false") for side, spot in sorted(sides.items())) + ",waterlogged=false]"

    # ---- 約束の確かめ ----
    for (x, y, z), state in b.items():
        assert not in_shaft(x, y, z), "%s: in the shaft: %s at %s" % (name, state, (x, y, z))
        assert y <= HIGHEST, (name, state, (x, y, z))
    for cx, _, cz in site.pads(level):
        for x in range(cx - site.h, cx + site.h + 1):
            for z in range(cz - site.h, cz + site.h + 1):
                assert (x, 0, z) in b, "%s: a hole in the pad's floor at %s" % (name, (x, 0, z))
    assert b.get(site.anchor, "").startswith(mod_id + ":blockhut" + name), (name, "the hut block is not at the anchor")
    return b, racks, stash


def check_levels(name, lower, upper):
    """改築で、棚・回収箱・小屋が同じ場所にある。"""
    site = Site(name)
    blocks, racks, stash = lower
    after = upper[0]
    for spot in racks + ([stash] if stash else []) + [site.anchor]:
        assert after.get(spot) == blocks[spot], "%s: the next level moves %s at %s (it has %s there)" % (name, blocks[spot], spot, after.get(spot))


def write(name, level, blocks, stash, mod_id="airline"):
    site = Site(name)
    sx, sy, sz = site.size
    schematic = "%s%d" % (name, level)
    out = os.path.join(ROOT, "src", "main", "resources", "blueprints", mod_id, "airfield", "infrastructure", schematic + ".blueprint")
    palette, index = [Compound({"Name": String("minecraft:air")})], {}
    for state in blocks.values():
        if state not in index:
            index[state] = len(palette)
            palette.append(mb.parse(state))
    shorts = [index.get(blocks.get((x, y, z)), 0) if (x, y, z) in blocks else 0 for y in range(sy) for z in range(sz) for x in range(sx)]
    ints = []
    for i in range(0, len(shorts), 2):
        value = (shorts[i] << 16) | (shorts[i + 1] if i + 1 < len(shorts) else 0)
        if value >= 1 << 31:
            value -= 1 << 32
        ints.append(value)
    ax, ay, az = site.anchor
    hut_entity = Compound({
        "id": String(mod_id + ":airfield"),          # ブロックエンティティ型(小屋ブロックはどれも同じ型)
        "x": Short(ax), "y": Short(ay), "z": Short(az),
        "type": String(mod_id + ":" + ("anchorage" if name in PIERS else name)),   # 建物の登録名(別版も同じ建物)
        "pack": String(mb.PACK_NAME),
        "path": String("infrastructure/" + schematic + ".blueprint"),
        "mirror": Byte(0),
        "version": Int(0),
        "blueprintDataProvider": Compound({
            "schematicName": String(schematic),
            "corner1": mb.pos(-ax, -ay, -az),
            "corner2": mb.pos(sx - 1 - ax, sy - 1 - ay, sz - 1 - az),
            "posTagMap": List[Compound]([]),
        }),
    })
    entities = [hut_entity]
    if stash:
        tx, ty, tz = stash
        entities.append(Compound({
            "id": String("minecolonies:stash"), "x": Short(tx), "y": Short(ty), "z": Short(tz),
            "type": String("minecolonies:stash"), "mirror": Byte(0), "version": Int(0),
            "blueprintDataProvider": Compound({"schematicName": String(""), "corner1": mb.pos(0, 0, 0), "corner2": mb.pos(0, 0, 0),
                                               "posTagMap": List[Compound]([])}),
        }))
    for (x, y, z), state in sorted(blocks.items()):
        made_of = mb.materials(state)
        if made_of:
            entities.append(Compound({
                "id": String(mb.MATERIAL_ENTITY), "x": Short(x), "y": Short(y), "z": Short(z),
                "textureData": Compound({slot: String(material) for slot, material in made_of.items()}),
            }))
    mods = ["minecolonies", mod_id] + (["domum_ornamentum"] if any(s.startswith(mb.DO) for s in blocks.values()) else []) + \
        (["simulated"] if any(s.startswith("simulated:") for s in blocks.values()) else [])
    blueprint = nbtlib.File({
        "version": Byte(1),
        "size_x": Short(sx), "size_y": Short(sy), "size_z": Short(sz),
        "palette": List[Compound](palette),
        "blocks": IntArray(ints),
        "tile_entities": List[Compound](entities),
        "entities": List[Compound]([]),
        "required_mods": List[String]([String(m) for m in mods]),
        "name": String(schematic),
        "architects": List[String]([String("asteain")]),
        "mcversion": Int(mb.DATA_VERSION),
        "optional_data": Compound({"structurize": Compound({"primary_offset": mb.pos(ax, ay, az)})}),
    })
    os.makedirs(os.path.dirname(out), exist_ok=True)
    blueprint.save(out, gzipped=True)
    counts = {}
    for state in blocks.values():
        key = state.split("[")[0].split("|")[0]
        counts[key] = counts.get(key, 0) + 1
    print("wrote", out, os.path.getsize(out), "bytes; size", site.size, "; blocks", len(blocks))
    print("  materials:", ", ".join("%s %d" % (k.split(":")[1], n) for k, n in sorted(counts.items(), key=lambda kv: -kv[1])[:12]))
    return out


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("--") and a in FACILITIES] or list(FACILITIES)
    levels = LEVELS
    for a in sys.argv[1:]:
        if a.startswith("--levels="):
            levels = tuple(int(v) for v in a.split("=", 1)[1].split(","))
    for name in names:
        designs = {level: design(name, level) for level in (levels if name not in PIERS else (1,))}
        levels_here = sorted(designs)
        for lower, upper in zip(levels_here, levels_here[1:]):
            check_levels(name, designs[lower], designs[upper])
        for level in levels_here:
            blocks, racks, stash = designs[level]
            write(name, level, blocks, stash)


if __name__ == "__main__":
    main()
