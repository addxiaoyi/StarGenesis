"""Launch an actual isolated Paper/Folia server and verify P0 console lifecycle.

Requires Python 3.9+, Java 21, an official server jar and a built plugin jar.
This test does not claim real-player, model, integration or region-boundary acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import queue
import shutil
import subprocess
import threading
import time
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--kind", choices=["paper", "folia"], default="paper")
    parser.add_argument("--accept-eula", action="store_true", help="Accept Mojang's EULA for this isolated test")
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    target = args.directory.resolve()
    target.mkdir(parents=True, exist_ok=True)
    if not args.accept_eula and not (target / "eula.txt").is_file():
        parser.error("Supply an existing accepted eula.txt or explicitly pass --accept-eula")
    if args.accept_eula:
        (target / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    plugins = target / "plugins"
    plugins.mkdir(exist_ok=True)
    jar = args.plugin_jar.resolve()
    shutil.copy2(jar, plugins / "StarGenesis.jar")
    (target / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-rcon=false\n"
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=4\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=60000\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n',
        encoding="utf-8",
    )
    if args.kind == "folia":
        config = target / "config"
        config.mkdir(exist_ok=True)
        (config / "folia-global.yml").write_text("_version: 1\nthreaded-regions:\n  threads: 2\n", encoding="utf-8")
    command = [args.java, "-Xms256M", "-Xmx1G", "-XX:ActiveProcessorCount=4", "-jar", str(args.server_jar.resolve()), "--nogui"]
    started = time.monotonic()
    proc = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
    output: queue.Queue[str | None] = queue.Queue()

    def read_output() -> None:
        assert proc.stdout
        for line in proc.stdout:
            output.put(line)
        output.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    lines: list[str] = []
    commands_sent = False
    stopping = False
    log = target / "smoke-console.log"
    try:
        with log.open("w", encoding="utf-8") as evidence:
            while time.monotonic() - started < args.timeout:
                try:
                    line = output.get(timeout=1)
                except queue.Empty:
                    if proc.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                lines.append(line)
                evidence.write(line)
                evidence.flush()
                if "Done (" in line and not commands_sent:
                    assert proc.stdin
                    proc.stdin.write("genesis status\ngenesis help\ngenesis debug capabilities\ngenesis debug budgets\n")
                    proc.stdin.flush()
                    commands_sent = True
                if "StarGenesis budgets:" in line and not stopping:
                    assert proc.stdin
                    proc.stdin.write("stop\n")
                    proc.stdin.flush()
                    stopping = True
            if proc.poll() is None:
                if not stopping and proc.stdin:
                    proc.stdin.write("stop\n")
                    proc.stdin.flush()
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=10)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)

    joined = "".join(lines)
    checks = {
        "server_ready": "Done (" in joined,
        "plugin_enabled": "StarGenesis ready | phase=P0" in joined,
        "design_examples_verified": "design self-check passed: 5" in joined,
        "status_command": "phase=P0 foundation" in joined,
        "help_command": "/genesis help - this help" in joined,
        "capabilities_command": "StarGenesis capabilities (startup snapshot" in joined,
        "missing_dependency_report": "NOT_INSTALLED" in joined,
        "budget_command": "StarGenesis budgets:" in joined,
        "plugin_disabled": "StarGenesis stopped cleanly" in joined,
        "clean_exit": proc.returncode == 0,
        "no_plugin_startup_error": "StarGenesis startup rejected" not in joined,
    }
    result = {"kind": args.kind, "checks": checks, "passed": all(checks.values()),
              "seconds": round(time.monotonic() - started, 2), "server_sha256": sha256(args.server_jar),
              "plugin_sha256": sha256(jar), "log_sha256": sha256(log),
              "java": subprocess.run([args.java, "-version"], capture_output=True, text=True).stderr.strip(),
              "scope": "actual-server console lifecycle only; no human clients or integration plugins"}
    (target / "smoke-result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not result["passed"]:
        print("".join(lines[-60:]))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
