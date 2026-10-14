"""飛行場の設計図(Structurize の .blueprint。レベル 1 から 5)を、ゲームを起動せずに作る。

2026-10-11 の裁定で、飛行場は「垂直に離着陸できる場所」と「屋根のある場所(飛行士の居場所と荷の置き場)」を
一つにした大きな建物になった。見た目は、ユーザーの指定で MineColonies の Original パック(昔からの MineColonies の様式)に
寄せる。

レベルの上がり方も、Original パックに合わせる(2026-10-11 夜のユーザーの指摘「改築無しで増築のみはいけない。ほかの建物の
レベル 1〜5 を見ること」)。配達人の小屋、倉庫、建築家、木こりの 5 段階を jar から読んで比べた結果:

- **敷地(設計図の箱)と小屋ブロックの位置は、どのレベルも同じ。**
- **レベルが上がるたびに、建物そのものが建て替わる。**
    - レベル 1: オークの板と原木、丸石。柵の窓、壁の松明、丸石の階段の軒。
    - レベル 2: 材料は同じまま、窓が柵からガラス板(2 段)に、軒が石レンガの階段に、松明の一部がランタンに変わる。
      屋根が 1 段高くなり、扉にトウヒの柵の柱と階段のひさしが付く。中の物が増える(前の建物の 2〜5 割が置き換わる)。
    - レベル 3: トウヒ材と瓦屋根(Domum Ornamentum)、石レンガに、ほぼ全部を建て替える。
    - レベル 4: ダークオークと木組み。レベル 5: レンガと、皮をはいだダークオーク。
- 棚は、レベルとともに増える(倉庫 24→32→88、建築家 2→4)。

この飛行場も、同じ順に建て替わる(2026-10-11 夜の裁定で、レベル 1 と 2 の見た目は「良い」。レベル 3〜5 は助手の案):

    レベル 3: トウヒ材、石レンガの床、瓦屋根(軒は shingle_flat、斜面は shingle、てっぺんは shingle_slab)。東のあずまやが、
              壁のある「来客の小屋」になる。棚 12。台の縁と十字が石レンガになる。
    レベル 4: ダークオーク材、壁の上の段が木組み(double_crossed)、前庭が石レンガ、台の縁と十字が深層岩のタイル。
              格納庫と来客の小屋の間に、屋根つきの渡り。棚 16。
    レベル 5: 壁がレンガ(来客の小屋はベージュのレンガ)、柱が皮をはいだダークオーク、木組みの中は白。棚 20。

    建物は、床から 8 段までしか高くできない(線が台の 16 ブロック上から出る)ので、レベル 2 から先は高さを変えず、
    材料と、東の建物と、棚の数で差を付ける。台は、レベル 2 から先もずっと 2 つ(敷地 34 × 25 に 3 つめは入らない)。

Domum Ornamentum のブロックは、置く場所ごとに「どの材料で作るか」をブロックエンティティに持つ。この道具の中では、
状態のあとに「|」で続けて書く(状態|絵の枠=材料;…)。設計図へ書くときに、ブロックエンティティの中身に直す。

    北(-z)   敷地は 34 × 25(どのレベルも同じ)
    x=0              x=16 x=17             x=33
    ┌─────────────────┬─────────────────┐  z=0   柵(高さ 1)で敷地の北側を囲む
    │  ┌───────────┐  │  ┌ ─ ─ ─ ─ ─ ┐  │  z=2
    │  │ 台 13×13  │  │    来客の台      │        レベル 1: 東は土の空き地(台の予定地を土の道でふちどる)
    │  └───────────┘  │  └ ─ ─ ─ ─ ─ ┘  │  z=14  レベル 2: 東も丸石の前庭になり、来客の台ができる
    │                 │                 │  z=15〜17
    │ ╔═════════════╗ │   ┌─────────┐   │  z=18  格納庫の前面(柱だけ。壁は無い)
    │ ║ 回収箱  机   ║ │   │来客の   │   │  z=19〜22
    │ ║ 棚…  小屋 扉 ║ │   │あずまや │   │  z=22  奥の壁ぎわに、輸出用の棚と小屋ブロック
    │ ╚═════════════╝ │   └─────────┘   │  z=23  奥の壁(扉つき)
    └─────────────────┴─────────────────┘  z=24

    レベル 1: 格納庫(壁 4 段、柵の窓、松明、丸石の軒)。棚 4 つ、回収箱。東は空き地(資材の原木が積んである)。
    レベル 2: 格納庫を建て替える(壁 5 段、ガラス板の窓、ランタン、石レンガの軒、裏口のひさし、棚 6 つ)。
              東に来客の台と、来客のあずまや(棚 2 つ、長いす)。柵の角は、原木の柱とランタンになる。

決まり(本体の RouteRules と合わせること):
- 台の中心は、小屋ブロックから見て PAD_FROM_HUT = (1, -1, -14)。来客の台(レベル 2 から)は GUEST_PAD_FROM_HUT = (18, -1, -14)。
- 台の中心から横に 8.5 ブロック以内には、床から 2 段め以上に何も置かない(機体が垂直に上がり下がりする筒)。
- それより外でも、床から 9 段め以上には何も置かない(線は、台の 16 ブロック上から出る)。
- 前のレベルの棚、回収箱、小屋、台は、次のレベルでも同じ場所にある(中の品物が残るように)。

形式は Structurize の BlueprintUtil#writeBlueprintToNBT に合わせた gzip の NBT。

使い方: python tools/make_blueprint.py [MOD の ID] [--show]
MOD の ID を変えたら、作り直すこと(小屋ブロックの名前と、ブロックエンティティの名前に ID が入る)。
nbtlib が要る(pip install nbtlib)。design() は、レビュー用のビューアー(ColonyPatchServer の design/review-2026-10-11)も読む。
"""
import math
import os
import sys

