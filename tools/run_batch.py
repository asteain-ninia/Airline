"""専用サーバーの試験を、続けて何本か回す。結果は run-logs に <札>-<名前>-result.txt として残り、<札>-summary.txt に 1 行ずつまとまる。

使い方: python tools/run_batch.py <札> <項目> [<項目> ...]
項目は、Gradle のタスク名(runAirfield、runAirfieldReload、runAirfieldRemoved、runNetwork)か、
runFlight の場面の名前(trip、far、cargo など。reopen で始まる場面は、順番どおりに並べること)。
場面の名前のあとに @<機体の型> を付けると、その型の機体で飛ばす(例: trip@small)。
@ のあとが .json で終わるときは、MOD にまだ入れていない設計のファイルとして読ませ、その設計で飛ばす(例: trip@E:/somewhere/swift.json)。

途中で 1 本が落ちても、次へ進む(reopen の続きの場面だけは、前が落ちたら飛ばす)。

項目に all と書くと、全部の場面(ALL の並び。普通の速さで 3 時間あまり)。
--part 2/3 を付けると、並びを 3 つに分けた 2 つめだけを回す(掛かる時間の見込みが釣り合うように分ける。reopen の続きの場面と、runAirfield の続きの 2 つは分けない)。
分けて別の時に回すときや、2 つの写しで同時に回すとき(tools/run_split.py)に。

--sprint を付けると、サーバーが刻みを追いつく限り速く進める(早回し。2026-10-15)。場面の中で起きることは刻みの数で決まるので
同じだが、地形の読み込みや MineColonies の道探しは刻みと別に進むので、早回しではそれを待つ刻みが増える。
そのため試験の待ちは、刻みの数に加えて、普通の速さなら掛かる時間も過ぎるまで諦めない(Script.pastInRealTime)。
reopen の場面(サーバーを止めて開き直す)は、--sprint でも普通の速さで回る(早回しのまま止めると、止める途中で回り続けて終わらないことがあった)。
"""
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = {"runAirfield": ("run-airfield", 1500), "runAirfieldReload": ("run-airfield", 900), "runAirfieldRemoved": ("run-airfield", 900), "runNetwork": ("run-network", 900)}
# 全部の場面(2026-10-15 の c120m と同じ並び)。中型の機体は、まだ MOD に入れていない仮の設計で飛ばす。
MEDIUM = os.path.join(os.path.dirname(ROOT), "ColonyPatchServer", "design", "classes-2026-10-14", "kokaze_medium.json").replace(os.sep, "/")
ALL = (["classes@" + MEDIUM, "runNetwork", "runAirfield", "runAirfieldReload", "runAirfieldRemoved",
        "yard", "trip", "direct", "three", "traffic", "detour", "cargo", "carry", "far", "faults", "crash", "ground", "lost", "hindered", "fuel", "fetch",
        "reopen1", "reopen2", "reopen3", "reopenc1", "reopenc2", "reopenc3", "reopenc4"]
       + [scene + "@small" for scene in ("trip", "far", "fuel", "traffic", "yard", "cargo", "crash")] + ["trip@whitesail"])


# 1 本に掛かる時間の見込み(秒。早回しの札 sp3、reopen は普通の速さの sp5 と c120m から)。分けるときの重さにだけ使う。
SECONDS = {"classes": 85, "detour": 115, "runAirfield": 55, "reopen1": 100, "reopen2": 115, "reopen3": 50,
           "reopenc1": 70, "reopenc2": 80, "reopenc3": 115, "reopenc4": 155}


def weight(item):
    return SECONDS.get(item.partition("@")[0], 45) + 15


def follows(item):
    """続きの場面(前の場面のワールドを使う): reopen の 2 つめから先、runAirfield のあとの 2 つ。"""
    return (item.startswith("reopen") and item not in ("reopen1", "reopenc1")) or item in ("runAirfieldReload", "runAirfieldRemoved")


