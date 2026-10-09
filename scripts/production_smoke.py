import argparse
import os
import pathlib
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
PROPERTIES = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line)
STARTUP = """
import {registries} from 'minecraft:registries';
import {console} from 'mantis:console';
registries.item('mantis:release_item', {displayName:'Release Probe'});
registries.fluid('mantis:release_fluid', {displayName:'Release Fluid'});
console.warn('MANTIS_RELEASE_WARN');
console.error('MANTIS_RELEASE_ERROR');
"""
SERVER = """
import {events} from 'mantis:events';
import {clock} from 'mantis:clock';
import {console} from 'mantis:console';
import {recipes} from 'minecraft:recipes';
import {commands} from 'minecraft:commands';
import {server} from 'minecraft:server';
events.on('recipes', () => {
  recipes.shapeless('mantis:release_smoke', 'mantis:release_item', ['minecraft:stone']);
  recipes.shapeless('mantis:release_bucket', 'mantis:release_fluid_bucket', ['minecraft:bucket']);
  console.log('MANTIS_RELEASE_RECIPES', {ready:true}, [1,2]);
});
commands.register('mantis:release_probe', {permission:4}, () => {
  if (clock.ticks() < 1 || server.level('overworld').dimension() !== 'minecraft:overworld') {
    throw Error('Release command ran before activation');
  }
  console.log('MANTIS_RELEASE_COMMAND');
  return 1;
});
clock.every(100, () => {});
events.once('server.started', () => clock.after(1, () => console.log('MANTIS_RELEASE_RUNNING')));
events.once('server.reloaded', () => console.log('MANTIS_RELEASE_RELOADED'));
"""


def main():
    parser = argparse.ArgumentParser(description="Boot the bundled Mantis JAR in an installed Forge server and exercise scripts and reloads.")
    parser.add_argument("--work-dir", type=pathlib.Path, default=ROOT / "build" / "production-smoke")
    parser.add_argument("--reloads", type=int, default=3)
    args = parser.parse_args()
    if args.reloads < 1:
        parser.error("--reloads must be positive")
    jar = ROOT / "mantis-minecraft" / "build" / "libs" / ("mantis-" + PROPERTIES["mod_version"] + ".jar")
    if not jar.is_file():
        raise SystemExit("Missing bundled release JAR: " + str(jar))
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    version = PROPERTIES["minecraft_version"] + "-" + PROPERTIES["forge_version"]
    installer = work / "forge-installer.jar"
    url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + version + "/forge-" + version + "-installer.jar"
    request = urllib.request.Request(url, headers={"User-Agent": "Java/17"})
    with urllib.request.urlopen(request, timeout=60) as response, installer.open("wb") as target:
        shutil.copyfileobj(response, target)
    print("Installing Forge " + version, flush=True)
    with (work / "installer.log").open("w") as log:
        subprocess.run(["java", "-jar", str(installer), "--installServer", str(work)], cwd=work, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    (work / "mods").mkdir(exist_ok=True)
    shutil.copy2(jar, work / "mods" / jar.name)
    scripts = work / "config" / "mantis"
    for kind, name, text in [("startup_scripts", "content.js", STARTUP), ("server_scripts", "it's smoke.mjs", SERVER)]:
        folder = scripts / kind
        folder.mkdir(parents=True, exist_ok=True)
        (folder / name).write_text(text, encoding="utf-8")
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text("online-mode=false\nserver-port=0\nlevel-seed=1\nview-distance=2\nsimulation-distance=2\nmax-tick-time=60000\n")
    (work / "user_jvm_args.txt").write_text("-Xms256m\n-Xmx1g\n")
    command = ["bash", "run.sh", "--nogui"] if os.name != "nt" else ["cmd", "/c", "run.bat", "--nogui"]
    process = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1, start_new_session=os.name != "nt")
    output = queue.Queue()

    def read_output():
        for line in process.stdout:
            output.put(line)
        output.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    lines = []
    commands = reloads = 0
    ready = stopped = False
    deadline = time.monotonic() + 420

    def send(text):
        process.stdin.write(text + "\n")
        process.stdin.flush()

    try:
        with (work / "smoke.log").open("w", encoding="utf-8") as log:
            while time.monotonic() < deadline:
                try:
                    line = output.get(timeout=1)
                except queue.Empty:
                    if process.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                lines.append(line)
                log.write(line)
                log.flush()
                if "MANTIS_RELEASE_" in line or "[Mantis]" in line or "Done (" in line:
                    print(line.rstrip(), flush=True)
                if "MANTIS_RELEASE_RUNNING" in line and not ready:
                    ready = True
                    send("mantis:release_probe")
                elif "MANTIS_RELEASE_COMMAND" in line:
                    commands += 1
                    send("mantis status")
                    if reloads < args.reloads:
                        send("mantis reload")
                    else:
                        send("stop")
                        stopped = True
                elif "MANTIS_RELEASE_RELOADED" in line:
                    reloads += 1
                    send("mantis:release_probe")
            if not stopped:
                raise RuntimeError("Release server did not complete its script/command/reload checks")
            code = process.wait(timeout=30)
        text = "".join(lines)
        failures = ["Mixin apply failed", "InvalidMixinException", "NoClassDefFoundError", "ServiceConfigurationError", "Mantis startup scripts failed", "Server scripts failed to load", "Mantis reload failed", "Release command ran before activation"]
        if code or commands != args.reloads + 1 or reloads != args.reloads or any(error in text for error in failures):
            raise RuntimeError("Release server failed; inspect " + str(work / "smoke.log"))
        if not re.search(r"WARN.*MANTIS_RELEASE_WARN", text) or not re.search(r"ERROR.*MANTIS_RELEASE_ERROR", text):
            raise RuntimeError("Console warn/error did not preserve native log levels")
        if "MANTIS_RELEASE_RECIPES {\"ready\":true} [1,2]" not in text or "1 timers" not in text:
            raise RuntimeError("Release recipe conversion or reload-owned timers were not verified")
        print("Production release JAR passed: startup content, fluids, recipes, console levels, commands and " + str(reloads) + " reloads.", flush=True)
    except Exception:
        print("".join(lines[-50:]), flush=True)
        raise
    finally:
        if process.poll() is None:
            if os.name == "nt":
                process.kill()
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=30)


if __name__ == "__main__":
    main()
