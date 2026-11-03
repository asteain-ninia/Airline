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
台の上の左右に(台の中心を向く)。位置は下の CONNECTORS の数字(泊地は桟橋の縁)。泊地の連結器は、内側の床の上のレバーで、いつも伸ばしておく(2026-10-18)。ほかは、まだ通電なし。

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
PIER_BEYOND = 21                                # 桟橋の先は、台(機体の真ん中)からこれだけ先まで(大型機の半分の長さ 12 と、8 余分に)
# 石の桟橋(2026-10-15 夕のご指示「アーチは幅5で、なおかつ桟橋自体の幅は4に…柱の幅は従来通り2で。アーチ脚の長さはそれ自体がアーチ状に
# 短くなっていき、桟橋先端でちょうど0になる＝頂点になるように」、柱は 横 2・厚み 2 と選んでいただいた):
# 床は幅 4(機体が東に横付けするので、木の桟橋より西へ 1 列広い)・厚さ 1(同じ日のご意見「床の厚さを1ブロックに。持ち送りは良いですね」で、
# 床の下の梁をやめた)。床の下の真ん中 2 列に、2 × 2 の柱と、柱の間の幅 5 の弧。床の東西の縁は、逆さの階段(続くご意見「持ち送りではなくて
# 桟橋のヘリを逆付き階段にするのが良さそう」。縁の下の持ち送りは、弧の上半分を横から隠してしまった)。
PILLAR_DEEPEST = 14                             # 根元の柱の長さ(床の下から)。先へ行くほど短い
PILLAR_SHORTEST = 2                             # 柱の最低の長さ(続くご意見「柱の最低長さを2ブロックとしましょうか。多分こっちのほうがよりアーチっぽくなる」)。
#                                                 幅 5 の弧の切り口は床の下 2 段に収まるので、長さ 2 の柱の間にも弧が全部そろう
ARCH_DEPTH = PILLAR_DEEPEST                     # 石の桟橋の、床より下の深さ
PILLAR_THICK = 2                                # 柱の厚み(南北)。横(東西)も 2
ARCH_SPAN = 5                                   # 柱の間の弧の幅
PIER_SPACING = PILLAR_THICK + ARCH_SPAN         # 柱の間隔
STONE_PIER_BAYS = 6                             # 先端(長さ 0 の柱 = 大きな弧の頂点)から根元の柱まで、柱の間隔がいくつ並ぶか
# 桟橋の先が台からどれだけ先か: 先端から根元の柱の南の端まで (柱の間隔 × 数 + 柱の厚み)、その南に踊り場 3、小屋は台から南へ 20。= 26
STONE_PIER_BEYOND = STONE_PIER_BAYS * PIER_SPACING + PILLAR_THICK + 3 - 1 - 20
assert STONE_PIER_BEYOND >= PIER_BEYOND


def do_panel(facing, half, opened):
    """Domum Ornamentum のパネル(厚さ 3/16 の板。材料は石レンガ)。開いた板は、向きの反対側の壁に付く(バニラの落とし戸と同じ)。"""
    return "domum_ornamentum:panel[facing=%s,half=%s,open=%s,type=full,waterlogged=false]|minecraft:block/oak_planks=minecraft:stone_bricks" % (
        facing, half, opened)


