"""飛行士のスキンの絵。MineColonies の村人のスキン(128×64)の上に、飛行士の服を描く。

絵を描いたのは Codex(gpt-6-astra)。依頼文と答えは、ColonyPatchServer の design/pilot-skin-2026-10-11/codex/ にある
(brief.md、answer-1.md)。このファイルは、その答えのコードを、そのまま置いたもの(この先頭の説明だけ足した)。

使う側: tools/make_pilot_skins.py(ゲームに入れる絵を書き出す)、design/review-2026-10-11/pilot.py(ビューアー)。
"""
"""MineColonies の飛行士スキン。依存は Pillow のみ。"""
from PIL import Image


# 素材ごとに4階調。砂目ではなく、面の向きと折れに沿って配置する。
PAL = {
    # 上着の革
    "a": "#503a2e", "b": "#71503b", "c": "#8d674a", "d": "#a6805a",
    # 革の帯・手首
    "e": "#42362e", "f": "#5b4837", "g": "#786047", "h": "#927859",
    # カーキの布
    "i": "#575744", "j": "#707058", "k": "#89866a", "l": "#a29b7b",
    # 手袋・長靴
    "m": "#38332d", "n": "#504438", "o": "#695744", "p": "#826b50",
    # 羊毛：白いマフラーより暖色
    "q": "#a49a80", "r": "#c4b99c", "s": "#ded4bb", "t": "#eee5ce",
    # マフラー：影に少し灰緑を含む生成り
    "u": "#a8aaa0", "v": "#c9cbbf", "w": "#e3e5d9", "x": "#f3f2e7",
    # 古びた真鍮と、彩度を抑えたガラス
    "B": "#79613e", "C": "#ac8e54", "D": "#c6ac73",
    "E": "#526965", "F": "#819c96", "G": "#c6d7ca",
}
PAL = {
    key: tuple(bytes.fromhex(value[1:])) + (255,)
    for key, value in PAL.items()
}