import nbtlib
from nbtlib import Byte, Compound, Int, IntArray, List, Short, String

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZE_X, SIZE_Y, SIZE_Z = 34, 9, 25  # 敷地。どのレベルも同じ
LEVELS = (1, 2, 3, 4, 5)
PAD = (8, 0, 8)             # 台の中心(床のブロック)
PAD_GAP = 17                # 来客の台は、この分だけ東
GUEST_PAD_LEVEL = 2         # 来客の台ができるレベル
PAD_HALF = 6                # 台は 13×13
ANCHOR = (7, 1, 22)         # 小屋ブロック
SHAFT_RADIUS = 8.5          # 台の中心から横にこの距離までは、SHAFT_FREE_FROM 段め以上に何も置かない
SHAFT_FREE_FROM = 2
HIGHEST = 8                 # これより上の段には何も置かない
DATA_VERSION = 3955         # Minecraft 1.21.1
SCHEMATIC = "airfield%d"    # レベルの数字で終わること(MineColonies は、改築の設計図を、この数字を入れ替えて探す)
PACK_NAME = "Airline"       # pack.json の name と同じにする

LOG = "minecraft:oak_log[axis=%s]"
PLANKS = "minecraft:oak_planks"
COBBLE = "minecraft:cobblestone"
PATH = "minecraft:dirt_path"
DIRT = "minecraft:dirt"
FENCE = "minecraft:oak_fence"
SPRUCE_FENCE = "minecraft:spruce_fence"
PANE = "minecraft:glass_pane"
LANTERN = "minecraft:lantern[hanging=%s,waterlogged=false]"
RACK = "minecolonies:blockminecoloniesrack[facing=%s,variant=blockrackemptysingle]"
STONE_BRICKS = "minecraft:stone_bricks"
DEEPSLATE = "minecraft:deepslate_tiles"
BRICKS = "minecraft:bricks"
DO = "domum_ornamentum:"
BEIGE = DO + "beige_bricks"
ROOFING = DO + "cobblestone_extra"       # 瓦の材料(Original パックと同じ)
PAPER = DO + "paper_extra"               # 木組みの中の材料(レベル 4)
MATERIAL_ENTITY = DO + "materially_retexturable"
# 柵やガラス板がつながる相手(ブロック 1 個ぶんの面を持つもの)
FULL = ("minecraft:oak_log", "minecraft:oak_planks", "minecraft:cobblestone",
        "minecraft:spruce_log", "minecraft:spruce_planks", "minecraft:dark_oak_log", "minecraft:dark_oak_planks", "minecraft:stripped_dark_oak_log",
        STONE_BRICKS, DEEPSLATE, BRICKS, BEIGE, DO + "double_crossed")


def shingle(kind, facing, corner, support):
    """瓦。kind は shingle(斜面)か shingle_flat(軒)。角は outer_right(北西の角は東向き、北東は南、南東は西、南西は北)。"""
    return "%s%s[facing=%s,half=bottom,shape=%s,waterlogged=false]|minecraft:block/oak_planks=%s;minecraft:block/clay=%s" % (
        DO, kind, facing, "outer_right" if corner else "straight", support, ROOFING)