# 石の桟橋の弧の切り口に使う形(南北の縦の切り口。u は北 → 南、v は下 → 上。0〜1)。
# ColonyPatchServer/design/arch-gate-2026-10-15 のアーチ門の「パネルも使う」版と同じ形と選び方(ご判断「瓦なしパネルのみが一番いい」)。
# 形はそこで本物のモデルを切って確かめたもの: 階段は「向いた側に、もう半分の段」、開いたパネルは向きの反対側の壁。
P3 = 3.0 / 16.0
PIER_SHAPES = {
    "full": (mb.STONE_BRICKS, lambda u, v: True, 0.0),
    "slab_top": ("minecraft:stone_brick_slab[type=top,waterlogged=false]", lambda u, v: v >= 0.5, 0.005),
    "slab_bottom": ("minecraft:stone_brick_slab[type=bottom,waterlogged=false]", lambda u, v: v < 0.5, 0.005),
    "stairs_top_south": (mb.stairs("south", "top", "stone_brick"), lambda u, v: v >= 0.5 or u >= 0.5, 0.008),
    "stairs_top_north": (mb.stairs("north", "top", "stone_brick"), lambda u, v: v >= 0.5 or u < 0.5, 0.008),
    "stairs_bottom_south": (mb.stairs("south", "bottom", "stone_brick"), lambda u, v: v < 0.5 or u >= 0.5, 0.008),
    "stairs_bottom_north": (mb.stairs("north", "bottom", "stone_brick"), lambda u, v: v < 0.5 or u < 0.5, 0.008),
    "panel_top": (do_panel("north", "top", "false"), lambda u, v: v >= 1 - P3, 0.012),
    "panel_bottom": (do_panel("north", "bottom", "false"), lambda u, v: v < P3, 0.012),
    "panel_north": (do_panel("south", "bottom", "true"), lambda u, v: u < P3, 0.012),
    "panel_south": (do_panel("north", "bottom", "true"), lambda u, v: u >= 1 - P3, 0.012),
}
PROFILE_SAMPLES = 32


def choose_shape(solid, z, y):
    """マス (z, y) の形: 理想の形(solid(z, y) → 石か)と食い違う点の割合 + 割り増し が一番小さいもの。空なら None。"""
    n = PROFILE_SAMPLES
    pts = [((i + 0.5) / n, (j + 0.5) / n) for i in range(n) for j in range(n)]
    ideal = [solid(z + u, y + v) for u, v in pts]
    if all(ideal):
        return "full"
    if not any(ideal):
        return None
    best, best_cost = None, sum(ideal) / float(len(pts))                 # 空のときの食い違い
    for name, (_, inside, extra) in PIER_SHAPES.items():
        cost = sum(1 for (u, v), want in zip(pts, ideal) if inside(u, v) != want) / float(len(pts)) + extra
        if cost < best_cost - 1e-9:
            best, best_cost = name, cost
    return best
