"""専用サーバーの試験を、続けて何本か回す。結果は run-logs に <札>-<名前>-result.txt として残り、<札>-summary.txt に 1 行ずつまとまる。

使い方: python tools/run_batch.py <札> <項目> [<項目> ...]
項目は、Gradle のタスク名(runAirfield、runAirfieldReload、runAirfieldRemoved、runNetwork)か、
runFlight の場面の名前(trip、far、cargo など。reopen で始まる場面は、順番どおりに並べること)。
場面の名前のあとに @<機体の型> を付けると、その型の機体で飛ばす(例: trip@small)。
@ のあとが .json で終わるときは、MOD にまだ入れていない設計のファイルとして読ませ、その設計で飛ばす(例: trip@E:/somewhere/swift.json)。

途中で 1 本が落ちても、次へ進む(reopen の続きの場面だけは、前が落ちたら飛ばす)。
"""
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = {"runAirfield": ("run-airfield", 1500), "runAirfieldReload": ("run-airfield", 900), "runAirfieldRemoved": ("run-airfield", 900), "runNetwork": ("run-network", 900)}


def main():
    label, items = sys.argv[1], sys.argv[2:]
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
                                    "--timeout", str(timeout)] + args, cwd=ROOT)
            head = "(no result file)"
            if os.path.exists(result):
                shutil.copyfile(result, os.path.join(logs, "%s-%s-result.txt" % (label, item)))
                with open(result, encoding="utf-8", errors="replace") as f:
                    head = f.readline().strip()
            line = "%s exit=%d %.0fs %s" % (item, code, time.time() - started, head)
            if code != 0 or not head.startswith("PASS"):
                chain_broken = item.startswith("reopen")
        print(line, flush=True)
        with open(summary, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        # 順番待ちの札を、ほかで待っている試験(10 秒ごとに札を見に来る)にも回す。すぐ次を始めると、こちらが札を取り続けてしまう。
        if item is not items[-1]:
            time.sleep(15)


if __name__ == "__main__":
    main()
