#!/usr/bin/env python3
"""
Temporary test (removed again): the dedicated server of a version creating a new world (preparing the spawn area,
which the integrated server of a singleplayer game does too) with small heaps.

usage: servertest.py <version> <heap MB> ...
"""
import json, os, re, subprocess, sys, time, urllib.request

VERSION, HEAPS = sys.argv[1], [int(h) for h in sys.argv[2:]]


def get(url):
    return urllib.request.urlopen(url, timeout=120).read()


manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
meta = json.loads(get(next(v["url"] for v in manifest["versions"] if v["id"] == VERSION)))
major = meta.get("javaVersion", {}).get("majorVersion", 8)
run_major = 21 if major >= 21 else 17  # Java 8 era servers run fine on 17, which has the same GC log format
java = os.path.join(os.environ[f"JAVA_HOME_{run_major}_X64"], "bin", "java")
jar = get(meta["downloads"]["server"]["url"])
lines = []
for heap in HEAPS:
    d = os.path.abspath(f"srv-{heap}")
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "server.jar"), "wb").write(jar)
    open(os.path.join(d, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(d, "server.properties"), "w").write(
        "level-seed=4815162342\nview-distance=4\nsimulation-distance=5\nonline-mode=false\n")
    gc_log = os.path.join(d, "gc.log")
    start = time.time()
    agent = [f"-javaagent:{os.environ['AGENT']}=3"] if os.environ.get("AGENT") else []
    proc = subprocess.Popen([java, f"-Xms{heap}M", f"-Xmx{heap}M", f"-Xlog:gc:file={gc_log}:uptime"] + agent + ["-jar", "server.jar", "nogui"],
                            cwd=d, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    done, oom, out = None, False, []
    deadline = time.time() + 420
    for line in proc.stdout:
        out.append(line)
        if "OutOfMemoryError" in line:
            oom = True
        if "Done (" in line:
            done = time.time() - start
            break
        if time.time() > deadline:
            break
    if proc.poll() is None:
        try:
            proc.stdin.write("stop\n")
            proc.stdin.flush()
            proc.wait(timeout=120)
        except Exception:
            proc.kill()
    full, peak = 0, 0
    for l in open(gc_log, errors="replace") if os.path.exists(gc_log) else []:
        m = re.search(r"Pause (\w+).*?(\d+)M->(\d+)M\((\d+)M\)", l)
        if m:
            full += m.group(1) == "Full"
            peak = max(peak, int(m.group(3)))
    lines.append(f"{heap:4d} MB: {'world ready in %.0fs' % done if done else 'NOT READY'}{' OUT OF MEMORY' if oom else ''}"
                 f" | max heap after GC {peak} MB, {full} full GCs")
    if heap == HEAPS[0]:
        lines += ["      " + l.strip()[:200] for l in out if "[Gurkha Launcher]" in l]
msg = "\n".join(lines)
print(msg)
print(f"::notice title=Server {VERSION} (Java {major}) new world{' with agent' if os.environ.get('AGENT') else ''}::" + msg.replace("\n", "%0A"))