def part_of(items, which, parts):
    """items を parts 個の組に分けた which 番め(1 から)。組は、掛かる時間の見込みが釣り合うように、重い順に軽い組へ入れて作る。
    続きの場面(follows)は、前の場面と同じ組に入れる。組の中は、元の並びの順。"""
    units = []
    for at, item in enumerate(items):
        if follows(item) and units:
            units[-1][1].append(item)
        else:
            units.append((at, [item]))
    loads = [0] * parts
    groups = [[] for _ in range(parts)]
    for at, unit in sorted(units, key=lambda u: (-sum(weight(i) for i in u[1]), u[0])):
        lightest = loads.index(min(loads))
        groups[lightest].append((at, unit))
        loads[lightest] += sum(weight(i) for i in unit)
    return [item for at, unit in sorted(groups[which - 1]) for item in unit]


def main():
    sprint = "--sprint" in sys.argv
    args_in = [a for a in sys.argv[1:] if a != "--sprint"]
    part = None
    if "--part" in args_in:
        at = args_in.index("--part")
        which, _, parts = args_in[at + 1].partition("/")
        part = (int(which), int(parts))
        del args_in[at:at + 2]
    label, items = args_in[0], args_in[1:]
    items = [each for item in items for each in (ALL if item == "all" else [item])]
    if part:
        items = part_of(items, *part)
        print("part %d/%d: %s" % (part[0], part[1], " ".join(items)), flush=True)
    whole, passed, checks = time.time(), 0, 0
    logs = os.path.join(ROOT, "run-logs")
    os.makedirs(logs, exist_ok=True)
    summary = os.path.join(logs, label + "-summary.txt")
    chain_broken = False
    for item in items:
        if item in TASKS:
            folder, timeout = TASKS[item]
            args = [item]
        else:
            folder, timeout = ("run-reopen" if item.startswith("reopen") else "run-flight"), 1800
            scene, _, design = item.partition("@")
            args = ["runFlight", "-Pscene=" + scene] + (["-Paircraft=" + design] if design else [])
            if design.endswith(".json"):
                # (プロジェクトのフォルダから見た道で渡す。空白を含む道は、gradlew.bat の引数として通らない。)
                name = os.path.basename(design)[:-5]
                os.makedirs(os.path.join(ROOT, "run-logs", "bench"), exist_ok=True)
                shutil.copyfile(design, os.path.join(ROOT, "run-logs", "bench", name + ".json"))
                args = ["runFlight", "-Pscene=" + scene, "-Pdesign=run-logs/bench/" + name + ".json", "-Paircraft=" + name]
                item = scene + "@" + name
        first_of_chain = item in ("reopen1", "reopenc1")
        if first_of_chain or not item.startswith("reopen"):
            chain_broken = False
        if chain_broken:
            line = "%s SKIPPED (an earlier scene of its chain failed)" % item
        else:
            result = os.path.join(ROOT, folder, "airline-result.txt")
            if os.path.exists(result):
                os.remove(result)
            started = time.time()
            code = subprocess.call([sys.executable, os.path.join(ROOT, "tools", "run_gradle.py"), os.path.join("run-logs", "%s-%s.log" % (label, item)),
                                    "--timeout", str(timeout)] + args + (["-Psprint=on"] if sprint else []), cwd=ROOT)
            head = "(no result file)"
            if os.path.exists(result):
                shutil.copyfile(result, os.path.join(logs, "%s-%s-result.txt" % (label, item)))
                with open(result, encoding="utf-8", errors="replace") as f:
                    head = f.readline().strip()
            line = "%s exit=%d %.0fs %s" % (item, code, time.time() - started, head)
            if code != 0 or not head.startswith("PASS"):
                chain_broken = item.startswith("reopen")
            else:
                passed += 1
                words = head.split()
                checks += int(words[1]) if len(words) > 1 and words[1].isdigit() else 0
        print(line, flush=True)
        with open(summary, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        # 順番待ちの札を、ほかで待っている試験(10 秒ごとに札を見に来る)にも回す。すぐ次を始めると、こちらが札を取り続けてしまう。
        if item is not items[-1]:
            time.sleep(15)
    line = "total: %d of %d PASS, %d checks, %.0f s%s" % (passed, len(items), checks, time.time() - whole, " (sprint)" if sprint else "")
    print(line, flush=True)
    with open(summary, "a", encoding="utf-8") as f:
        f.write(line + "\n")


if __name__ == "__main__":
    main()