def paint(base: "Image.Image", female: bool) -> "Image.Image":
    """服を描いた新しい128×64 RGBA画像を返す。baseは変更しない。"""
    if base.size != (128, 64):
        raise ValueError("base must be a 128x64 skin")

    source = base.convert("RGBA")

    # 透明から作ることで、元の髪の外層や職業用パーツを残さない。
    out = Image.new("RGBA", (128, 64))
    px = out.load()

    def tile(x, y, rows, palette=PAL):
        """文字1個が1画素。空白は行区切り、ピリオドは描画しない。"""
        rows = rows.split() if isinstance(rows, str) else rows
        width = len(rows[0])
        if any(len(row) != width for row in rows):
            raise ValueError("Unequal pixel-art row widths")
        for dy, row in enumerate(rows):
            for dx, key in enumerate(row):
                if key != ".":
                    px[x + dx, y + dy] = palette[key]

    def fill(x, y, w, h, key):
        out.paste(PAL[key], (x, y, x + w, y + h))

    def box(u, v, w, h, d, top, bottom, sides):
        """Minecraftの箱UV。側面の順序は右・前・左・後ろ。"""
        tile(u + d, v, top)
        tile(u + d + w, v, bottom)
        offsets = (u, u + d, u + d + w, u + 2 * d + w)
        for x, rows in zip(offsets, sides):
            tile(x, v + d, rows)

    # 肌は描き直さない。顔と頭下面をアルファごと正確にコピー。
    out.paste(source.crop((8, 8, 16, 16)), (8, 8))
    out.paste(source.crop((16, 0, 24, 8)), (16, 0))

    # 飛行帽：縫い合わせた天面と、厚みのある耳当て。
    tile(8, 0, """
        bbccbccb
        bcccdccb
        cddcdccc
        cddcdccc
        cddcdccc
        ccccdccb
        bcccbccb
        bbccbcbb
    """)
    tile(8, 8, """
        bccbdccb
        cddcdccb
        cccbdccb
        bbbabbbb
    """)
    tile(8, 12, "b c b a")
    tile(15, 12, "b c b a")

    cap_side = """
        bcccccbb
        cddddccb
        ccccccbb
        bbccbbaa
        bccdcbaa
        bcdccbaa
        bcccbaaa
        abbbbaaa
    """
    tile(0, 8, cap_side)
    tile(16, 8, [row[::-1] for row in cap_side.split()])
    tile(24, 8, """
        bccbdccb
        cccbdccc
        cccbdccc
        bccbdccb
        bccbdccb
        bbbabbbb
        abbbabba
        aaaaaaaa
    """)

    # 額に上げたゴーグル。前面の外層はy=8..11だけを使う。
    tile(40, 8, """
        .DD..DD.
        CGFBCGFB
        BEEBBEEB
        .BB..BB.
    """)
    tile(32, 9, "ffffffff gfffffff")
    tile(48, 9, "ffffffff fffffffg")
    tile(56, 9, "ffffffff fffBCfff")
    tile(33, 10, "C")
    tile(54, 10, "C")

    # 上着：羊毛の折り返し、前合わせ、ポケット、細い腰ベルト。
    front = """
        crvwwrsc
        crsuvsrc
        ccrvsrcc
        bcrtvcdb
        bccavcdb
        bdcaBcdb
        babaccab
        bdcaBddb
        bccaccbb
        ffgBCgff
        aaabBaaa
        bbccccbb
    """
    back = """
        crrssrrc
        cccccccc
        bddddddb
        bbccccbb
        bccbbccb
        bccbbccb
        bccbbccb
        bcdbbdcb
        bccbbccb
        ffgffgff
        aaaaaaaa
        bbccccbb
    """
    side = """
        csrc
        brrb
        bccb
        abcb
        abcb
        abcb
        abcb
        abcb
        abbb
        ffgf
        aaaa
        bbbb
    """
    box(
        16, 16, 8, 12, 4,
        top="""
            ccrssrcc
            ccrwwrcc
            ccrvvrcc
            ccrssrcc
        """,
        bottom="""
            aaaabaaa
            bbbbbbbb
            bbbbbbbb
            aaaabaaa
        """,
        sides=(
            side,
            front,
            [row[::-1] for row in side.split()],
            back,
        ),
    )

    # マフラーの外層：首元の結び目と、重なって短く垂れる端。
    tile(22, 32, "vwwv wxxw wxxw vwwv")
    tile(22, 36, """
        vxxw
        .wxv
        .vw.
        .vw.
        .vu.
    """)
    tile(24, 38, "xw wv vu")
    tile(34, 36, "vwwv")
    tile(19, 36, "v")
    tile(28, 36, "v")

    # ポケットのふたにだけ、外層でわずかな厚みを付ける。
    tile(21, 42, "dc")
    tile(25, 42, "dc")

    # 袖は縦の光と肘の折れで描く。主要な意匠は最初の3列に収める。
    sleeve_front = """
        cddc
        cddc
        bcdc
        bcdc
        bccb
        abbc
        bcdb
        bccb
        aqqr
        fgff
        noom
        mnmm
    """
    sleeve_outer = """
        bccb
        bcdc
        bcdc
        bccb
        bbcb
        abbb
        bcbb
        bbcb
        aqqr
        ffff
        noon
        mnmm
    """
    sleeve_inner = """
        abbb
        abcb
        abcb
        abbb
        aabb
        aaab
        abbb
        abbb
        aqqr
        ffff
        nnnm
        mmmm
    """
    sleeve_back = """
        bccb
        bddc
        bccb
        bccb
        bcbb
        abbb
        bccb
        bbcb
        aqqr
        ffff
        noon
        mnmm
    """
    for u, v in ((40, 16), (32, 48)):
        box(
            u, v, 4, 12, 4,
            top="bccc cddc cddc bccb",
            bottom="mnnm noon nnnm mmmm",
            sides=(
                sleeve_outer, sleeve_front, sleeve_inner, sleeve_back
            ),
        )

    # ズボンは長い折れ。長靴には履き口・足首のしわ・爪先・靴底。
    leg_front = """
        jkki
        jkli
        jkki
        kjji
        jjki
        opoo
        nnmn
        noon
        nonn
        nmnn
        oppo
        mmmm
    """
    leg_outer = """
        ijji
        jkji
        jkji
        ijji
        ijji
        opon
        nnnm
        nonm
        nonm
        nmnm
        noon
        mmmm
    """
    leg_inner = """
        ijji
        ijji
        ijji
        iiji
        iiij
        onnn
        nmnm
        nnnm
        nnnm
        nmnm
        nnmm
        mmmm
    """
    leg_back = """
        ijji
        jkki
        jkki
        ijji
        jiij
        oooo
        nnnn
        nonn
        nonn
        nmnn
        noon
        mmmm
    """
    for u, v, mirror in ((0, 16, False), (16, 48, True)):
        sides = [leg_outer, leg_front, leg_inner, leg_back]
        if mirror:
            sides = [leg_inner, leg_front, leg_outer, leg_back]
            sides = [
                [row[::-1] for row in face.split()]
                for face in sides
            ]
        box(
            u, v, 4, 12, 4,
            top="ijji jkki jkki ijji",
            bottom="mmmm mnnm mnnm mmmm",
            sides=sides,
        )

    if female:
        # 元の髪色を基準に、小さな明度差だけで束の流れを出す。
        hair = source.getpixel((10, 3))[:3]

        def hair_tone(delta):
            return tuple(
                max(0, min(255, channel + delta))
                for channel in hair
            ) + (255,)

        hp = {
            str(i): hair_tone(delta)
            for i, delta in enumerate((-9, 0, 9, 18))
        }
        tile(25, 14, "011110 012210", hp)

        # 胸の8×3×3箱。指定された22×6領域内に収める。
        # 突き出す前面にも、上着の合わせをつなげる。
        box(
            64, 49, 8, 3, 3,
            top="bccavcdb bccavcdb bccavcdb",
            bottom="abbabbba aaabaaaa aaabaaaa",
            sides=(
                "abc abc abb",
                "bccavcdb bdcaBcdb bccaccbb",
                "cba cba bba",
                "bbbbbbbb bbbbbbbb aaaaaaaa",
            ),
        )

        def hair_box(u, v, w, h, d, top, bottom, sides):
            tile(u + d, v, top, hp)
            tile(u + d + w, v, bottom, hp)
            offsets = (u, u + d, u + d + w, u + 2 * d + w)
            for x, rows in zip(offsets, sides):
                tile(x, v + d, rows, hp)

        # 後ろ髪の2×5×2箱。未使用のUV隅は透明のまま。
        hair_box(
            86, 48, 2, 5, 2,
            top="12 12",
            bottom="01 00",
            sides=(
                "01 12 12 01 01",
                "23 23 12 12 01",
                "10 21 21 10 10",
                "12 23 12 12 01",
            ),
        )
        fill(86, 52, 8, 1, "f")  # 束を留める細い革ひも。

        # 毛先の1×5×1箱。
        hair_box(
            88, 55, 1, 5, 1,
            top="2",
            bottom="0",
            sides=(
                "1 1 1 0 0",
                "2 3 2 2 1",
                "1 2 1 1 0",
                "2 2 2 1 1",
            ),
        )

    return out
