"""Launch an actual isolated Paper/Folia server and verify development console lifecycle.

Requires Python 3.9+, Java 21, an official server jar and a built plugin jar.
This test does not claim real-player, model, integration or region-boundary acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path


def filesystem_path(path: Path) -> Path:
    """Use Windows extended paths when opening deeply nested hashed release files."""
    resolved = str(path.resolve())
    if os.name != "nt" or resolved.startswith("\\\\?\\"):
        return Path(resolved)
    if resolved.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + resolved[2:])
    return Path("\\\\?\\" + resolved)


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
    parser.add_argument("--factory-mode", choices=["empty", "create", "recover"], default="empty")
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
    saw_budget_response = False
    saw_factory_status = False
    factory_create_sent = False
    factory_status_sent = False
    create_factory_command = (
        "genesis factory create "
        "00000000-0000-0000-0000-000000000001 "
        "00000000-0000-0000-0000-000000000002 "
        "00000000-0000-0000-0000-000000000003 "
        "00000000-0000-0000-0000-000000000004"
    )
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
                    factory_command = create_factory_command if args.factory_mode == "create" else "genesis factory status"
                    proc.stdin.write("genesis status\ngenesis help\ngenesis lab\ngenesis program\ngenesis blueprint\ngenesis arena\ngenesis arena play\n"
                                     "genesis arena play genesis:arena_ruleset 1.1.0\n"
                                     "genesis arena spectate 00000000-0000-0000-0000-000000000001\n"
                                     "genesis arena decisions\n"
                                     "genesis arena decision " + "0" * 64 + "\n"
                                     "genesis arena recover\n"
                                     "genesis arena recover " + "0" * 64
                                     + " 00000000-0000-0000-0000-000000000032 invalid\n"
                                     "genesis settlement list\n"
                                     "genesis settlement inspect 00000000-0000-0000-0000-000000000001\n"
                                     "genesis design keys\n"
                                     "genesis design imports\n"
                                     "genesis design inspect " + "0" * 64 + "\n"
                                     + factory_command + "\ngenesis debug capabilities\ngenesis debug budgets\n")
                    proc.stdin.flush()
                    commands_sent = True
                    factory_create_sent = args.factory_mode == "create"
                    factory_status_sent = args.factory_mode != "create"
                if args.factory_mode == "create" and factory_create_sent \
                        and "四人虚拟工厂已创建并保存" in line and not factory_status_sent:
                    assert proc.stdin
                    proc.stdin.write("genesis factory status\n")
                    proc.stdin.flush()
                    factory_status_sent = True
                saw_budget_response = saw_budget_response or "StarGenesis budgets:" in line
                if args.factory_mode == "empty":
                    saw_factory_status = saw_factory_status or "此服务器还没有虚拟工厂" in line
                else:
                    saw_factory_status = saw_factory_status or "虚拟工厂 factory-main" in line
                if saw_budget_response and saw_factory_status and not stopping:
                    assert proc.stdin
                    proc.stdin.write("stop\n")
                    proc.stdin.flush()
                    stopping = True
            if proc.poll() is None:
                if not stopping and commands_sent and proc.stdin and not proc.stdin.closed:
                    try:
                        proc.stdin.write("stop\n")
                        proc.stdin.flush()
                    except OSError:
                        pass
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
    identity = re.search(r"This server is running (Paper|Folia) version (\S+)", joined)
    detected_kind, detected_build = identity.groups() if identity else ("", "")
    release_directory = target / "plugins" / "StarGenesis" / "published-designs"
    published_arena_seeded = any(release_directory.glob("*/*.json")) if release_directory.is_dir() else False
    published_arena_v2_seeded = False
    if release_directory.is_dir():
        for release in release_directory.glob("*/*.json"):
            try:
                document = json.loads(filesystem_path(release).read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if document.get("kind") == "ARENA_RULESET" and document.get("metadata", {}).get("schemaVersion") == 2:
                published_arena_v2_seeded = True
                break
    expected_factory_state = (
        "此服务器还没有虚拟工厂" if args.factory_mode == "empty"
        else "虚拟工厂 factory-main"
    )
    checks = {
        "server_kind_matches": detected_kind.lower() == args.kind,
        "server_ready": "Done (" in joined,
        "plugin_enabled": "StarGenesis ready | phases=P1-P10 development" in joined,
        "settlement_journal_validated": "Settlement saga journal verified | records=0 | unresolved=0 | real-settlement=disabled" in joined,
        "settlement_journal_commands": "Settlement journal | records=0 | page=1/1" in joined
        and "Unknown settlement saga." in joined,
        "design_signing_trust_command": "Trusted design publishers | count=0" in joined,
        "design_signing_trust_validated": "Design signing trust verified | authors=0 | signed-archive=0" in joined,
        "design_signed_archive_command": "Signed design archive | records=0 | page=1/1" in joined,
        "design_archive_unknown_inspect": "No signed design matches that artifact hash." in joined,
        "design_examples_verified": "design self-check passed: 5" in joined,
        "status_command": "phase=P1-P10 development" in joined,
        "help_command": "/genesis help - this help" in joined,
        "laboratory_console_guard": "The laboratory is available to players in-game." in joined,
        "program_console_guard": "The program editor is available to players in-game." in joined,
        "blueprint_console_guard": "The blueprint editor is available to players in-game." in joined,
        "arena_console_guard": "竞技规则集编辑器仅能由玩家使用。" in joined,
        "arena_match_console_guard": joined.count("竞技规则集编辑器仅能由玩家使用。") >= 4,
        "arena_decision_history_console": "Arena review decisions | records=0 | page=1/1" in joined,
        "arena_unknown_decision_console": "Unknown arena review decision." in joined,
        "arena_recovery_console_usage": "Usage: /genesis arena recover <sha256> <reviewer-uuid> <original-signature>" in joined,
        "arena_recovery_refuses_new_decision": "Arena approval recovery failed: No persisted arena approval exists for this candidate" in joined,
        "published_arena_seeded": published_arena_seeded,
        "published_arena_schema_v2_seeded": published_arena_v2_seeded,
        "factory_state_command": expected_factory_state in joined,
        "factory_created": args.factory_mode != "create" or "四人虚拟工厂已创建并保存" in joined,
        "capabilities_command": "StarGenesis capabilities (startup snapshot" in joined,
        "missing_dependency_report": "NOT_INSTALLED" in joined,
        "budget_command": "StarGenesis budgets:" in joined,
        "plugin_disabled": "StarGenesis stopped cleanly" in joined,
        "clean_exit": proc.returncode == 0,
        "no_plugin_startup_error": "StarGenesis startup rejected" not in joined,
    }
    result = {"kind": args.kind, "detected_kind": detected_kind.lower(), "detected_build": detected_build,
              "factory_mode": args.factory_mode, "checks": checks, "passed": all(checks.values()),
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
