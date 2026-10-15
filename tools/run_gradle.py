"""Gradle をオフラインで実行し、ログをファイルに残す。時間切れなら、自分が起動した子プロセスの木だけを止める。

使い方: python tools/run_gradle.py <ログ> [--timeout 秒] <Gradle のタスクと引数...>
プロセスは PID で指定して止める(名前では止めない)。

サーバーを起動するタスク(名前が run で始まるもの)は、全部のプロジェクトと写しを合わせて、同時に 2 つまでにする(2026-10-15。
それまでは 1 つ。前にメモリが尽きたことがあるので 2 つまで)。順番待ちの札は SLOTS のファイルで、ほかのプロジェクトの写しとも共有する。
1 枚めの札(server-slot.lock)は ColonyPatchServer の run_gradle.py も使う(あちらは 1 枚めだけを待つ)。
2 枚めの札で起動したサーバーは、待ち受けの番号を 100 上げる(-PportOffset=100。同じ種類の試験が 2 つの写しで同時に走ってもぶつからない)。
それとは別に、写しごとの札(<写し>/run-logs/copy.lock)も取る。同じ写しで 2 つ走らせないため、写しを写す sync_copy.py が走っている試験の
ソースを入れ替えないため。
札を持ったまま落ちたときは、札に書いた PID が生きていないのを見て、次の人が外す。
"""
import ctypes
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLOTS = [r"E:\WORKSPACE\Minecraft MODS\ColonyPatchServer\run-logs\server-slot.lock",
         r"E:\WORKSPACE\Minecraft MODS\ColonyPatchServer\run-logs\server-slot-2.lock"]
SLOT = SLOTS[0]
SLOT_WAIT = 3600
PORT_STEP = 100


def alive(pid):
    handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    code = ctypes.c_ulong()
    ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
    ctypes.windll.kernel32.CloseHandle(handle)
    return bool(ok) and code.value == 259


def try_lock(path, label):
    """札を取る。取れたら None、取れなければ、持っている人の札の中身(PID と名前)。持ち主が生きていない札は外して取り直す。"""
    while True:
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, ("%d %s\n" % (os.getpid(), label)).encode("utf-8"))
            os.close(fd)
            return None
        except FileExistsError:
            owner = []
            try:
                with open(path, encoding="utf-8") as f:
                    owner = f.read().split()
                if not owner or not alive(int(owner[0])):
                    os.remove(path)
                    continue
            except (OSError, ValueError):
                pass
            return owner or ["?"]


def take(paths, label, what):
    """paths の札のどれか 1 枚が空くまで待って取る。取れた札の番号(0 から)、時間切れなら -1。"""
    start = time.time()
    told = 0.0
    while True:
        owners = []
        for i, path in enumerate(paths):
            owner = try_lock(path, label)
            if owner is None:
                return i
            owners.append(" ".join(owner))
        waited = time.time() - start
        if waited > SLOT_WAIT:
            return -1
        if waited - told >= 60 or told == 0.0:
            print("[run_gradle] waiting for %s (%.0f s): %s" % (what, waited, " / ".join(owners)), flush=True)
            told = waited if waited > 0 else 0.001
        time.sleep(10)


def copy_lock(root):
    return os.path.join(root, "run-logs", "copy.lock")


def release(path):
    try:
        os.remove(path)
    except OSError:
        pass


def main():
    args = sys.argv[1:]
    if not args:
        print("usage: run_gradle.py <log> [--timeout N] <gradle args...>")
        return 2
    log = args.pop(0)
    timeout = 600
    if args and args[0] == "--timeout":
        timeout = int(args[1])
        args = args[2:]
    server = any(a.startswith("run") for a in args)
    slot = -1
    if server:
        label = "%s %s" % (os.path.basename(ROOT), " ".join(a for a in args if a.startswith("run")))
        os.makedirs(os.path.dirname(copy_lock(ROOT)), exist_ok=True)
        if take([copy_lock(ROOT)], label, "this copy") < 0:
            print("exit=-998 seconds=%d log=%s (this copy was busy for too long)" % (SLOT_WAIT, log))
            return 1
        slot = take(SLOTS, label, "a server slot")
        if slot < 0:
            release(copy_lock(ROOT))
            print("exit=-998 seconds=%d log=%s (the server slots were busy for too long)" % (SLOT_WAIT, log))
            return 1
        if slot > 0:
            args = args + ["-PportOffset=%d" % (PORT_STEP * slot)]
    try:
        start = time.time()
        with open(log, "w", encoding="utf-8", errors="replace") as out:
            p = subprocess.Popen(
                ["cmd", "/c", os.path.join(ROOT, "gradlew.bat"), "--offline", "--console=plain", *args],
                cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
            try:
                code = p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
                p.wait()
                code = -999
                out.write("\n[run_gradle] TIMEOUT after %d s; killed process tree of PID %d\n" % (timeout, p.pid))
        print("exit=%s seconds=%.0f log=%s" % (code, time.time() - start, log))
        return 0 if code == 0 else 1
    finally:
        if server:
            release(SLOTS[slot])
            release(copy_lock(ROOT))


if __name__ == "__main__":
    sys.exit(main())