def ridge(shape, facing, wood):
    """屋根のてっぺんの瓦(shingle_slab)。"""
    return "%sshingle_slab[facing=%s,shape=%s,waterlogged=false]|minecraft:block/oak_planks=%s;minecraft:block/dark_oak_planks=%s" % (DO, facing, shape, ROOFING, wood)


def frame(facing, wood, centre):
    """木組みの壁(double_crossed)。facing は、表の向き。"""
    return "%sdouble_crossed[facing=%s]|minecraft:block/oak_planks=%s;minecraft:block/dark_oak_planks=%s" % (DO, facing, wood, centre)


def stairs(facing, half="bottom", kind="oak"):
    return "minecraft:%s_stairs[facing=%s,half=%s,shape=straight,waterlogged=false]" % (kind, facing, half)


def pads(level):
    """台の中心(床のブロック)。1 つめが自分の台、2 つめが来客の台。"""
    px, py, pz = PAD
    return [(px, py, pz)] + ([(px + PAD_GAP, py, pz)] if level >= GUEST_PAD_LEVEL else [])


def design(mod_id="airline", level=1):
    """座標 (x, y, z) → ブロックの状態(命令と同じ書き方。Domum Ornamentum は「|材料」つき)。書いていない所は空気。"""
    b = {}
    improved = level >= 2
    tiled = level >= 3                  # 瓦屋根と、来客の小屋
    east = SIZE_X - 1
    wood = ("oak", "oak", "spruce", "dark_oak", "dark_oak")[level - 1]
    planks = "minecraft:%s_planks" % wood
    log = "minecraft:stripped_dark_oak_log[axis=%s]" if level >= 5 else "minecraft:" + wood + "_log[axis=%s]"
    fence = "minecraft:%s_fence" % wood
    floor = COBBLE if level <= 2 else STONE_BRICKS if level <= 4 else DEEPSLATE          # 建物の床
    border = "minecraft:brick_wall" if level >= 5 else fence                             # 敷地の北側を囲むもの
    plate = BRICKS if level >= 5 else planks                                             # 壁の下の段
    course = BEIGE if level >= 5 else planks                                             # 軒の裏の、壁のいちばん上の段
    apron = COBBLE if level <= 3 else STONE_BRICKS                                       # 前庭
    mark = COBBLE if level <= 2 else STONE_BRICKS if level == 3 else DEEPSLATE           # 台の十字(レベル 3 から、縁も)
    frame_wood = "minecraft:stripped_dark_oak_wood" if level >= 5 else "minecraft:dark_oak_planks"
    frame_centre = "minecraft:smooth_quartz" if level >= 5 else PAPER

    def put(x, y, z, state):
        assert 0 <= x < SIZE_X and 0 <= y < SIZE_Y and 0 <= z < SIZE_Z, (x, y, z)
        b[(x, y, z)] = state

    def box(x0, y0, z0, x1, y1, z1, state):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    put(x, y, z, state)

    def ring(x0, z0, x1, z1, y, state_of):
        """長方形の輪。state_of(向き) に、その辺が上がっていく向き(輪の内側)を渡す。角は北と南の辺のものにする。"""
        for x in range(x0, x1 + 1):
            put(x, y, z0, state_of("south"))
            put(x, y, z1, state_of("north"))
        for z in range(z0 + 1, z1):
            put(x0, y, z, state_of("east"))
            put(x1, y, z, state_of("west"))

    def roof_ring(x0, z0, x1, z1, y, piece):
        """屋根の輪。piece(向き, 角か)。瓦は、角に角の形を使う。階段(レベル 1・2)は、いままでどおり角も辺の形。"""
        if not tiled:
            ring(x0, z0, x1, z1, y, lambda facing: piece(facing, False))
            return
        ring(x0, z0, x1, z1, y, lambda facing: piece(facing, False))
        put(x0, y, z0, piece("east", True))
        put(x1, y, z0, piece("south", True))
        put(x1, y, z1, piece("west", True))
        put(x0, y, z1, piece("north", True))

    def eaves_piece(facing, corner):
        if tiled:
            return shingle("shingle_flat", facing, corner, STONE_BRICKS)
        return stairs(facing, kind="stone_brick" if improved else "cobblestone")

    def slope_piece(facing, corner):
        return shingle("shingle", facing, corner, planks) if tiled else stairs(facing)

    def roof_top(x0, z0, x1, z1, y):
        """屋根のてっぺん。レベル 1・2 は板。瓦のときは、1 列なら棟、2 列なら Original パックの小屋と同じ並び。"""
        if not tiled:
            box(x0, y, z0, x1, y, z1, planks)
        elif z0 == z1:
            for x in range(x0, x1 + 1):
                put(x, y, z0, ridge("two_way", "east", planks))
            put(x0, y, z0, ridge("one_way", "west", planks))
            put(x1, y, z0, ridge("one_way", "east", planks))
        else:
            assert z1 == z0 + 1
            for x in range(x0 + 1, x1):
                put(x, y, z0, ridge("three_way", "south", planks))
                put(x, y, z1, ridge("three_way", "north", planks))
            put(x0, y, z0, ridge("curved", "north", planks))
            put(x1, y, z0, ridge("curved", "east", planks))
            put(x0, y, z1, ridge("curved", "west", planks))
            put(x1, y, z1, ridge("curved", "south", planks))

    def hip_roof(x0, z0, x1, z1, y, steps):
        """寄棟屋根。(x0..x1, z0..z1) は壁の外周。軒は壁から 1 ブロック張り出す。steps は、軒の上の段数。"""
        roof_ring(x0 - 1, z0 - 1, x1 + 1, z1 + 1, y, eaves_piece)                             # 軒
        ring(x0, z0, x1, z1, y, lambda facing: course)                                        # 軒の裏で、壁をもう 1 段
        for i in range(steps):
            roof_ring(x0 + i, z0 + i, x1 - i, z1 - i, y + 1 + i, slope_piece)
            if i + 1 < steps:
                ring(x0 + i + 1, z0 + i + 1, x1 - i - 1, z1 - i - 1, y + 1 + i, lambda facing: planks)
        roof_top(x0 + steps, z0 + steps, x1 - steps, z1 - steps, y + steps)

    def pad_floor(cx, cz):
        if level >= 3:                                                                       # 縁
            for i in range(-PAD_HALF, PAD_HALF + 1):
                put(cx + i, 0, cz - PAD_HALF, mark)
                put(cx + i, 0, cz + PAD_HALF, mark)
                put(cx - PAD_HALF, 0, cz + i, mark)
                put(cx + PAD_HALF, 0, cz + i, mark)
        box(cx - PAD_HALF + 1, 0, cz - PAD_HALF + 1, cx + PAD_HALF - 1, 0, cz + PAD_HALF - 1, planks)      # 台の中
        for i in range(-PAD_HALF + 1, PAD_HALF):                                                        # 台の中心を示す十字
            put(cx + i, 0, cz, mark)
            put(cx, 0, cz + i, mark)

    def walls(x0, z0, x1, z1, top, band_from, lower):
        """壁(柱の間)。奥(z1)と両側(x0、x1)。前(z0)は開いている。band_from 段めから上は、レベル 4・5 では木組み。"""
        for y in range(1, top + 1):
            framed = level >= 4 and y >= band_from
            for x in range(x0 + 1, x1):
                put(x, y, z1, frame("south", frame_wood, frame_centre) if framed else lower)
            for z in range(z0 + 1, z1):
                put(x0, y, z, frame("west", frame_wood, frame_centre) if framed else lower)
                put(x1, y, z, frame("east", frame_wood, frame_centre) if framed else lower)

    ax, ay, az = ANCHOR
    px, _, pz = PAD
    gx = px + PAD_GAP

    # ---- 地面 ----
    box(0, 0, 0, 16, 0, 17, apron)                                      # 西の前庭
    pad_floor(px, pz)
    box(0, 0, 18, east, 0, 24, PATH)                                    # 南の通り道(建物の両脇と裏)
    if improved:
        box(17, 0, 0, east, 0, 17, apron)                               # 東も前庭になる
        pad_floor(gx, pz)
    else:
        box(17, 0, 0, east, 0, 24, DIRT)                                # 東は、まだ空き地
        for i in range(-PAD_HALF, PAD_HALF + 1):                        # 台の予定地を、土の道でふちどる
            put(gx + i, 0, pz - PAD_HALF, PATH)
            put(gx + i, 0, pz + PAD_HALF, PATH)
            put(gx - PAD_HALF, 0, pz + i, PATH)
            put(gx + PAD_HALF, 0, pz + i, PATH)
        box(17, 0, 18, east, 0, 18, PATH)                               # 格納庫の前から東へ伸びる道
        for x in (gx - 1, gx, gx + 1):                                  # 積んである資材(オークの原木)
            put(x, 1, 21, LOG % "x")
            put(x, 1, 22, LOG % "x")
        put(gx, 2, 21, LOG % "x")
        put(gx + 1, 2, 21, LOG % "x")

    # ---- 敷地の北側を囲む柵(高さ 1。筒の中でも置ける高さ)と、明かり ----
    for z in range(0, 17):
        put(0, 1, z, border)
        put(east, 1, z, border)
    for x in range(0, SIZE_X):
        put(x, 1, 0, border)
    for x in (0, east):
        for z in (0, 16):
            if improved:                                                # 角は、原木の柱とランタン
                put(x, 1, z, log % "y")
                put(x, 2, z, LANTERN % "false")
            else:
                put(x, 2, z, "minecraft:torch")
    if improved:                                                        # 2 つの台の境目にも、柵の上にランタン
        put(16, 2, 0, LANTERN % "false")
        put(17, 2, 0, LANTERN % "false")
    if tiled:                                                           # 柵の上のランタンが増える(筒の外だけ)
        for x in (4, 12, 21, 29):
            put(x, 2, 0, LANTERN % "false")
        for x in (0, east):
            for z in (4, 12):
                put(x, 2, z, LANTERN % "false")

    # ---- 格納庫(x 1..15, z 18..23)。レベル 2 で、壁が 1 段高くなる ----
    posts = (1, 5, 11, 15)                                              # 柱の x。間は 3・5・3 ブロック
    top = 5 if improved else 4                                          # 壁のいちばん上の段
    box(1, 0, 18, 15, 0, 23, plate)                                     # 壁の下(レベル 4 までは板、5 はレンガ)
    box(2, 0, 18, 14, 0, 22, floor)                                     # 床
    for x in posts:                                                     # 柱の下
        put(x, 0, 18, floor)
        put(x, 0, 23, floor)
    walls(1, 18, 15, 23, top, 4, BRICKS if level >= 5 else planks)
    if improved:                                                        # 窓: ガラス板を 2 段
        for y in (2, 3):
            for x in (1, 15):
                for z in (20, 21):
                    put(x, y, z, PANE)
            for x in (3, 4, 12, 13):
                put(x, y, 23, PANE)
    else:                                                               # 窓: 柵を 1 段
        for x in (1, 15):
            for z in (20, 21):
                put(x, 2, z, FENCE)
        for x in (3, 13):
            put(x, 2, 23, FENCE)
    for x in posts:
        box(x, 1, 18, x, top, 18, log % "y")                            # 前の柱
        box(x, 1, 23, x, top, 23, log % "y")                            # 奥の柱
    for x in range(2, 15):                                              # 前面は柱だけ。いちばん上の段だけ板を渡す
        if x not in posts:
            put(x, top, 18, planks)
    hip_roof(1, 18, 15, 23, top + 1, 2)                                 # 屋根(寄棟。軒の上に 2 段)

    # ---- 格納庫の中: 小屋、輸出用の棚、輸入用の回収箱、扉、飛行士の机 ----
    put(ax, ay, az, mod_id + ":blockhutairfield[facing=north]")
    racks = [(3, 1, 22), (4, 1, 22), (5, 1, 22), (6, 1, 22)]
    for spot in racks:
        put(*spot, RACK % "north")
    more = []
    if level >= 2:                                                      # 棚は、レベルごとに 2 つずつ増える
        more += [((2, 1, 22), "north"), ((2, 1, 21), "east")]
    if level >= 3:
        more += [((10, 1, 22), "north"), ((11, 1, 22), "north")]
    if level >= 4:
        more += [((14, 1, 22), "west"), ((14, 1, 21), "west")]
    if level >= 5:
        more += [((14, 1, 20), "west"), ((14, 1, 19), "west")]
    for spot, facing in more:
        put(*spot, RACK % facing)
        racks.append(spot)
    stash = (2, 1, 19)
    put(*stash, "minecolonies:blockstash[facing=east]")
    put(9, 1, 23, "minecraft:%s_door[facing=south,half=lower,hinge=left,open=false,powered=false]" % wood)
    put(9, 2, 23, "minecraft:%s_door[facing=south,half=upper,hinge=left,open=false,powered=false]" % wood)
    put(12, 1, 20, fence)                                               # 机
    put(12, 2, 20, "minecraft:%s_pressure_plate[powered=false]" % wood)
    put(11, 1, 20, stairs("west", kind=wood))                           # いす
    put(13, 1, 20, stairs("east", kind=wood))
    put(13, 1, 22, "minecraft:crafting_table")
    if tiled:                                                           # 中の明かり: 屋根の裏の板から下げたランタン
        for x in (5, 11):
            put(x, top + 1, 22, LANTERN % "true")
    else:                                                               # 中の松明(奥の柱)
        for x in (5, 11):
            put(x, 3, 22, "minecraft:wall_torch[facing=north]")
    if improved:
        for x in (3, 8, 13):                                            # 前の梁から下げたランタン
            put(x, top - 1, 18, LANTERN % "true")
        porch = fence if tiled else SPRUCE_FENCE
        for x in (8, 10):                                               # 裏口のひさし: 柵の柱と、階段
            put(x, 0, 24, floor)
            put(x, 1, 24, porch)
            put(x, 2, 24, porch)
        for x in (8, 9, 10):
            put(x, 3, 24, stairs("north", kind=wood))
    else:
        for x in posts:                                                 # 前の柱の松明(台の側)
            put(x, 2, 17, "minecraft:wall_torch[facing=north]")

    # ---- 東の建物(x 20..30, z 19..23)。レベル 2: 来客のあずまや(柱 4 本、奥だけ壁、低い屋根)。レベル 3 から: 来客の小屋(三方に壁、屋根が 1 段高い) ----
    if improved:
        x0, x1 = gx - 5, gx + 5
        box(x0, 0, 19, x1, 0, 23, floor)
        for x in (x0, x1):
            for z in (19, 23):
                box(x, 1, z, x, 3, z, log % "y")
        if tiled:
            lower = BEIGE if level >= 5 else planks
            walls(x0, 19, x1, 23, 3, 3, lower)
            if level >= 5:                                              # いちばん下の段はレンガ
                walls(x0, 19, x1, 23, 1, 9, BRICKS)
            for z in (20, 21, 22):                                      # 横の窓
                if z == 21:
                    put(x0, 2, z, PANE)
                    put(x1, 2, z, PANE)
        else:
            box(x0 + 1, 1, 23, x1 - 1, 3, 23, planks)                   # 奥の壁
            for z in (20, 21, 22):                                      # 横は、いちばん上の段だけ板を渡す
                put(x0, 3, z, planks)
                put(x1, 3, z, planks)
        for x in (gx - 3, gx - 2, gx + 2, gx + 3):                      # 奥の窓
            put(x, 2, 23, PANE)
        for x in range(x0 + 1, x1):                                     # 前は、いちばん上の段だけ板を渡す
            put(x, 3, 19, planks)
        hip_roof(x0, 19, x1, 23, 4, 2 if tiled else 1)
        guest_racks = [((x0 + 1, 1, 22), "north"), ((x0 + 2, 1, 22), "north")]                 # 来客の荷の棚
        if level >= 3:
            guest_racks += [((x0 + 3, 1, 22), "north"), ((x0 + 4, 1, 22), "north")]
        if level >= 4:
            guest_racks += [((x0 + 5, 1, 22), "north"), ((x0 + 1, 1, 21), "east")]
        if level >= 5:
            guest_racks += [((x0 + 1, 1, 20), "east"), ((x1 - 1, 1, 22), "north")]
        for spot, facing in guest_racks:
            put(*spot, RACK % facing)
            racks.append(spot)
        for x in (gx + 1, gx + 2, gx + 3):                              # 長いす(北を向いて座る)
            put(x, 1, 22, stairs("south", kind=wood))
        if tiled:
            put(gx, 4, 20, LANTERN % "true")                            # 屋根の裏の板から下げたランタン
        else:
            put(gx, 4, 21, LANTERN % "true")

    # ---- レベル 4 から: 格納庫と来客の小屋の間の、屋根つきの渡り ----
    if level >= 4:
        box(16, 0, 20, 19, 0, 22, floor)
        for z in (20, 22):
            put(17, 1, z, fence)
            put(17, 2, z, fence)
            put(18, 1, z, fence)
            put(18, 2, z, fence)
        box(16, 3, 20, 19, 3, 22, "minecraft:%s_slab[type=bottom,waterlogged=false]" % wood)

    # ---- 柵とガラス板のつながり ----
    def joins(spot, fences):
        other = b.get(spot)
        if other is None:
            return False
        name = other.split("[")[0].split("|")[0]
        return name in FULL or (name.endswith("_fence") if fences else name == PANE)

    for (x, y, z), state in list(b.items()):
        sides = {"north": (x, y, z - 1), "south": (x, y, z + 1), "west": (x - 1, y, z), "east": (x + 1, y, z)}
        if state == "minecraft:brick_wall":
            # 塀: つながる向きは low。まっすぐ続く所は柱を立てない(up=false)。上に何か載る所と、角と端は柱
            linked = {side: (b.get(spot, "").split("[")[0].split("|")[0] in FULL + ("minecraft:brick_wall",)) for side, spot in sides.items()}
            straight = (linked["north"] and linked["south"] and not linked["east"] and not linked["west"]) or (linked["east"] and linked["west"] and not linked["north"] and not linked["south"])
            up = not straight or (x, y + 1, z) in b
            b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "low" if linked[side] else "none") for side in sorted(sides)) + ",up=%s,waterlogged=false]" % ("true" if up else "false")
            continue
        if state != PANE and not (state.endswith("_fence") and "[" not in state):
            continue
        b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "true" if joins(spot, state != PANE) else "false") for side, spot in sorted(sides.items())) + ",waterlogged=false]"

    # ---- 決まりの確かめ ----
    for (x, y, z), state in b.items():
        for cx, _, cz in pads(level):
            flat = math.hypot(x - cx, z - cz)
            assert not (y >= SHAFT_FREE_FROM and flat <= SHAFT_RADIUS), "in the shaft: %s at %s" % (state, (x, y, z))
        assert y <= HIGHEST
    return b, racks, stash


