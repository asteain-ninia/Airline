"""ブロックモデルの JSON から、同じ平面で重なっている面の組(Z ファイティングでちらつくもの)を洗い出す。

使い方:
    python tools/find_overlaps.py モデル.json [モデル.json ...] [--near 0.02]

    例: python tools/find_overlaps.py src/main/resources/assets/airline/models/block/buoy_*.json
    日本語を出すので、PYTHONIOENCODING=utf-8 を付けて動かすこと。

出すもの(番号は elements の中の順番、0 から):
    同じ向き(ちらつく)        2 つの要素の面が同じ平面にあり、同じ向きで、広がりが重なる組。両方が表から描かれるのでちらつく
    ほぼ同じ平面・同じ向き     平面の差が --near 以下の組(わざと 0.05 などずらした組も、--near を広げると出る)
    向かい合う(触れ合い)      同じ平面で向かい合う組。箱どうしが触れ合っているだけで、面の裏は描かれないので、ちらつかない
「同じ向き」か「ほぼ同じ平面」が 1 組でもあれば、終了コード 1 で終わる。

回転つきの要素(rotation)は、回した後の 4 頂点で平面と形を求めて比べる(rescale も考える)。
JSON にない面(faces から外した面)は数えない。親モデル(parent)の elements はたどらない。
"""
import argparse
import json
import math
import sys

EPS = 1e-6

FACE_DEF = {
    # 面: (軸 0=x 1=y 2=z, 端 0=from 1=to, 外向きの符号)
    "down": (1, 0, -1), "up": (1, 1, 1),
    "north": (2, 0, -1), "south": (2, 1, 1),
    "west": (0, 0, -1), "east": (0, 1, 1),
}


def rot_point(p, rot):
    """Minecraft と同じく、軸の正の向きのまわりに右手回りで回す(x 軸は y→z、y 軸は z→x、z 軸は x→y)。"""
    if not rot:
        return list(p)
    k = "xyz".index(rot["axis"])
    ang = math.radians(rot["angle"])
    o = rot.get("origin", [8, 8, 8])
    q = [p[n] - o[n] for n in range(3)]
    i, j = (k + 1) % 3, (k + 2) % 3
    c, s = math.cos(ang), math.sin(ang)
    u, v = q[i], q[j]
    q[i], q[j] = c * u - s * v, s * u + c * v
    if rot.get("rescale"):
        f = 1.0 / math.cos(math.radians(abs(rot["angle"])))
        q[i] *= f
        q[j] *= f
    return [q[n] + o[n] for n in range(3)]


def rot_vec(v, rot):
    if not rot:
        return list(v)
    r = dict(rot, origin=[0, 0, 0], rescale=False)
    return rot_point(v, r)


def face_quad(e, face):
    """要素 e の面 face の 4 頂点(回した後)と、外向きの法線を返す。"""
    a, b = e["from"], e["to"]
    axis, end, sign = FACE_DEF[face]
    i, j = [n for n in range(3) if n != axis]
    c = (a[axis], b[axis])[end]
    pts = []
    for (u, v) in ((a[i], a[j]), (b[i], a[j]), (b[i], b[j]), (a[i], b[j])):
        p = [0, 0, 0]
        p[axis], p[i], p[j] = c, u, v
        pts.append(rot_point(p, e.get("rotation")))
    n = [0, 0, 0]
    n[axis] = sign
    return pts, rot_vec(n, e.get("rotation"))


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _basis(n):
    t = [1, 0, 0] if abs(n[0]) < 0.9 else [0, 1, 0]
    u = [n[1] * t[2] - n[2] * t[1], n[2] * t[0] - n[0] * t[2], n[0] * t[1] - n[1] * t[0]]
    l = math.sqrt(dot(u, u))
    u = [x / l for x in u]
    v = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]]
    return u, v


def _area(poly):
    s = 0.0
    for k in range(len(poly)):
        x0, y0 = poly[k]
        x1, y1 = poly[(k + 1) % len(poly)]
        s += x0 * y1 - x1 * y0
    return s / 2


def _ccw(poly):
    return poly if _area(poly) >= 0 else poly[::-1]


