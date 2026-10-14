"""Gradle をオフラインで実行し、ログをファイルに残す。時間切れなら、自分が起動した子プロセスの木だけを止める。

使い方: python tools/run_gradle.py <ログ> [--timeout 秒] <Gradle のタスクと引数...>
プロセスは PID で指定して止める(名前では止めない)。

サーバーを起動するタスク(名前が run で始まるもの)は、同時に 1 つだけにする。
順番待ちの札は SLOT のファイルで、ほかのプロジェクトの写しとも共有する(同じ場所を指すこと)。
札を持ったまま落ちたときは、札に書いた PID が生きていないのを見て、次の人が外す。
"""
import ctypes
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLOT = r"E:\WORKSPACE\Minecraft MODS\ColonyPatchServer\run-logs\server-slot.lock"
SLOT_WAIT = 3600


def alive(pid):
    handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    code = ctypes.c_ulong()
    ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
    ctypes.windll.kernel32.CloseHandle(handle)
    return bool(ok) and code.value == 259


def take_slot(label):
    start = time.time()
    told = 0.0
    while True:
        try:
            fd = os.open(SLOT, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, ("%d %s\n" % (os.getpid(), label)).encode("utf-8"))
            os.close(fd)
            return True
        except FileExistsError:
            owner = []
            try:
                with open(SLOT, encoding="utf-8") as f:
                    owner = f.read().split()
                if not owner or not alive(int(owner[0])):
                    os.remove(SLOT)
                    continue
            except (OSError, ValueError):
                pass
            waited = time.time() - start
            if waited > SLOT_WAIT:
                return False
            if waited - told >= 60 or told == 0.0:
                print("[run_gradle] waiting for the server slot (%.0f s): %s" % (waited, " ".join(owner) if owner else "?"), flush=True)
                told = waited if waited > 0 else 0.001
            time.sleep(10)


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
    if server and not take_slot("%s %s" % (os.path.basename(ROOT), " ".join(a for a in args if a.startswith("run")))):
        print("exit=-998 seconds=%d log=%s (server slot was busy for too long)" % (SLOT_WAIT, log))
        return 1
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
            try:
                os.remove(SLOT)
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())