PIERS = ("anchorage", "anchoragestone")
# 連結器の位置(台の中心から)。床置き: 上向き、台の中心から南へ 3(機体の下部連結器の位置が決まったら直す)。
# 横型: 台の床の 1 段上、台の中心から東西へ 13(中心を向く)。8(大型機の胴の幅 15 のすぐ外)にしていたが、機体を作るときと、建てた
# 機体を船にするときは、機体をいったん機首を東へ向けて置いてから台の向きへ回すので、前後 25 の大型機がその連結器にぶつかった
# (2026-10-15、場面 facilities)。前後の長さ(中心から 12)の外へ。機体の連結器とつなぐときに、位置を見直す。
CONNECTORS = {
    "mediumairfield": [],  # 床置きは、下の PIT(床の穴)に(2026-10-18)
    "largeairfield": [],   # 2026-10-18 に外した(ご裁定「いまの形、連結器を外す」。どの機体とも届かず、飾りだった)
    "anchorage": [((-8, 1, 0), "east"), ((8, 1, 0), "west")],
}
CONNECTOR = "simulated:docking_connector[extended=false,facing=%s,powered=false]"
# 泊地の岸壁の連結器は、いつも入れておく(ご裁定「埋め込んで常時ON」。2026-10-18 に信号の元のレバーを足した)。機体は、機体ブロックの列の
# 両舷 2 マスの連結器で、これにつながる(中心どうし 3 マス。機体の連結器は、着いたら自動操縦が入れる)。
CONNECTOR_ON = "simulated:docking_connector[extended=false,facing=%s,powered=true]"


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
            beyond = STONE_PIER_BEYOND if name == "anchoragestone" else PIER_BEYOND
            # 北の縁は、竪穴(半径 17)と桟橋の先の、遠い方まで
            self.pad1 = (self.r, below, max(self.r, beyond))
            self.beyond = beyond
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

    tip = pz - site.beyond
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
        # 石の桟橋(2026-10-15 夕のご指示。定数の所の説明を参照): 床は幅 4・厚さ 1 の石レンガで、東西の縁は逆さの階段。床の下の真ん中 2 列に、
        # 2 × 2 の柱(7 おき。先端にも 1 本)と、柱の間の幅 5 の半円の弧(天辺が床の下面)。
        # 柱の長さは、先端を頂点とする 4 分の 1 の楕円に沿って、根元(14)へ伸びる。根元では真下へ落ち、先では床と平らに並ぶ
        # (それまでのご意見「根本の足をさらに長く、外側に行くにつれて短く…ちょうど半アーチを描く」「もっと根元の方での減少が急激に」も、この形)。
        # ただし最低 2(先端の側はどれも 2)。弧の下側は、その楕円か、柱の最低の長さの、深い方で切る。弧の形の選び方は choose_shape()。
        brick, chiseled, wall = mb.STONE_BRICKS, "minecraft:chiseled_stone_bricks", "minecraft:stone_brick_wall"
        west, east = ax - 2, ax + 1                                     # 床の西と東の縁(東の縁は機体の脇。連結器もここ)
        core = (ax - 1, ax)                                             # 梁・柱・弧の 2 列
        root_end = az - 3                                               # 根元の柱の南の端(その南は、島の上の踊り場)
        for z in range(tip, az + 1):
            for x in range(west, east + 1):
                put(x, deck, z, brick)
            for x, facing in ((west, "east"), (east, "west")):          # 床の縁: 逆さの階段(上面は平ら。下の段は内向き)
                put(x, deck, z, mb.stairs(facing, "top", "stone_brick"))
        for z in range(az - 2, az + 1):                                 # 根元の踊り場(島の上)、市松。床より東西に 1 ずつ広い
            for x in range(west - 1, east + 2):
                put(x, deck, z, mb.DEEPSLATE if (x + z) % 2 else brick)
        span = root_end + 1 - tip

        def reach(z):
            """床の下から大きな楕円までの深さ。z はブロックの境目で測る、連続の値。先端で 0、根元の柱の南の端で 14。"""
            d = min(max(z - tip, 0.0), span) / span
            return PILLAR_DEEPEST * (1.0 - math.sqrt(max(0.0, 1.0 - d * d)))

        # 柱 k は z = tip + 7k と その南(k = 0 は先端)。根元の柱がいちばん長い(14)。どの柱も最低 2、ただし先端の柱だけは 0
        # (同じ日の最後のご意見「最後の柱だけ長さ0にしてください」)
        pillars = [tip + PIER_SPACING * k for k in range(0, STONE_PIER_BAYS + 1)]
        assert pillars[-1] + PILLAR_THICK - 1 == root_end, (pillars, root_end)
        lengths = {at: max(PILLAR_SHORTEST, int(round(reach(at + PILLAR_THICK / 2.0)))) for at in pillars}
        lengths[pillars[-1]] = PILLAR_DEEPEST
        lengths[pillars[0]] = 0

        def floor_of(z):
            """弧の下側を切る深さ: 大きな楕円か柱の最低の長さの、深い方。ただし先端の柱から次の柱までは、次の柱の長さ 2 から
            先端の 0 へ上がる小さな 4 分の 1 の楕円(先端で床と平らに並ぶ = 頂点。弧の北の足が宙に浮かないように)。"""
            d = z - tip
            if d < PIER_SPACING:
                t = min(max(d, 0.0), PIER_SPACING) / PIER_SPACING
                return max(reach(z), PILLAR_SHORTEST * (1.0 - math.sqrt(max(0.0, 1.0 - t * t))))
            return max(reach(z), PILLAR_SHORTEST)
        bays = [tip + PIER_SPACING * k + PILLAR_THICK for k in range(STONE_PIER_BAYS)]   # 弧の北の端
        spring = deck - ARCH_SPAN / 2.0                                 # 弧の立ち上がり(床の下面から半径ぶん下。マスの途中)

        def solid(z, y):
            """南北の縦の切り口(真ん中 2 列)の、理想の形。床より下だけを聞く。"""
            for at in pillars:
                if at <= z < at + PILLAR_THICK:
                    return y >= deck - lengths[at]
            for b0 in bays:
                if b0 <= z < b0 + ARCH_SPAN:
                    if y < deck - floor_of(z):
                        return False
                    c = b0 + ARCH_SPAN / 2.0
                    return y >= spring and (z - c) ** 2 + (y - spring) ** 2 >= (ARCH_SPAN / 2.0) ** 2
            return False

        for z in range(tip, root_end + 1):
            for y in range(deck - PILLAR_DEEPEST, deck):
                shape = choose_shape(solid, z, y)
                if shape is None:
                    continue
                state = PIER_SHAPES[shape][0]
                if state == brick and any(at <= z < at + PILLAR_THICK for at in pillars) and (deck - y) % 4 == 3:
                    state = chiseled                                    # 柱の帯
                for x in core:
                    put(x, y, z, state)
        for b0 in bays:
            # 弧の天辺に、床から鎖 1 つで吊ったランタン(先端の側の、弧の無い所も同じ)。床のすぐ下に吊ると、両脇の持ち送りの陰で横から見えない
            c = b0 + ARCH_SPAN // 2
            if (ax, deck - 1, c) not in b and (ax, deck - 2, c) not in b:
                put(ax, deck - 1, c, "minecraft:chain[axis=y,waterlogged=false]")
                put(ax, deck - 2, c, mb.LANTERN % "true")
        for x in (west - 1, east + 1):                                  # 踊り場の灯籠(竪穴の外): 塀の柱にランタン
            put(x, ay, az, wall)
            put(x, ay + 1, az, mb.LANTERN % "false")
        # 約束: ランタンは支えがあること(吊り下げは真上、置いたものは真下が、丸ブロックか塀か鎖。2026-10-15 のご指摘
        # 「今度はランタンがつかなくなるはず」: 天井側のパネルの下には吊れない)
        holders = (brick, chiseled, wall, "minecraft:chain")
        for (x, y, z), state in b.items():
            if state.startswith("minecraft:lantern"):
                above = "hanging=true" in state
                held = b.get((x, y + 1, z) if above else (x, y - 1, z), "").split("[")[0]
                assert held in holders, (name + ": lantern without support", (x, y, z), held)
    put(ax + 1, deck, pz, CONNECTOR_ON % "east")                       # 横型の連結器(桟橋の東の縁、機体の脇)。いつも入れておく
    # その信号の元: 連結器のすぐ内側の床の上のレバー(入れてある。床が信号を受けて、隣の連結器に渡す)。手で切れば係留も外れる
    # (2026-10-18 のご意見「レッドストーンブロックではなくてレバーかレッドストーントーチでいいのでは?」)
    put(ax, deck + 1, pz, "minecraft:lever[face=floor,facing=east,powered=true]")
    put(ax, ay, az, mod_id + ":blockhutanchorage[facing=north]")       # 係船柱(小屋ブロック)
    # 柵と塀のつながり
    for (x, y, z), state in list(b.items()):
        if state == "minecraft:stone_brick_wall":
            b[(x, y, z)] = state + "[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"
            continue
        if state == mb.FENCE:
            sides = {"north": (x, y, z - 1), "south": (x, y, z + 1), "west": (x - 1, y, z), "east": (x + 1, y, z)}
            b[(x, y, z)] = state + "[" + ",".join("%s=%s" % (side, "true" if b.get(spot, "").split("[")[0] in mb.FULL else "false") for side, spot in sorted(sides.items())) + ",waterlogged=false]"
    # 約束: 竪穴(台の中心から半径 17)の中は、桟橋の床より上に何も無い(ぶつかる形の無いレバーは別。航路の確かめも機体も通り抜ける)。
    # 台の上(機体の居る所)は空。
    for (x, y, z), state in b.items():
        assert state.startswith("minecraft:lever") or not (y >= py + 2 and math.hypot(x - px, z - pz) <= site.r), (name + ": in the shaft", state, (x, y, z))
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
        # (Measured as the air network measures it: a column is in the shaft when its middle lies within the radius and half
        # a block (Tube.touchesColumn). Up to 2026-10-15 only within the radius: a lantern on a fence post at the large
        # airfield's corner lay 17.03 from the pad's middle, and the network kept large aircraft off that pad's lines.)
        return y >= SHAFT_FREE_FROM and any(math.hypot(x - cx, z - cz) <= site.r + 0.5 for cx, _, cz in site.pads(level))

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
        put(ax - 4, 1, z1 - 2, mb.stairs("west", kind=wood))           # 背もたれは机の反対(2026-10-18 のご指摘で直した)
        put(ax - 2, 1, z1 - 2, mb.stairs("east", kind=wood))
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
        put(*stash, "minecolonies:blockstash[facing=south]")  # 前が通路の側(2026-10-18 に北から直した)
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
        put(*stash, "minecolonies:blockstash[facing=south]")  # 前が通路の側(2026-10-18 に北から直した)
        put(ax + 3, 1, z1 - 1, fence)                                   # 机といす
        put(ax + 3, 2, z1 - 1, "minecraft:%s_pressure_plate[powered=false]" % wood)
        put(ax + 2, 1, z1 - 1, mb.stairs("west", kind=wood))           # 背もたれは机の反対(2026-10-18 のご指摘で直した)
        put(ax + 4, 1, z1 - 1, mb.stairs("east", kind=wood))
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


