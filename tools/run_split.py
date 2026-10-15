"""全部(または指定の項目)を 2 つの写しで同時に回す(2026-10-15)。サーバーは 2 台まで(run_gradle.py の札)。

    python tools/run_split.py <札> [--sprint] [--no-sync] <写し 1> <写し 2> [<項目> ...]

- 項目を省くと all(run_batch.py の ALL)。run_batch.py の --part 1/2 と 2/2 を、それぞれの写しで回す。
- 始める前に、このプロジェクトのソースと道具を 2 つの写しへ写す(--no-sync で写さない)。
- 結果は、それぞれの写しの run-logs/<札>-1-*.txt、<札>-2-*.txt。最後に、2 つの summary を並べて、合計の行を出す。
"""
import os
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARENT = os.path.dirname(ROOT)


def main():
    args = sys.argv[1:]
    flags = [a for a in args if a in ("--sprint", "--no-sync")]
    rest = [a for a in args if a not in flags]
    if len(rest) < 3:
        print(__doc__)
        return 2
    label, copies, items = rest[0], rest[1:3], rest[3:] or ["all"]
    if "--no-sync" not in flags:
        code = subprocess.call([sys.executable, os.path.join(ROOT, "tools", "sync_copy.py")] + copies)
        if code != 0:
            return code
    started = time.time()
    runs = []
    for i, name in enumerate(copies, 1):
        target = os.path.join(PARENT, name)
        out = open(os.path.join(target, "run-logs", "%s-%d-batch.out" % (label, i)), "w", encoding="utf-8")
        cmd = [sys.executable, os.path.join(target, "tools", "run_batch.py"), "%s-%d" % (label, i), "--part", "%d/2" % i] + \
              (["--sprint"] if "--sprint" in flags else []) + items
        runs.append((name, target, subprocess.Popen(cmd, cwd=target, stdout=out, stderr=subprocess.STDOUT), out))
        print("started part %d/2 in %s" % (i, name), flush=True)
        time.sleep(20)   # (2 つの Gradle が、同じ瞬間に組み立てを始めないように)
    passed = total = checks = 0
    for i, (name, target, proc, out) in enumerate(runs, 1):
        proc.wait()
        out.close()
        summary = os.path.join(target, "run-logs", "%s-%d-summary.txt" % (label, i))
        lines = open(summary, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(summary) else []
        print("== part %d/2 (%s)" % (i, name))
        for line in lines:
            print(line)
            if line.startswith("total: "):
                words = line.split()
                passed += int(words[1])
                total += int(words[3])
                checks += int(words[5])
    print("total of both: %d of %d PASS, %d checks, %.0f s%s" % (passed, total, checks, time.time() - started, " (sprint)" if "--sprint" in flags else ""))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
