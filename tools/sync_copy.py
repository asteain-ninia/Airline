"""試験用の写し(Airline-testcopy2、Airline-benchcopy など)へ、ソースと道具を写す。

    python tools/sync_copy.py <写しのフォルダの名前> [<写しのフォルダの名前> ...]

サーバーの順番待ちの札(run_gradle.py と同じ札)を取ってから写すので、その写しで試験が走っている最中や、
走り始める瞬間に、ソースが入れ替わることがない。札が空くまで待つ。
写すのは src、tools、build.gradle、gradle.properties。写しの側だけにあるファイルは、src と tools の中では消える(鏡写し)。
"""
import os
import subprocess
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_gradle  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    copies = sys.argv[1:]
    if not copies:
        print(__doc__)
        return 2
    if not run_gradle.take_slot("sync " + " ".join(copies)):
        print("the server slot was busy for too long; nothing copied")
        return 1
    try:
        for name in copies:
            target = os.path.join(os.path.dirname(ROOT), name)
            if not os.path.isdir(os.path.join(target, "src")):
                print("not a copy of the project:", target)
                return 1
            for folder in ("src", "tools"):
                code = subprocess.call(["robocopy", os.path.join(ROOT, folder), os.path.join(target, folder), "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/NP"])
                if code >= 8:
                    print("robocopy failed with", code)
                    return 1
            for name2 in ("build.gradle", "gradle.properties"):
                with open(os.path.join(ROOT, name2), "rb") as f:
                    data = f.read()
                with open(os.path.join(target, name2), "wb") as f:
                    f.write(data)
            print("synced", name)
        return 0
    finally:
        try:
            os.remove(run_gradle.SLOT)
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())
