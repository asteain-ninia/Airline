"""機体ブロックと、飛行場の小屋ブロックの見た目(モデルと絵)を書き出す。

絵とモデルを作るのは block_art_painter.py(Codex〈gpt-6-astra〉が書いた。依頼文と返事は
ColonyPatchServer の design/block-art-2026-10-12/codex/)。このスクリプトは、それを呼ぶだけ。

書き出すもの(src/main/resources/assets/airline の下):
    models/block/blockhutairfield.json   小屋ブロック: 板張りの桟橋に、白い小さな飛行船と、係留柱と、吹き流し
    models/block/aircraft.json           機体ブロック: 立方体のまま、絵を 3 枚
    textures/block/aircraft_top.png      甲板のオークに、真鍮の方位盤(矢は機首 = 東)
    textures/block/aircraft_side.png     オークの梁に、真鍮の帯と、翼の印
    textures/block/aircraft_bottom.png   板と、鉄の帯
    textures/block/airfield_hut_windsock.png   吹き流し(赤と白)

使い方: python tools/make_block_art.py
オークの板の色を拾うために、バニラの絵(oak_planks.png)を、ビルドで取ってある Minecraft の jar から読む
(build/moddev/artifacts の下。一度ビルドしてあること)。Pillow が要る。
"""
import glob
import os
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import block_art_painter  # noqa: E402


def main():
    jars = glob.glob(os.path.join(ROOT, "build", "moddev", "artifacts", "*client-extra*.jar"))
    if not jars:
        sys.exit("Minecraft の絵の入った jar が見つからない(build/moddev/artifacts/*client-extra*.jar)。先に一度ビルドすること。")
    with tempfile.TemporaryDirectory() as vanilla:
        with zipfile.ZipFile(jars[0]) as z:
            with open(os.path.join(vanilla, "oak_planks.png"), "wb") as f:
                f.write(z.read("assets/minecraft/textures/block/oak_planks.png"))
        block_art_painter.build(os.path.join(ROOT, "src", "main", "resources"), vanilla)
    base = os.path.join(ROOT, "src", "main", "resources", "assets", "airline")
    for path in ("models/block/blockhutairfield.json", "models/block/aircraft.json", "textures/block/aircraft_top.png", "textures/block/aircraft_side.png",
                 "textures/block/aircraft_bottom.png", "textures/block/airfield_hut_windsock.png"):
        print("wrote", path, os.path.getsize(os.path.join(base, *path.split("/"))), "bytes")


if __name__ == "__main__":
    main()