def _clip(subject, clipper):
    """凸多角形どうしの共通部分(Sutherland-Hodgman)。"""
    out = subject
    for k in range(len(clipper)):
        a, b = clipper[k], clipper[(k + 1) % len(clipper)]
        inp, out = out, []
        if not inp:
            break

        def inside(p):
            return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-12

        def inter(p, q):
            x1, y1, x2, y2 = p[0], p[1], q[0], q[1]
            x3, y3, x4, y4 = a[0], a[1], b[0], b[1]
            d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
            t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
            return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))

        for m in range(len(inp)):
            p, q = inp[m], inp[(m + 1) % len(inp)]
            if inside(q):
                if not inside(p):
                    out.append(inter(p, q))
                out.append(q)
            elif inside(p):
                out.append(inter(p, q))
    return out


def _plane_label(n, d):
    for k, name in enumerate("xyz"):
        if abs(abs(n[k]) - 1) < EPS:
            return "%s=%g" % (name, round(d * n[k], 4))
    return "斜めの平面 n=(%s) d=%g" % (",".join("%.3f" % x for x in n), round(d, 4))


def analyse(model, near):
    """(同じ向き, 向かい合う, ほぼ同じ平面) の 3 つの一覧を返す。"""
    els = model.get("elements", [])
    faces = []
    for idx, e in enumerate(els):
        for f in e.get("faces", {}):
            pts, n = face_quad(e, f)
            faces.append((idx, e.get("name", ""), f, pts, n, e["faces"][f].get("texture")))
    same, opp, close = [], [], []
    for p in range(len(faces)):
        for q in range(p + 1, len(faces)):
            i1, name1, f1, pts1, n1, t1 = faces[p]
            i2, name2, f2, pts2, n2, t2 = faces[q]
            if i1 == i2:
                continue
            c = dot(n1, n2)
            if abs(abs(c) - 1) > 1e-6:
                continue
            d1 = dot(n1, pts1[0])
            gap = abs(d1 - dot(n1, pts2[0]))
            if gap > near + EPS:
                continue
            u, v = _basis(n1)
            a = _ccw([(dot(u, x), dot(v, x)) for x in pts1])
            b = _ccw([(dot(u, x), dot(v, x)) for x in pts2])
            inter = _clip(a, b)
            ar = abs(_area(inter)) if len(inter) >= 3 else 0.0
            if ar < 1e-9:
                continue
            rec = (i1, name1, f1, t1, i2, name2, f2, t2, _plane_label(n1, d1), ar, gap)
            if gap < EPS:
                (same if c > 0 else opp).append(rec)
            elif c > 0:
                close.append(rec)
    return same, opp, close


def label(idx, name, face):
    """「#番号 名前.面」の形。名前のない要素は「#番号.面」。"""
    return "#%d%s.%s" % (idx, " " + name if name else "", face)


def _show(title, recs):
    print("  [%s] %d 組" % (title, len(recs)))
    for (i1, a, f1, t1, i2, b, f2, t2, pl, ar, gap) in recs:
        extra = "" if gap < EPS else "  平面の差 %.4f" % gap
        print("    %s(%s)  と  %s(%s)  平面 %s  重なり %.4f%s"
              % (label(i1, a, f1), t1, label(i2, b, f2), t2, pl, ar, extra))


def main():
    parser = argparse.ArgumentParser(description="同じ平面で重なっている面の組を洗い出す")
    parser.add_argument("models", nargs="+", help="ブロックモデルの JSON")
    parser.add_argument("--near", type=float, default=0.02, help="ほぼ同じ平面とみなす平面の差(既定 0.02)")
    args = parser.parse_args()
    bad = 0
    for path in args.models:
        with open(path, encoding="utf-8") as f:
            model = json.load(f)
        same, opp, close = analyse(model, args.near)
        print("==", path, "要素", len(model.get("elements", [])))
        _show("同じ向き(ちらつく)", same)
        _show("ほぼ同じ平面・同じ向き(差 %g 以下)" % args.near, close)
        _show("向かい合う(触れ合い。ちらつかない)", opp)
        bad += len(same) + len(close)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