# 床の穴の連結器(2026-10-18、ご裁定「位置合わせに使う」、ご指摘「発着場側の連結器は穴を掘ったほうがいいのでは？　着陸時に機体が浮いてしまいます」)。
# 機体は発着場で設計図の北を向いて止まり、底の連結器は機体のブロックから艫へ 3 なので、台の中心から南へ 3 の真下。
# 床に 1×1 の穴(床の段と、その下の段)、その下(床の 2 マス下)に上向きの連結器、東どなりの壁付きレッドストーントーチでいつも伸ばす。
# 機体の連結器(床の 1 段上)とは中心で 3 マス、間の 2 マスに両方の頭が入る。そのため設計図は床の下に 2 段を足す(地面はそのまま残す)。
PIT_LIFT = 2
PIT_FROM_PAD = (0, 3)
# 床の下の 2 段の、いまの地面をそのまま残す印は Structurize の置き換えの印(2026-11-03)。minecraft:structure_void は
# 回して置くと空気に変わり(Structurize の Blueprint.setRotationMirrorRelative が写さない)、建築家が地面を掘っていた。
KEEP = "structurize:blocksubstitution"
PIT_WALL = "minecraft:stone_bricks"


SLAB_OF = {"minecraft:stone_bricks": "minecraft:stone_brick_slab", "minecraft:deepslate_tiles": "minecraft:deepslate_tile_slab",
           "minecraft:cobblestone": "minecraft:cobblestone_slab"}