def check_levels(lower, upper):
    """改築で、中の品物と台が残ること: 前のレベルの棚、回収箱、小屋は、次のレベルでも同じ場所に同じ向きである。台の床は、どのレベルでも全部ある。"""
    blocks, racks, stash = lower
    after = upper[0]
    for spot in racks + [stash, ANCHOR]:
        assert after.get(spot) == blocks[spot], "the next level moves %s at %s (it has %s there)" % (blocks[spot], spot, after.get(spot))
    px, _, pz = PAD
    for x in range(px - PAD_HALF, px + PAD_HALF + 1):
        for z in range(pz - PAD_HALF, pz + PAD_HALF + 1):
            assert (x, 0, z) in after and (x, 0, z) in blocks, "the pad's floor has a hole at %s" % ((x, 0, z),)
    same = sum(1 for spot, state in blocks.items() if after.get(spot) == state)
    return same, len(blocks), len(after)


def materials(state):
    """「状態|絵の枠=材料;…」の、材料の部分({絵の枠: 材料})。無ければ None。"""
    _, bar, rest = state.partition("|")
    return dict(pair.split("=") for pair in rest.split(";")) if bar else None


def parse(state):
    name, _, rest = state.partition("|")[0].partition("[")
    entry = {"Name": String(name)}
    if rest:
        entry["Properties"] = Compound({k: String(v) for k, v in (pair.split("=") for pair in rest.rstrip("]").split(","))})
    return Compound(entry)


