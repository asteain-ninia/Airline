"""空路ブイ。Python 3 + Pillow。実行: python buoy.py OUT_DIR VANILLA_DIR"""
import argparse
import json
from pathlib import Path

from PIL import Image

SIDES = ("down", "up", "north", "south", "west", "east")


def _textures():
    # 帆布は生成り、金属は銅・古金・青緑。各素材は4階調だけ。
    palettes = {
        "canvas": ("#8f8062", "#b3a17d", "#cdbb94", "#dfd0ad"),
        "copper": ("#694437", "#945e42", "#bc8056", "#d7a271"),
        "gold": ("#716039", "#9b8146", "#c3a25b", "#ddc17b"),
        "diamond": ("#365e5d", "#4b8480", "#74aaa0", "#acd0bc"),
        "off": ("#283539", "#35474b", "#4c6265", "#718284"),
        "on": ("#bd773e", "#e9ac5e", "#ffdc91", "#fff2c4"),
    }
    result = {}
    for name, palette in palettes.items():
        colors = [
            tuple(bytes.fromhex(c[1:])) + (255,) for c in palette
        ]
        im = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                if name == "canvas":
                    # 縫い目と短い織り目。ランダムな斑点にはしない。
                    k = 2
                    if x % 8 == 0:
                        k = 1
                    elif x % 8 == 1 and y % 4 == 1:
                        k = 3
                    elif (x + 2 * y) % 13 == 0:
                        k = 1
                    elif y % 7 == 2 and x % 4 < 2:
                        k = 3
                    if x % 8 == 0 and y % 4 == 3:
                        k = 0
                elif name in ("off", "on"):
                    # 同じ窓模様を明暗で置換。中央8×8を灯室に使う。
                    d = max(abs(x - 7.5), abs(y - 7.5))
                    k = 0 if d >= 3.5 else 1 if d >= 2.5 else 2
                    if 7 <= x <= 8 and 6 <= y <= 9:
                        k = 3 if name == "on" else 2
                    if (x, y) in ((5, 5), (6, 5), (5, 6), (10, 10)):
                        k = 3
                else:
                    # 大きな宝石模様でなく、鍛金した細い帯として扱う。
                    k = (1, 2, 2, 1)[y % 4]
                    if (x + 3 * y) % 17 == 0:
                        k = 0
                    if y % 4 == 1 and x % 7 in (1, 2):
                        k = 3
                im.putpixel((x, y), colors[k])
        result[name] = im
    return result


def _box(elements, lo, hi, material, *, lamp=False, lit=False):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    # ワールド寸法に沿うUV。木と帆布のドットを過度に拡大しない。
    uv = {
        "down": [x0, 16 - z1, x1, 16 - z0],
        "up": [x0, z0, x1, z1],
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0],
        "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }
    faces = {}
    for side in SIDES:
        faces[side] = {
            "texture": "#" + material,
            "uv": [4, 4, 12, 12] if lamp else uv[side],
        }
    # cullfaceを指定しない。宙に浮く木枠の底も常に描く。
    element = {"from": list(lo), "to": list(hi), "faces": faces}
    if lamp and lit:
        element["shade"] = False
    elements.append(element)


