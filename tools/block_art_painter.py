from pathlib import Path
from io import BytesIO
import json
from PIL import Image, ImageDraw

BRASS = [(104, 80, 43), (148, 115, 55), (184, 150, 77), (219, 187, 110)]
IRON = [(66, 65, 58), (91, 91, 81), (125, 125, 111), (163, 162, 144)]
CLOTH = [(173, 168, 150), (205, 201, 182), (230, 226, 208), (244, 240, 223)]
RED = [(113, 49, 37), (151, 65, 45), (181, 89, 60), (204, 116, 77)]


def _textures(vanilla_dir):
    with Image.open(Path(vanilla_dir) / "oak_planks.png") as src:
        oak = src.convert("RGBA")
    if oak.size != (16, 16):
        raise ValueError("oak_planks.png must be 16 x 16")

    # 外周は元の板をそのまま残し、隣のオークと継ぎ目を合わせる。
    pixels = (
        oak.get_flattened_data()
        if hasattr(oak, "get_flattened_data")
        else oak.getdata()
    )
    colors = sorted(set(pixels), key=lambda c: (sum(c[:3]), c))
    wood_dark = colors[0]
    top = oak.copy()
    d = ImageDraw.Draw(top)

    # 面取りした真鍮の環。輪郭も黒でなく、木と金属の影色。
    outer = [
        (5, 2), (10, 2), (13, 5), (13, 10),
        (10, 13), (5, 13), (2, 10), (2, 5),
    ]
    rim = [
        (5, 3), (10, 3), (12, 5), (12, 10),
        (10, 12), (5, 12), (3, 10), (3, 5),
    ]
    inner = [
        (6, 4), (9, 4), (11, 6), (11, 9),
        (9, 11), (6, 11), (4, 9), (4, 6),
    ]
    d.polygon(outer, fill=wood_dark)
    d.polygon(rim, fill=BRASS[2])
    d.line([(3, 9), (3, 5), (5, 3), (10, 3)], fill=BRASS[3])
    d.line([(12, 6), (12, 10), (10, 12), (5, 12)], fill=BRASS[1])
    d.polygon(inner, fill=IRON[0])
    d.line([(5, 6), (6, 5), (9, 5)], fill=IRON[1])

    # 北は画像上、機首は右（+x）。赤銅色の長い針で東を強調。
    d.line((7, 5, 7, 10), fill=IRON[2])
    d.line((5, 7, 10, 7), fill=IRON[2])
    d.polygon([(4, 8), (7, 7), (7, 9)], fill=CLOTH[1])
    d.polygon([(7, 6), (11, 8), (7, 10)], fill=RED[1])
    d.line((8, 7, 10, 8), fill=RED[3])
    d.point((7, 8), fill=BRASS[3])
    for x, y in [(5, 3), (10, 3), (3, 10), (12, 10)]:
        d.point((x, y), fill=BRASS[0])

    side = oak.copy()
    d = ImageDraw.Draw(side)
    # 梁を留める帯は上下に通し、小さな翼章を中央に打つ。
    for y in (1, 13):
        d.line((0, y, 15, y), fill=BRASS[3])
        d.line((0, y + 1, 15, y + 1), fill=BRASS[1])
        for x in (2, 13):
            d.point((x, y), fill=BRASS[0])
    d.rectangle((5, 5, 10, 10), fill=wood_dark)
    d.rectangle((6, 5, 9, 10), fill=BRASS[1])
    d.line((6, 5, 9, 5), fill=BRASS[3])
    d.line((6, 10, 9, 10), fill=BRASS[0])
    for a, b in [
        ((3, 6), (6, 6)), ((4, 7), (6, 7)),
        ((5, 8), (6, 8)), ((9, 6), (12, 6)),
        ((9, 7), (11, 7)), ((9, 8), (10, 8)),
    ]:
        d.line((a, b), fill=BRASS[2])
    d.line((7, 6, 7, 8), fill=CLOTH[2])
    d.point((8, 9), fill=BRASS[0])

    bottom = oak.copy()
    d = ImageDraw.Draw(bottom)
    # 船底は控えめな二本の鉄帯。木目は上面と同じ密度。
    for x in (3, 11):
        d.rectangle((x, 0, x + 1, 15), fill=IRON[1])
        d.line((x, 0, x, 15), fill=IRON[2])
        for y in (2, 7, 12):
            d.point((x, y), fill=IRON[3])
            d.point((x + 1, y + 1), fill=IRON[0])

    sock = Image.new("RGBA", (16, 16))
    # 吹き流しは生成ノイズを使わず、折り目だけを四階調で描く。
    for y in range(16):
        shade = 3 if y < 3 else 2 if y < 8 else 1 if y < 13 else 0
        for x in range(16):
            palette = CLOTH if (x // 4) % 2 == 0 else RED
            sock.putpixel((x, y), (*palette[shade], 255))

    return {
        "aircraft_top": top,
        "aircraft_side": side,
        "aircraft_bottom": bottom,
        "airfield_hut_windsock": sock,
    }


def _hut():
    textures = {
        "oak": "minecraft:block/oak_planks",
        "spruce": "minecraft:block/spruce_planks",
        "post": "minecraft:block/stripped_oak_log",
        "end": "minecraft:block/oak_log_top",
        "wool": "minecraft:block/white_wool",
        "iron": "minecraft:block/iron_block",
        "rope": "minecraft:block/stripped_oak_log",
        "sock": "airline:block/airfield_hut_windsock",
        "particle": "minecraft:block/oak_planks",
    }
    elements = []

    def box(name, a, b, material, overrides=None, rotation=None):
        x0, y0, z0 = a
        x1, y1, z1 = b
        # 座標に対応する切り抜きUV。小部品で木目を縮めすぎない。
        uv = {
            "north": [16-x1, 16-y1, 16-x0, 16-y0],
            "south": [x0, 16-y1, x1, 16-y0],
            "west": [z0, 16-y1, z1, 16-y0],
            "east": [16-z1, 16-y1, 16-z0, 16-y0],
            "up": [x0, z0, x1, z1],
            "down": [x0, 16-z1, x1, 16-z0],
        }
        faces = {
            f: {"texture": "#" + material, "uv": v}
            for f, v in uv.items()
        }
        for f, value in (overrides or {}).items():
            if value is None:
                faces.pop(f, None)
            else:
                tex, region = value
                faces[f] = {"texture": "#" + tex, "uv": list(region)}

        element = {
            "name": name,
            "from": list(a),
            "to": list(b),
            "faces": faces,
        }
        if rotation:
            axis, angle, origin = rotation
            element["rotation"] = {
                "axis": axis,
                "angle": angle,
                "origin": list(origin),
                "rescale": False,
            }
        elements.append(element)

    # 低い桟橋で白い船の輪郭を主役にする。正面は北（z=0）。
    for x in (1, 13):
        for z in (1, 13):
            box(
                "pier_foot", (x, 0, z), (x+2, 2.5, z+2), "post",
                {"down": ("end", (0, 0, 2, 2))},
            )
    box("pier_underbeam", (1, 1.25, 1), (15, 2.5, 15), "spruce")
    box("pier_deck", (0.5, 2.5, 0.5), (15.5, 3.5, 15.5), "oak")
    for z in (0.5, 14.75):
        box(
            "pier_rim", (0.5, 2.25, z),
            (15.5, 3.5, z+.75), "spruce",
        )

    # 桟橋から少し浮いた甲板と竜骨。気嚢との空隙を残す。
    box("ship_keel", (5, 5.5, 6.5), (12, 6.25, 8.5), "spruce")
    box("ship_deck", (4, 6.25, 6), (13, 7, 9), "oak")
    box("ship_bow", (13, 6.25, 6.75), (14, 7, 8.25), "oak")
    for z in (6, 8.6):
        box("gunwale", (4, 7, z), (12.75, 7.5, z+.4), "spruce")
    for x in (5, 11.5):
        for z in (6.25, 8.25):
            box(
                "suspension", (x, 7, z),
                (x+.4, 10.25, z+.4), "post",
            )

    # 羊毛の箱を段状に組み、実機の角張った白い気嚢を縮小。
    box("envelope", (4, 10, 5), (14, 14.5, 10), "wool")
    box(
        "envelope_crown", (5, 14.5, 6), (13, 15.5, 9), "wool",
        {"down": None},
    )
    box(
        "envelope_tail", (3, 11, 6), (4, 13.5, 9), "wool",
        {"east": None},
    )
    box(
        "envelope_nose", (14, 11, 6), (15, 13.5, 9), "wool",
        {"west": None},
    )
    box("tail_rudder", (2.5, 8.5, 7.25), (4, 11, 7.75), "spruce")

    # 二本の革色の帯は気嚢の側面だけに置く。
    for x in (5.5, 11.5):
        box(
            "envelope_binding",
            (x, 9.9, 4.92), (x+.35, 14.5, 10.08), "rope",
            {"up": None, "down": None, "east": None, "west": None},
        )

    # 両舷の木製プロペラ。回転後もブロック境界内。
    box("propeller_axle", (8.5, 8, 3.6), (9.25, 8.75, 11.4), "post")
    for z in (3.5, 11.25):
        for angle in (-45, 45):
            box(
                "wood_propeller",
                (8.5, 6.1, z), (9.25, 10.65, z+.25), "spruce",
                rotation=("z", angle, (8.875, 8.375, z+.125)),
            )
        box(
            "propeller_hub",
            (8.35, 7.85, z-.1), (9.4, 8.9, z+.35), "iron",
        )

    # 船首の係留柱と索で、飛行船の発着場であることを伝える。
    box("mooring_post", (14.5, 3.5, 7), (15.25, 8.25, 8), "post")
    box("mooring_cap", (14.25, 8.25, 6.75), (15.5, 8.6, 8.25), "iron")
    box("mooring_line", (13.6, 6.8, 7.35), (14.75, 7.1, 7.65), "rope")

    # 背後に独立した吹き流し。三段の先細りで風の向きを表す。
    box("windsock_foot", (1, 3.5, 11.5), (3.25, 4.25, 13.75), "spruce")
    box("windsock_mast", (1.75, 4.25, 12.25), (2.5, 15.5, 13), "post")
    box("windsock_pivot", (1.55, 14.5, 12.05), (2.7, 15, 13.2), "iron")
    segments = [
        (
            (2.5, 12.75, 11.75), (4.5, 15.25, 13.5),
            (0, 0, 6, 16),
        ),
        (
            (4.5, 13, 12), (6.5, 15, 13.25),
            (6, 0, 12, 16),
        ),
        (
            (6.5, 13.25, 12.2), (8, 14.5, 13),
            (12, 0, 16, 16),
        ),
    ]
    for i, (a, b, region) in enumerate(segments):
        overrides = {
            f: ("sock", region)
            for f in ("north", "south", "up", "down", "east", "west")
        }
        # 南北どちらからも同じ縞が繋がるよう北面を反転。
        u0, v0, u1, v1 = region
        overrides["north"] = ("sock", (u1, v0, u0, v1))
        if i:
            overrides["west"] = None
        box("windsock_" + str(i), a, b, "sock", overrides)

    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": True,
        "textures": textures,
        "elements": elements,
    }


