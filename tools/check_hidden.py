"""ブロックモデルの面が外から見えるかどうかを、光線を飛ばして確かめる。

使い方:
    python tools/check_hidden.py モデル.json 要素:面 [要素:面 ...]
    python tools/check_hidden.py モデル.json --all

    要素は、名前(同じ名前の要素はすべて調べる)か、#番号(elements の中の順番、0 から)。
    面は down / up / north / south / west / east。
    例: python tools/check_hidden.py src/main/resources/assets/airline/models/block/buoy_1.json "#14:up" "#14:down"
    --all は、JSON にあるすべての面を調べ、外から見えない面だけを一覧にする(作らなくてよい面を探すとき)。
    日本語を出すので、PYTHONIOENCODING=utf-8 を付けて動かすこと。numpy が要る。

仕組み:
    面の上に点を打ち、それぞれの点から、面の表側の半球へ光線を多数(既定は全球 400 方向のうち表側のもの)飛ばす。
    点は、ほかの要素の境目の座標で面を区切った区画ごとの中心と、等間隔の格子(既定 7×7)に打つ。
    これで、面の縁から少しだけはみ出した細い帯のような所も、点が落ちて調べられる。
    光線は、ほかの要素の面のうち「光線の先にいる人の方を向いた面」に当たると、さえぎられたとみなす
    (裏向きの面は描かれないので、さえぎらない)。すべての光線がさえぎられれば、その面は外から見えない。
    中が囲まれたすき間に向いた面(外へ抜ける光線がない面)も、「見えない」と判定できる。
    見えると分かった時点で打ち切り、外へ抜けた光線の出どころの点を 1 つ示す。

    JSON にない面(faces から外した面)も、要素の形から調べられる(外した面が本当に隠れていたかの確認に使える)。
    回転つきの要素も、回した後の形で調べる。

決まりごと(気をつけること):
    絵の透けている所は考えない(すべての面を不透明として扱う)。透ける絵を使う面がさえぎる側にあるときは、結果を信じすぎないこと。
    ほぼ真横(面とのなす角が約 1 度未満)の光線は飛ばさない。隣のブロックは考えない(モデル 1 つだけで判定する)。
    「見えない」の判定は、点と光線の数が有限なので、ごく細いすき間を見落とすことがありうる。
終了コード: 要素:面 を指定したときは、1 つでも見える面があれば 1。
"""
import argparse
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from find_overlaps import FACE_DEF, face_quad, label  # noqa: E402


def _quads(model):
    """JSON にあるすべての面を (要素の番号, 頂点 4 つ, 法線) にして返す。"""
    out = []
    for idx, e in enumerate(model.get("elements", [])):
        for f in e.get("faces", {}):
            pts, n = face_quad(e, f)
            out.append((idx, np.array(pts, float), np.array(n, float)))
    return out


def _corners(e):
    """要素の 8 頂点(回した後)。"""
    out = []
    for f in ("down", "up"):
        out.extend(face_quad(e, f)[0])
    return np.array(out, float)


def _directions(count):
    """球面上にほぼ一様に並べた count 個の向き(フィボナッチ格子)。"""
    k = np.arange(count) + 0.5
    phi = np.arccos(1 - 2 * k / count)
    theta = math.pi * (1 + 5 ** 0.5) * k
    return np.stack([np.cos(theta) * np.sin(phi), np.sin(theta) * np.sin(phi), np.cos(phi)], axis=1)


def _breaks(model, idx, a, b, n, grid):
    """面の一辺(a から a+b)を、等間隔の格子と、ほかの要素の境目で区切る位置(0〜1)。"""
    length = float(np.linalg.norm(b))
    u = b / length
    cuts = set(np.linspace(0, 1, grid + 1).tolist())
    plane = float(n @ a)
    for k, e in enumerate(model.get("elements", [])):
        if k == idx:
            continue
        c = _corners(e)
        if (c @ n).max() < plane - 1e-6:   # 面の裏側にしかない要素は、境目に使わない
            continue
        for x in ((c - a) @ u) / length:
            if 1e-6 < x < 1 - 1e-6:
                cuts.add(float(x))
    cuts = sorted(cuts)
    return [(p + q) / 2 for p, q in zip(cuts, cuts[1:]) if q - p > 1e-6]


