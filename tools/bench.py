"""機体の設計を 1 つ、試験場で測る。

使い方: python tools/bench.py <設計の .json、または MOD に入っている型の名前> [--out <結果の .json>] [--mode all|raw|static] [--log <札>]

専用サーバーを 1 回起動して、設計どおりの機体を 1 機つくり、機体のレバーだけで動かして測り、結果を JSON に書く。
- static: 台の上で、質量・重心・気嚢の見え方だけ(約 1 分)
- raw:    それに加えて、上昇、前進、惰性、後進、制動、その場の旋回(約 4 分)
- all:    それに加えて、進みながらの旋回と、MOD の自動操縦での直進・旋回・位置合わせ(約 8 分。既定)
- dock:   連結器の確かめ(設計に、下向きの simulated:docking_connector が 1 つ要る)。上昇のあと、機体の連結器の真下に世界の連結器を置き、
          つながるか・推進とバーナーを切って落ちないか・信号を切ると外れるかを見る
- legs:   上昇のあと、すぐ自動操縦の課題へ(操縦の数字は、型のファイルに書いてあるまま。--leg・--offset と合わせて、線の保ち方を見る)

結果の JSON の "ok" が false のときは、"error" にわけがある(設計が読めない、ブロックが置けない、気嚢が見えない、など)。
終了コード: 0 = 結果が書けた(ok が false でも 0)。1 = サーバーが結果を書かずに終わった(ログを見る)。
"""
import argparse
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("design")
    ap.add_argument("--out", default="")
    ap.add_argument("--mode", default="all", choices=["all", "raw", "static", "legs", "dock"])
    ap.add_argument("--log", default="bench")
    ap.add_argument("--leg", type=int, default=80, help="自動操縦の直進の長さ(ブロック)。長い線を保てるかを見るときに延ばす")
    ap.add_argument("--offset", type=float, default=0.0, help="直進の線を、機体の右へこれだけずらして始める(線への乗り方を見る)")
    ap.add_argument("--outline", default="", help="枠(前後 9 × 左右 7 × 高さ 9)より大きい設計を測るときの、試験場だけの枠。「機首側x尾側x片側の幅x高さ」(例 5x7x4x12)。MOD には、そのままでは入れられない")
    ap.add_argument("--fast", action="store_true", help="遅い機体にも、速い機体の止まり方(制動、終わりぎわの寄せ)を当てる。自動操縦のその部分の確かめ用")
    a = ap.parse_args()
    is_file = a.design.endswith(".json")
    name = os.path.basename(a.design)[:-5] if is_file else a.design
    # Gradle へは、プロジェクトのフォルダから見た道で渡す(空白を含む道は、gradlew.bat の引数として通らない)。
    work = os.path.join("run-logs", "bench")
    os.makedirs(os.path.join(ROOT, work), exist_ok=True)
    made = os.path.join(work, "%s-%s-report.json" % (a.log, name))
    if os.path.exists(os.path.join(ROOT, made)):
        os.remove(os.path.join(ROOT, made))
    args = ["runBench", "-PbenchOut=" + made.replace(os.sep, "/"), "-PbenchMode=" + a.mode]
    if a.fast:
        args.append("-PbenchFast=true")
    if a.leg != 80:
        args.append("-PbenchLeg=%d" % a.leg)
    if a.offset:
        args.append("-PbenchOffset=%s" % a.offset)
    if a.outline:
        args.append("-PbenchOutline=" + a.outline)
    if is_file:
        shutil.copyfile(a.design, os.path.join(ROOT, work, name + ".json"))
        args.append("-Pdesign=" + os.path.join(work, name + ".json").replace(os.sep, "/"))
    else:
        args.append("-Paircraft=" + name)
    started = time.time()
    code = subprocess.call([sys.executable, os.path.join(ROOT, "tools", "run_gradle.py"), os.path.join("run-logs", "%s-%s.log" % (a.log, name)),
                            "--timeout", "1800"] + args, cwd=ROOT)
    out = os.path.abspath(a.out) if a.out else os.path.join(ROOT, made)
    ok = os.path.exists(os.path.join(ROOT, made))
    if ok and a.out:
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copyfile(os.path.join(ROOT, made), out)
    print("bench %s: exit=%d %.0fs %s" % (name, code, time.time() - started, out if ok else "(no report)"), flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