def rim_slab(state):
    """床のブロックと同じ材料の、下付きの半ブロック(床の穴の縁、2026-10-19)。"""
    name = state.split("[")[0].split("|")[0]
    slab = name[:-len("_planks")] + "_slab" if name.endswith("_planks") else SLAB_OF.get(name, "minecraft:stone_brick_slab")
    return slab + "[type=bottom,waterlogged=false]"


def pit_at(name, level):
    """床の穴の連結器があるか(Facility.floorConnector と同じ: 小型飛行場はレベル 3 から、中型飛行場はいつも)。"""
    return name == "mediumairfield" or (name == "airfield" and level >= 3)


def lifted(name):
    """床の下に 2 段を足す設計図(穴のあるレベルが 1 つでもある飛行場は、全部のレベルで足す。改築で小屋の位置が動かないように)。"""
    return name in ("airfield", "mediumairfield")


def with_pits(name, level, made):
    """床の下に 2 段を足し、穴と連結器を作る。"""
    blocks, racks, stash = made
    site = Site(name)
    up = lambda p: (p[0], p[1] + PIT_LIFT, p[2])
    out = {up(p): s for p, s in blocks.items()}
    sx, sy, sz = site.size
    for x in range(sx):
        for z in range(sz):
            for y in range(PIT_LIFT):
                out[(x, y, z)] = KEEP
    if pit_at(name, level):
        for cx, _, cz in site.pads(level):
            px, pz = cx + PIT_FROM_PAD[0], cz + PIT_FROM_PAD[1]
            floor = PIT_LIFT
            assert (px, floor, pz) in out, (name, level, "no floor where the pit goes")
            for dx in (-1, 0, 1):                                        # 穴(床の段は 3×3: 機体の連結器の頭が少しずれても入る)
                for dz in (-1, 0, 1):
                    out.pop((px + dx, floor, pz + dz), None)
                    if dx or dz:
                        out[(px + dx, floor - 1, pz + dz)] = PIT_WALL     # くぼみの底
            del out[(px, floor - 1, pz)]                                 # 穴(その下は 1×1)
            for dx in range(-2, 3):                                      # 縁: まわり 1 周を半ブロックに(半段ずつ下がる)
                for dz in range(-2, 3):
                    if max(abs(dx), abs(dz)) == 2 and (px + dx, floor, pz + dz) in out:
                        out[(px + dx, floor, pz + dz)] = rim_slab(out[(px + dx, floor, pz + dz)])
            out[(px, 0, pz)] = "simulated:docking_connector[extended=false,facing=up,powered=true]"
            out[(px + 1, 0, pz)] = "minecraft:redstone_wall_torch[facing=west,lit=true]"   # 東どなりの石に付く
            for spot in ((px + 2, 0, pz), (px - 1, 0, pz), (px, 0, pz - 1), (px, 0, pz + 1),
                         (px + 1, 1, pz), (px - 1, 1, pz), (px, 1, pz - 1), (px, 1, pz + 1)):
                out[spot] = PIT_WALL
    return out, [up(r) for r in racks], up(stash) if stash else None