def _assets(vanilla_dir):
    images = _textures(vanilla_dir)
    models = {
        "blockhutairfield": _hut(),
        "aircraft": {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {
                "top": "airline:block/aircraft_top",
                "side": "airline:block/aircraft_side",
                "bottom": "airline:block/aircraft_bottom",
                "particle": "airline:block/aircraft_side",
            },
        },
    }
    return models, images


def _validate(models, images):
    import math
    import itertools

    hut = models["blockhutairfield"]
    assert len(hut["elements"]) <= 48

    for e in hut["elements"]:
        assert all(
            0 <= a < b <= 16
            for a, b in zip(e["from"], e["to"])
        )
        for f in e["faces"].values():
            assert len(f["uv"]) == 4
            assert all(0 <= n <= 16 for n in f["uv"])
            assert f["texture"].startswith("#")
            assert f["texture"][1:] in hut["textures"]

        if "rotation" in e:
            r = e["rotation"]
            assert r["axis"] in ("x", "y", "z")
            assert r["angle"] in (-45, -22.5, 22.5, 45)
            k = "xyz".index(r["axis"])
            i, j = (k+1) % 3, (k+2) % 3
            c = math.cos(math.radians(r["angle"]))
            s = math.sin(math.radians(r["angle"]))

            # 指定座標だけでなく、回転した八頂点も検査する。
            for corner in itertools.product(*zip(e["from"], e["to"])):
                p = list(corner)
                u = p[i] - r["origin"][i]
                v = p[j] - r["origin"][j]
                p[i] = r["origin"][i] + c*u - s*v
                p[j] = r["origin"][j] + s*u + c*v
                assert all(0 <= n <= 16 for n in p)

    assert sum(n.startswith("airfield_hut_") for n in images) <= 4
    assert {n for n in images if n.startswith("aircraft_")} == {
        "aircraft_top", "aircraft_side", "aircraft_bottom",
    }

    for name, im in images.items():
        assert name.startswith(("airfield_hut_", "aircraft_"))
        assert im.size == (16, 16) and im.mode == "RGBA"
        buf = BytesIO()
        im.save(buf, format="PNG")
        buf.seek(0)
        with Image.open(buf) as reread:
            reread.load()
            assert reread.size == (16, 16)
            assert reread.mode == "RGBA"

    for model in models.values():
        assert json.loads(json.dumps(model)) == model


def build(out_dir: str, vanilla_dir: str) -> None:
    """モデル2個と16x16 RGBAテクスチャ4枚をout_dir以下へ出力する。"""
    models, images = _assets(vanilla_dir)
    _validate(models, images)

    root = Path(out_dir) / "assets" / "airline"
    model_dir = root / "models" / "block"
    texture_dir = root / "textures" / "block"
    model_dir.mkdir(parents=True, exist_ok=True)
    texture_dir.mkdir(parents=True, exist_ok=True)

    for name, model in models.items():
        (model_dir / (name + ".json")).write_text(
            json.dumps(model, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    for name, im in images.items():
        im.save(texture_dir / (name + ".png"), format="PNG")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir")
    parser.add_argument("vanilla_dir")
    args = parser.parse_args()
    build(args.out_dir, args.vanilla_dir)
