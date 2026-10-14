"""建築ツールのカテゴリーの絵と、パックの絵を作る(2026-10-14 夜のご裁定 A: パックは Airline のまま、絵を付ける)。

    python tools/make_pack_icons.py <MineColonies の jar>

- infrastructure/icon.png・icon_disabled.png: MineColonies Original の「infrastructure」と同じ絵(どちらも GPL-3.0)。
- aircraft/icon.png・icon_disabled.png: 16 x 16 の気球(ここで描く)。選ばれていないときの絵は、MineColonies と同じく暗くしたもの。
- pack.png: 飛行場の小屋ブロックの吹き流しの絵を、128 x 128 に拡大したもの。
"""
import io
import os
import sys
import zipfile

from PIL import Image, ImageEnhance

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "src", "main", "resources", "blueprints", "airline", "airfield")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "airline", "textures", "block")

with zipfile.ZipFile(sys.argv[1]) as jar:
    for name in ("icon.png", "icon_disabled.png"):
        data = jar.read("blueprints/minecolonies/original/infrastructure/" + name)
        io.open(os.path.join(PACK, "infrastructure", name), "wb").write(data)
        print("wrote infrastructure/" + name)

# a balloon over a small gondola, 16 x 16
CANVAS, ENVELOPE, SHADE, ROPE, HULL, OUTLINE = (0, 0, 0, 0), (236, 232, 222, 255), (196, 188, 172, 255), (92, 70, 50, 255), (140, 96, 58, 255), (60, 44, 32, 255)
rows = [
    "................",
    ".....oooooo.....",
    "...ooEEEEEEoo...",
    "..oEEEEEEEEESo..",
    ".oEEEEEEEEEESSo.",
    ".oEEEEEEEEEESSo.",
    ".oEEEEEEEEEESSo.",
    "..oEEEEEEEEESo..",
    "...ooEEEEEEoo...",
    ".....oooooo.....",
    "......r..r......",
    ".....r....r.....",
    "....ooooooooo...",
    "....oHHHHHHHo...",
    ".....ooooooo....",
    "................",
]
colors = {".": CANVAS, "o": OUTLINE, "E": ENVELOPE, "S": SHADE, "r": ROPE, "H": HULL}
icon = Image.new("RGBA", (16, 16))
for y, row in enumerate(rows):
    for x, ch in enumerate(row):
        icon.putpixel((x, y), colors[ch])
icon.save(os.path.join(PACK, "aircraft", "icon.png"))
ImageEnhance.Brightness(icon).enhance(0.55).save(os.path.join(PACK, "aircraft", "icon_disabled.png"))
print("wrote aircraft/icon.png, icon_disabled.png")

hut = Image.open(os.path.join(TEX, "airfield_hut_windsock.png")).convert("RGBA").crop((0, 0, 16, 16))
hut.resize((128, 128), Image.NEAREST).save(os.path.join(PACK, "pack.png"))
print("wrote pack.png")