def check_levels(name, lower, upper):
    """改築で、棚・回収箱・小屋が同じ場所にある。"""
    site = Site(name)
    blocks, racks, stash = lower
    after = upper[0]
    anchor = (site.anchor[0], site.anchor[1] + PIT_LIFT, site.anchor[2]) if lifted(name) else site.anchor
    for spot in racks + ([stash] if stash else []) + [anchor]:
        assert after.get(spot) == blocks[spot], "%s: the next level moves %s at %s (it has %s there)" % (name, blocks[spot], spot, after.get(spot))


def write(name, level, blocks, stash, mod_id="airline"):
    site = Site(name)
    sx, sy, sz = site.size
    if lifted(name):
        sy += PIT_LIFT
        site.anchor = (site.anchor[0], site.anchor[1] + PIT_LIFT, site.anchor[2])
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
        made_of = mb.materials(state) if state != KEEP else None
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
        if lifted(name):
            designs = {level: with_pits(name, level, made) for level, made in designs.items()}
        levels_here = sorted(designs)
        for lower, upper in zip(levels_here, levels_here[1:]):
            check_levels(name, designs[lower], designs[upper])
        for level in levels_here:
            blocks, racks, stash = designs[level]
            write(name, level, blocks, stash)


if __name__ == "__main__":
    main()
