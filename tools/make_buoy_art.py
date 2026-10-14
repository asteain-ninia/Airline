"""空路ブイの見た目(モデルと絵)を書き出す。

絵とモデルを作るのは buoy_art_painter.py(Codex〈gpt-6-astra〉が、要件だけから形を考えて書いた。依頼文と返事は
ColonyPatchServer の design/buoy-art-2026-10-12/codex/)。このスクリプトは、それを呼ぶだけ。

形: 生成りの帆布の浮き袋の下に、トウヒの木枠で囲んだ灯室。等級は、輪郭で見分ける
(等級 1 は細い袋が 1 つ、等級 2 は横に広い平たい袋、等級 3 は四隅に分かれた 4 つの袋)。袋の帯は、銅・金・青緑。
灯室の四方と下と、てっぺんの小窓が、消灯では暗い青灰色、点灯では琥珀色。

書き出すもの(src/main/resources/assets/airline の下):
    models/block/buoy_{1,2,3}.json、buoy_{1,2,3}_lit.json
    textures/block/buoy_canvas.png、buoy_copper.png、buoy_gold.png、buoy_diamond.png、buoy_off.png、buoy_on.png

当たり判定(route/BuoyBlock.java の shape)は、この形に合わせて手で書いてある。形を変えたら、そちらも直す。

使い方: python tools/make_buoy_art.py      (Pillow が要る)
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import buoy_art_painter  # noqa: E402


def main():
    # 2 つめの引数(バニラの絵のフォルダ)は、呼び出しの形を合わせるためだけのもの。色は、スクリプトの中に決めてある
    buoy_art_painter.build(os.path.join(ROOT, "src", "main", "resources"), "")
    base = os.path.join(ROOT, "src", "main", "resources", "assets", "airline")
    for tier in (1, 2, 3):
        for lit in ("", "_lit"):
            path = "models/block/buoy_%d%s.json" % (tier, lit)
            print("wrote", path, os.path.getsize(os.path.join(base, *path.split("/"))), "bytes")
    for name in ("canvas", "copper", "gold", "diamond", "off", "on"):
        path = "textures/block/buoy_%s.png" % name
        print("wrote", path, os.path.getsize(os.path.join(base, *path.split("/"))), "bytes")


if __name__ == "__main__":
    main()