def check_face(model, idx, face, grid=7, dirs=400, quads=None):
    """要素 idx の面 face を調べる。(見えるか, 飛ばした光線の数, 外へ抜けた点) を返す。"""
    e = model["elements"][idx]
    pts, n = face_quad(e, face)
    pts = np.array(pts, float)
    n = np.array(n, float)
    plane = float(n @ pts[0])
    quads = [q for q in (quads if quads is not None else _quads(model))
             if q[0] != idx and (q[1] @ n).max() > plane + 1e-9]   # 面より手前に出ている面だけが光線をさえぎりうる
    a, b, d = pts[0], pts[1] - pts[0], pts[3] - pts[0]
    ss = _breaks(model, idx, a, b, n, grid)
    ts = _breaks(model, idx, a, d, n, grid)
    D = _directions(dirs)
    D = D[D @ n > math.sin(math.radians(1))]                       # 表側の半球だけ
    total = 0
    if not quads:
        return True, len(D), a + 0.5 * b + 0.5 * d
    V = np.stack([q[1] for q in quads])          # (N, 4, 3)
    N = np.stack([q[2] for q in quads])          # (N, 3)
    E = np.roll(V, -1, axis=1) - V               # 各辺 (N, 4, 3)
    S = np.sign(np.einsum("ij,ij->i", np.cross(V[:, 1] - V[:, 0], V[:, 2] - V[:, 0]), N))
    denom = D @ N.T                              # (M, N) 光線の向きと面の法線
    front = denom > 1e-9                         # 光線の先にいる人の方を向いた面だけ
    for s in ss:
        for t in ts:
            P = a + s * b + t * d
            num = np.einsum("ij,ij->i", V[:, 0] - P, N)[None, :]
            with np.errstate(divide="ignore", invalid="ignore"):
                tt = num / denom
            hit = front & (tt > 1e-6)
            H = P[None, None, :] + tt[:, :, None] * D[:, None, :]
            inside = np.ones(hit.shape, bool)
            for k in range(4):
                c = np.cross(E[None, :, k, :], H - V[None, :, k, :])
                inside &= (np.einsum("mnj,nj->mn", c, N) * S[None, :]) >= -1e-7
            total += len(D)
            if not np.all(np.any(hit & inside, axis=1)):
                return True, total, P
    return False, total, None


def _targets(model, spec):
    name, face = spec.rsplit(":", 1)
    if face not in FACE_DEF:
        sys.exit("面の名前が違う: %s(down/up/north/south/west/east のどれか)" % spec)
    els = model["elements"]
    if name.startswith("#"):
        k = int(name[1:])
        if not 0 <= k < len(els):
            sys.exit("その番号の要素はない: %s" % spec)
        return [(k, face)]
    found = [(k, face) for k, e in enumerate(els) if e.get("name") == name]
    if not found:
        sys.exit("その名前の要素はない: %s" % spec)
    return found


def _fmt(p):
    return "(%s)" % ", ".join("%g" % round(float(x), 3) for x in p)


def main():
    parser = argparse.ArgumentParser(description="ブロックモデルの面が外から見えるかを、光線で確かめる")
    parser.add_argument("model", help="ブロックモデルの JSON")
    parser.add_argument("faces", nargs="*", help="要素:面(要素は名前か #番号)")
    parser.add_argument("--all", action="store_true", help="JSON にあるすべての面を調べ、見えない面を一覧にする")
    parser.add_argument("--grid", type=int, default=7, help="等間隔に打つ点の数(一辺あたり、既定 7)")
    parser.add_argument("--dirs", type=int, default=400, help="光線の向きの数(全球で、既定 400)")
    args = parser.parse_args()
    with open(args.model, encoding="utf-8") as f:
        model = json.load(f)
    quads = _quads(model)
    if args.all:
        hidden = 0
        for k, e in enumerate(model.get("elements", [])):
            for face in e.get("faces", {}):
                vis, tot, _ = check_face(model, k, face, args.grid, args.dirs, quads)
                if not vis:
                    hidden += 1
                    print("%s  外から見えない(光線 %d 本すべてさえぎられた)" % (label(k, e.get("name", ""), face), tot))
        print("見えない面 %d 枚" % hidden)
        return
    if not args.faces:
        parser.error("要素:面 を 1 つ以上書くか、--all を付けること")
    bad = 0
    for spec in args.faces:
        for k, face in _targets(model, spec):
            vis, tot, p = check_face(model, k, face, args.grid, args.dirs, quads)
            name = model["elements"][k].get("name", "")
            present = "" if face in model["elements"][k].get("faces", {}) else "(JSON にはない面)"
            if vis:
                print("%s%s  見える(点 %s から外へ抜ける光線がある)" % (label(k, name, face), present, _fmt(p)))
                bad += 1
            else:
                print("%s%s  外から見えない(光線 %d 本すべてさえぎられた)" % (label(k, name, face), present, tot))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
