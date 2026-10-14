"""飛行士のスキンを、ゲームに入れる形で書き出す。

MineColonies は、村人の絵を「見た目の種類の名前 + 男女 + 番号 + 肌の色」の名前で、自分の名前空間の中に探す
(ISimpleModelType#getTexture。名前空間 minecolonies は決め打ち)。飛行士の見た目の種類は airline:pilot なので、絵は

    assets/minecolonies/textures/entity/citizen/default/pilot{male,female}1_{a,b,d,w}.png

に置く(村の様式ごとのフォルダに絵が無いときは、default が使われる)。村人の一覧に出る顔の絵は、様式ごとに探されて、
default へは戻らないので、全部の様式のフォルダに同じものを置く:

    assets/minecolonies/textures/entity_icon/citizen/<様式>/pilot{male,female}1_{a,b,d,w}.png

土台にする絵は、MineColonies の村人(citizen)の番号 1。顔と肌の色は、それをそのまま残す。服の絵は tools/pilot_skin_painter.py。

使い方: python tools/make_pilot_skins.py
Pillow が要る。
"""
import io
import os
import zipfile

from PIL import Image

import pilot_skin_painter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINECOLONIES_JAR = os.path.join(ROOT, "..", "ColonyPatchServer", "libs", "minecolonies-1.1.1368-1.21.1.jar")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecolonies", "textures")
SUFFIXES = ("_a", "_b", "_d", "_w")
STYLES = ("default", "eastasian", "hellenic", "medieval", "modern", "nether", "nordic", "undead")


def icon(skin):
    """顔の絵(16×16): 頭の前の面に、外側の層(ゴーグル)を重ねて、2 倍にする。MineColonies の顔の絵と同じ大きさ。"""
    face = skin.crop((8, 8, 16, 16))
    face.alpha_composite(skin.crop((40, 8, 48, 16)))
    return face.resize((16, 16), Image.NEAREST)


def main():
    count = 0
    with zipfile.ZipFile(MINECOLONIES_JAR) as jar:
        for sex in ("male", "female"):
            for suffix in SUFFIXES:
                base = Image.open(io.BytesIO(jar.read("assets/minecolonies/textures/entity/citizen/default/citizen%s1%s.png" % (sex, suffix)))).convert("RGBA")
                skin = pilot_skin_painter.paint(base, sex == "female")
                name = "pilot%s1%s.png" % (sex, suffix)
                path = os.path.join(OUT, "entity", "citizen", "default", name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                skin.save(path, optimize=True)
                face = icon(skin)
                for style in STYLES:
                    path = os.path.join(OUT, "entity_icon", "citizen", style, name)
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    face.save(path, optimize=True)
                count += 1
    print("wrote", count, "skins and", count * len(STYLES), "icons under", OUT)


if __name__ == "__main__":
    main()