def pos(x, y, z):
    return Compound({"x": Int(x), "y": Int(y), "z": Int(z)})


def show(blocks):
    """層ごとの略図(確かめ用)。"""
    marks = {"minecraft:cobblestone": "c", "minecraft:oak_planks": "p", "minecraft:spruce_planks": "+", "minecraft:oak_log": "L", "minecraft:oak_fence": "f",
             "minecraft:oak_stairs": "s", "minecraft:oak_slab": "_", "minecraft:torch": "i", "minecraft:wall_torch": "i", "minecraft:lantern": "l",
             "minecraft:dirt_path": ".", "minecraft:cobblestone_stairs": "z", "minecolonies:blockminecoloniesrack": "R", "minecolonies:blockstash": "S", "minecraft:oak_door": "D",
             "minecraft:oak_pressure_plate": "t", "minecraft:crafting_table": "w"}
    width = max(x for x, _, _ in blocks) + 1
    for y in range(SIZE_Y):
        print("y =", y)
        for z in range(SIZE_Z):
            row = ""
            for x in range(width):
                state = blocks.get((x, y, z))
                name = state.split("[")[0] if state else None
                row += " " if name is None else ("H" if "blockhut" in name else marks.get(name, "?"))
            print("  %2d |%s|" % (z, row))