def _model(tier, lit):
    elements = []

    def box(lo, hi, material, lamp=False):
        _box(elements, lo, hi, material, lamp=lamp, lit=lit)

    width = 6 if tier == 1 else 8
    a, b = (16 - width) / 2, (16 + width) / 2

    # 大きな灯室。上下の木枠は環状で、下面の発光面を塞がない。
    box((a, 2, a), (b, 8, b), "lamp", True)
    for y in (1, 8):
        box((a - 1, y, a - 1), (b + 1, y + 1, a), "wood")
        box((a - 1, y, b), (b + 1, y + 1, b + 1), "wood")
        box((a - 1, y, a), (a, y + 1, b), "wood")
        box((b, y, a), (b + 1, y + 1, b), "wood")
    for x in (a - 1, b):
        for z in (a - 1, b):
            box((x, 2, z), (x + 1, 8, z + 1), "wood")
    box((6.5, 0.5, 6.5), (9.5, 2, 9.5), "metal")

    # 一本の心柱と頂部の灯窓。上からも点灯状態が分かる。
    # 同じ平面に 2 枚の面が重なるとちらつく(Z ファイティング)ので、心柱の上端は金具の中へ 0.05 入れ、
    # 袋のてっぺん(y=15)と重ねない。下端は、等級 1 では袋の底、等級 3 では梁の底と同じ平面に来るので、
    # 0.05 上げる(袋や梁の中か、灯室の上の囲まれたすき間に向くので、外からは見えない)。
    box((7, 9 if tier == 2 else 9.05, 7), (9, 15.05, 9), "wood")
    box((6, 15, 6), (10, 15.5, 10), "metal")
    box((6.5, 15.5, 6.5), (9.5, 16, 9.5), "lamp", True)

    if tier == 1:
        # 細身の単袋。段差で丸みを出し、斜め面は使わない。
        for low, high, radius in ((9, 10, 3), (10, 14, 4),
                                  (14, 15, 3)):
            box((8 - radius, low, 8 - radius),
                (8 + radius, high, 8 + radius), "canvas")
        box((3.875, 11.75, 3.875), (12.125, 12.5, 12.125), "metal")
    elif tier == 2:
        # 幅広の扁平袋。遠目で単袋とは違う水平の輪郭になる。
        for low, high, radius in ((10, 11, 5), (11, 13, 7),
                                  (13, 14, 5), (14, 15, 3)):
            box((8 - radius, low, 8 - radius),
                (8 + radius, high, 8 + radius), "canvas")
        box((0.875, 11.75, 0.875), (15.125, 12.5, 15.125), "metal")
    else:
        # 四袋は四隅に離す。上・横の両方で切れ込みが読める。
        # 井桁の交わる所で上下の面が同じ平面に重なるとちらつくので、東西に渡す梁だけ、
        # 上下に 0.05 ずつ厚くする(交わる所では東西の梁の面が外に来る)。
        for q in (3, 13):
            box((2, 8.95, q - 0.5), (14, 10.05, q + 0.5), "wood")
            box((q - 0.5, 9, 2), (q + 0.5, 10, 14), "wood")
        box((2, 8.95, 7.5), (14, 10.05, 8.5), "wood")
        box((7.5, 9, 2), (8.5, 10, 14), "wood")
        for x in (3, 13):
            for z in (3, 13):
                for low, high, radius in ((10, 11, 1.5),
                                          (11, 15, 2.5),
                                          (15, 16, 1.5)):
                    box((x - radius, low, z - radius),
                        (x + radius, high, z + radius), "canvas")
                box((x - 2.625, 12.25, z - 2.625),
                    (x + 2.625, 13, z + 2.625), "metal")

    metal = ("copper", "gold", "diamond")[tier - 1]
    model = {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": {
            "particle": "minecraft:block/spruce_planks",
            "wood": "minecraft:block/spruce_planks",
            "canvas": "airline:block/buoy_canvas",
            "metal": "airline:block/buoy_" + metal,
            "lamp": "airline:block/buoy_" + ("on" if lit else "off"),
        },
        "elements": elements,
    }
    _validate(model)
    return model


def _validate(model):
    assert model["parent"] == "minecraft:block/block"
    assert model["render_type"] == "minecraft:cutout"
    assert "particle" in model["textures"]
    assert 0 < len(model["elements"]) <= 40
    for element in model["elements"]:
        assert all(0 <= a < b <= 16
                   for a, b in zip(element["from"], element["to"]))
        assert set(element["faces"]) == set(SIDES)
        for face in element["faces"].values():
            u0, v0, u1, v1 = face["uv"]
            assert 0 <= u0 < u1 <= 16 and 0 <= v0 < v1 <= 16
            assert face["texture"].startswith("#")
            assert face["texture"][1:] in model["textures"]


def build(out_dir: str, vanilla_dir: str) -> None:
    """out_dirに6モデルと6枚の16×16 RGBAテクスチャを生成する。"""
    # バニラ材はリソース参照で使うので、参照PNGのコピーは不要。
    # vanilla_dirは呼び出し規約のため受け取る。色は固定で再現性を保つ。
    root = Path(out_dir) / "assets" / "airline"
    texture_dir = root / "textures" / "block"
    model_dir = root / "models" / "block"
    texture_dir.mkdir(parents=True, exist_ok=True)
    model_dir.mkdir(parents=True, exist_ok=True)
    for name, im in _textures().items():
        assert im.mode == "RGBA" and im.size == (16, 16)
        im.save(texture_dir / ("buoy_" + name + ".png"), format="PNG")
    for tier in (1, 2, 3):
        for lit in (False, True):
            name = "buoy_" + str(tier) + ("_lit" if lit else "")
            data = json.dumps(_model(tier, lit), ensure_ascii=False, indent=2)
            _validate(json.loads(data))
            (model_dir / (name + ".json")).write_text(
                data + "\n", encoding="utf-8"
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out_dir")
    parser.add_argument("vanilla_dir")
    args = parser.parse_args()
    build(args.out_dir, args.vanilla_dir)