def write(mod_id, level, blocks, racks, stash):
    schematic = SCHEMATIC % level
    width = SIZE_X
    out = os.path.join(ROOT, "src", "main", "resources", "blueprints", mod_id, "airfield", "infrastructure", schematic + ".blueprint")

    palette, index = [Compound({"Name": String("minecraft:air")})], {}
    for state in blocks.values():
        if state not in index:
            index[state] = len(palette)
            palette.append(parse(state))

    # 並びは y → z → x。short を 2 個ずつ int に詰める(上位 16 ビットが先)。
    shorts = [index.get(blocks.get((x, y, z)), 0) if (x, y, z) in blocks else 0 for y in range(SIZE_Y) for z in range(SIZE_Z) for x in range(width)]
    ints = []
    for i in range(0, len(shorts), 2):
        hi = shorts[i]
        lo = shorts[i + 1] if i + 1 < len(shorts) else 0
        value = (hi << 16) | lo
        if value >= 1 << 31:
            value -= 1 << 32
        ints.append(value)

    ax, ay, az = ANCHOR
    hut_entity = Compound({
        "id": String(mod_id + ":airfield"),          # ブロックエンティティ型の登録名
        "x": Short(ax), "y": Short(ay), "z": Short(az),
        "type": String(mod_id + ":airfield"),        # 建物の登録名
        "pack": String(PACK_NAME),
        "path": String("infrastructure/" + schematic + ".blueprint"),  # パックの中での道(レベル 1 は Airline.BLUEPRINT_PATH と同じ)
        "mirror": Byte(0),
        "version": Int(0),
        "blueprintDataProvider": Compound({
            "schematicName": String(schematic),
            "corner1": pos(-ax, -ay, -az),
            "corner2": pos(width - 1 - ax, SIZE_Y - 1 - ay, SIZE_Z - 1 - az),
            "posTagMap": List[Compound]([]),
        }),
    })
    # 輸入用の回収箱。MineColonies のデフォルトパックの食堂(cook4、cook5)が、設計図に回収箱を含めるのと同じ書き方。
    sx, sy, sz = stash
    stash_entity = Compound({
        "id": String("minecolonies:stash"),
        "x": Short(sx), "y": Short(sy), "z": Short(sz),
        "type": String("minecolonies:stash"),
        "mirror": Byte(0),
        "version": Int(0),
        "blueprintDataProvider": Compound({
            "schematicName": String(""),
            "corner1": pos(0, 0, 0),
            "corner2": pos(0, 0, 0),
            "posTagMap": List[Compound]([]),
        }),
    })

    # Domum Ornamentum のブロックは、場所ごとに、材料を書いたブロックエンティティを持つ。
    entities = [hut_entity, stash_entity]
    for (x, y, z), state in sorted(blocks.items()):
        made_of = materials(state)
        if made_of:
            entities.append(Compound({
                "id": String(MATERIAL_ENTITY), "x": Short(x), "y": Short(y), "z": Short(z),
                "textureData": Compound({slot: String(material) for slot, material in made_of.items()}),
            }))
    mods = ["minecolonies", mod_id] + (["domum_ornamentum"] if any(state.startswith(DO) for state in blocks.values()) else [])

    blueprint = nbtlib.File({
        "version": Byte(1),
        "size_x": Short(width), "size_y": Short(SIZE_Y), "size_z": Short(SIZE_Z),
        "palette": List[Compound](palette),
        "blocks": IntArray(ints),
        "tile_entities": List[Compound](entities),
        "entities": List[Compound]([]),
        "required_mods": List[String]([String(mod) for mod in mods]),
        "name": String(schematic),
        "architects": List[String]([String("asteain")]),
        "mcversion": Int(DATA_VERSION),
        "optional_data": Compound({"structurize": Compound({"primary_offset": pos(ax, ay, az)})}),
    })
    os.makedirs(os.path.dirname(out), exist_ok=True)
    blueprint.save(out, gzipped=True)

    check = nbtlib.load(out)
    counts = {}
    for state in blocks.values():
        counts[state.split("[")[0].split("|")[0]] = counts.get(state.split("[")[0].split("|")[0], 0) + 1
    print("wrote", out, os.path.getsize(out), "bytes; size", width, SIZE_Y, SIZE_Z, "; palette", len(check["palette"]), "; blocks", len(blocks))
    print("  materials:", ", ".join("%s %d" % (name.split(":")[1], n) for name, n in sorted(counts.items(), key=lambda kv: -kv[1])))
    print("  pads from hut:", [(x - ax, y - ay, z - az) for x, y, z in pads(level)], "; racks from hut:", [(x - ax, y - ay, z - az) for x, y, z in racks],
          "; stash from hut:", (sx - ax, sy - ay, sz - az))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mod_id = args[0] if args else "airline"
    designs = {level: design(mod_id, level) for level in LEVELS}
    for lower, upper in zip(LEVELS, LEVELS[1:]):
        same, before, after = check_levels(designs[lower], designs[upper])
        print("level %d -> %d: %d of %d blocks stay as they were; the new level has %d blocks" % (lower, upper, same, before, after))
    for level in LEVELS:
        blocks, racks, stash = designs[level]
        if "--show" in sys.argv:
            show(blocks)
        write(mod_id, level, blocks, racks, stash)


if __name__ == "__main__":
    main()
