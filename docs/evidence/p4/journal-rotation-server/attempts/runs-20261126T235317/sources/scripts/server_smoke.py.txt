"""Launch an actual isolated Paper/Folia server and verify plugin console lifecycle.

Requires Python 3.9+, Java 21, an official server jar and a built plugin jar.
The optional offline-client workflow is automated coverage, not human, integration-plugin or region-boundary acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_player_workflow(args: argparse.Namespace, target: Path, stop_server) -> dict[str, object]:
    node = shutil.which(args.node) or args.node
    if Path(node).exists():
        node = str(Path(node).resolve())
    result_path = target / "player-workflow-result.json"
    log_path = target / "player-workflow.log"
    result_path.write_text('{"passed":false,"error":"workflow did not complete"}\n', encoding="utf-8")
    script = args.client_script.resolve() if args.client_script else Path(__file__).resolve().with_name(
        "offline_client_lifecycle_smoke.cjs" if args.lifecycle_phase else "offline_client_workflow_smoke.cjs")
    command = [node, str(script),
               "--host", "127.0.0.1", "--port", str(args.port),
               "--kind", args.kind,
               "--module-root", str(args.node_modules.resolve()),
               "--data-directory", str(target / "plugins" / "StarGenesis"),
               "--blueprint-source", str(Path(__file__).resolve().parents[1]
                                          / "stargenesis-design" / "src" / "main" / "resources" / "examples" / "blueprint.json"),
               "--output", str(result_path)]
    if args.lifecycle_phase:
        command.extend(["--phase", args.lifecycle_phase])
        if args.lifecycle_manifest:
            command.extend(["--manifest", str(args.lifecycle_manifest.resolve())])
    if args.region_travel:
        command.append("--region-travel")
    if args.test_shutdown_menu:
        command.append("--test-shutdown-menu")
    if args.disable_plugin_during_lab:
        command.append("--disable-plugin-during-lab")
    if args.sustained_lab_minutes:
        command.extend(["--sustained-lab-minutes", str(args.sustained_lab_minutes)])
    try:
        completed = subprocess.Popen(command, cwd=target, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding="utf-8", errors="replace", bufsize=1)
        output_lines: list[str] = []
        workflow_output: queue.Queue[str | None] = queue.Queue()
        deadline = time.monotonic() + args.player_client_timeout
        shutdown_requested = False
        assert completed.stdout is not None
        def read_workflow_output() -> None:
            assert completed.stdout is not None
            for output_line in completed.stdout:
                workflow_output.put(output_line)
            workflow_output.put(None)
        threading.Thread(target=read_workflow_output, daemon=True).start()
        while time.monotonic() < deadline:
            try:
                line = workflow_output.get(timeout=0.25)
            except queue.Empty:
                if completed.poll() is not None:
                    break
                continue
            if line is None:
                break
            if line:
                output_lines.append(line)
                if line.strip() in {"READY_TO_STOP_WITH_MENU_OPEN", "READY_TO_DISABLE_PLUGIN_DURING_LAB",
                                    "READY_TO_STOP_WITH_LAB_ACTIVE"} and not shutdown_requested:
                    shutdown_requested = stop_server()
            if completed.poll() is not None and workflow_output.empty():
                break
        if completed.poll() is None:
            try:
                completed.wait(timeout=10)
            except subprocess.TimeoutExpired:
                completed.kill()
                completed.wait(timeout=10)
                log_path.write_text("".join(output_lines), encoding="utf-8")
                return {"passed": False, "error": "offline client process did not exit after closing its output", "log": log_path.name}
        if completed.returncode is None:
            completed.kill()
            completed.wait(timeout=10)
            log_path.write_text("".join(output_lines), encoding="utf-8")
            return {"passed": False, "error": "offline client workflow timed out", "log": log_path.name}
        return_code = completed.returncode
        log_path.write_text("".join(output_lines), encoding="utf-8")
    except OSError as failure:
        log_path.write_text(str(failure), encoding="utf-8")
        return {"passed": False, "error": str(failure), "log": log_path.name}
    except subprocess.TimeoutExpired:
        return {"passed": False, "error": "offline client process did not stop after timeout", "log": log_path.name}
    if not result_path.is_file():
        return {"passed": False, "error": "offline client workflow produced no result file", "log": log_path.name}
    try:
        workflow = json.loads(result_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as failure:
        return {"passed": False, "error": f"invalid offline client result: {failure}", "log": log_path.name}
    workflow["passed"] = bool(workflow.get("passed")) and return_code == 0
    if args.test_shutdown_menu:
        workflow["shutdownRequestedByHarness"] = shutdown_requested
        workflow["passed"] = workflow["passed"] and shutdown_requested
    if args.disable_plugin_during_lab:
        workflow["pluginDisableRequestedByHarness"] = shutdown_requested
        workflow["passed"] = workflow["passed"] and shutdown_requested
    if args.lifecycle_phase in {"prepare", "resume"}:
        workflow["activeLabShutdownRequestedByHarness"] = shutdown_requested
        workflow["passed"] = workflow["passed"] and shutdown_requested
    workflow["log"] = log_path.name
    return workflow


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--kind", choices=["paper", "folia"], default="paper")
    parser.add_argument("--accept-eula", action="store_true", help="Accept Mojang's EULA for this isolated test")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--port", type=int, default=0, help="Fixed server port required for offline-client testing")
    parser.add_argument("--max-players", type=int, default=4)
    parser.add_argument("--heap-mb", type=int, default=1024, help="Maximum Java heap for this isolated server")
    parser.add_argument("--jvm-arg", action="append", default=[], help="Additional Java option; repeat as --jvm-arg=-XX:...")
    parser.add_argument("--player-workflow", action="store_true", help="Run automated offline-client P1-P10 workflows")
    parser.add_argument("--client-script", type=Path, help="Use a focused offline-client script with --player-workflow")
    parser.add_argument("--node", default="node", help="Node.js executable for --player-workflow")
    parser.add_argument("--node-modules", type=Path, help="Directory containing the installed mineflayer package")
    parser.add_argument("--libraries-from", type=Path,
                        help="Reuse libraries from this server directory; bootstrap servers receive a copy")
    parser.add_argument("--player-client-timeout", type=int, default=900)
    parser.add_argument("--region-travel", action="store_true", help="Walk offline clients into separate Folia regions")
    parser.add_argument("--test-shutdown-menu", action="store_true", help="Stop the server while a player inventory menu remains open")
    parser.add_argument("--disable-plugin-during-lab", action="store_true", help="Disable StarGenesis live during a Folia command-driven lab")
    parser.add_argument("--sustained-lab-minutes", type=int, default=0,
                        help="Run four concurrent P1 simulations for this many minutes in the Folia player workflow")
    parser.add_argument("--extra-plugin-jar", type=Path, action="append", default=[], help="Additional isolated test plugin JAR")
    parser.add_argument("--lifecycle-phase", choices=["prepare", "resume", "verify"],
                        help="Focused persistent-identity restart workflow; used by server_lifecycle_smoke.py")
    parser.add_argument("--lifecycle-manifest", type=Path, help="Expected storage hashes from the preceding clean shutdown")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("--port must be between 0 and 65535")
    if not 1 <= args.max_players <= 100:
        parser.error("--max-players must be between 1 and 100")
    if not 256 <= args.heap_mb <= 8192:
        parser.error("--heap-mb must be between 256 and 8192")
    if args.lifecycle_phase and not args.player_workflow:
        parser.error("--lifecycle-phase requires --player-workflow")
    if args.client_script and (not args.player_workflow or args.lifecycle_phase or not args.client_script.is_file()):
        parser.error("--client-script requires --player-workflow, an existing script and no --lifecycle-phase")
    if args.lifecycle_phase in {"resume", "verify"} and args.lifecycle_manifest is None:
        parser.error("Restart phases require --lifecycle-manifest")
    if args.lifecycle_phase and (args.region_travel or args.test_shutdown_menu
                                 or args.disable_plugin_during_lab or args.sustained_lab_minutes):
        parser.error("Lifecycle phases cannot be combined with the general client workflow options")
    if args.player_workflow and (args.port == 0 or args.node_modules is None):
        parser.error("--player-workflow requires a fixed --port and --node-modules")
    if (args.region_travel or args.test_shutdown_menu) and not args.player_workflow:
        parser.error("--region-travel and --test-shutdown-menu require --player-workflow")
    if args.disable_plugin_during_lab and not args.player_workflow:
        parser.error("--disable-plugin-during-lab requires --player-workflow")
    if args.disable_plugin_during_lab and args.kind != "folia":
        parser.error("--disable-plugin-during-lab is specific to Folia")
    if not 0 <= args.sustained_lab_minutes <= 120:
        parser.error("--sustained-lab-minutes must be between 0 and 120")
    if args.sustained_lab_minutes and (not args.player_workflow or args.kind != "folia" or not args.region_travel):
        parser.error("--sustained-lab-minutes requires a Folia player workflow with region travel")
    if args.sustained_lab_minutes and args.player_client_timeout < args.sustained_lab_minutes * 60 + 300:
        parser.error("--player-client-timeout must leave five minutes beyond the sustained load duration")
    if args.test_shutdown_menu and args.disable_plugin_during_lab:
        parser.error("Choose one lab retirement scenario per server process")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    target = args.directory.resolve()
    target.mkdir(parents=True, exist_ok=True)
    if not args.accept_eula and not (target / "eula.txt").is_file():
        parser.error("Supply an existing accepted eula.txt or explicitly pass --accept-eula")
    if args.accept_eula:
        (target / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    plugins = target / "plugins"
    plugins.mkdir(exist_ok=True)
    server_jar = args.server_jar.resolve()
    with zipfile.ZipFile(server_jar) as archive:
        extracted_server = "org/bukkit/craftbukkit/Main.class" in archive.namelist()
    libraries_directory = target / "libraries"
    if args.libraries_from is not None and not libraries_directory.exists():
        source_libraries = args.libraries_from.resolve() / "libraries"
        if not source_libraries.is_dir():
            parser.error(f"Server libraries directory is missing: {source_libraries}")
        if extracted_server:
            libraries_directory = source_libraries
        else:
            shutil.copytree(source_libraries, libraries_directory)
    jar = args.plugin_jar.resolve()
    shutil.copy2(jar, plugins / "StarGenesis.jar")
    for extra_plugin in args.extra_plugin_jar:
        if not extra_plugin.is_file():
            parser.error(f"Additional test plugin does not exist: {extra_plugin}")
        shutil.copy2(extra_plugin.resolve(), plugins / extra_plugin.name)
    (target / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\n"
        f"view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players={args.max_players}\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=60000\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n',
        encoding="utf-8",
    )
    if args.kind == "folia":
        config = target / "config"
        config.mkdir(exist_ok=True)
        # Folia reads its threaded-regions settings from paper-global.yml.
        folia_config = config / "paper-global.yml"
        if not folia_config.exists():
            folia_config.write_text("threaded-regions:\n  threads: 2\n", encoding="utf-8")
    if extracted_server:
        libraries = sorted(libraries_directory.rglob("*.jar"))
        if not libraries:
            parser.error("An extracted server requires its library JARs or --libraries-from")
        classpath = os.pathsep.join([str(server_jar), *(str(path.resolve()) for path in libraries)])
        command = [args.java, "-Xms128M", f"-Xmx{args.heap_mb}M", "-XX:ActiveProcessorCount=4", *args.jvm_arg,
                   "-cp", classpath, "org.bukkit.craftbukkit.Main", "--nogui"]
    else:
        command = [args.java, "-Xms128M", f"-Xmx{args.heap_mb}M", "-XX:ActiveProcessorCount=4", *args.jvm_arg,
                   "-jar", str(server_jar), "--nogui"]
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
    player_workflow: dict[str, object] | None = None
    log = target / "smoke-console.log"

    def send_input(value: str) -> bool:
        if proc.stdin is None or proc.poll() is not None:
            return False
        try:
            proc.stdin.write(value)
            proc.stdin.flush()
            return True
        except (BrokenPipeError, OSError):
            return False

    try:
        with log.open("w", encoding="utf-8") as evidence:
            overall_timeout = args.timeout + (args.player_client_timeout if args.player_workflow else 0)
            while time.monotonic() - started < overall_timeout:
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
                    commands = "genesis status\ngenesis help\ngenesis debug capabilities\ngenesis debug budgets\n"
                    if args.player_workflow:
                        commands += "op SGReview\n"
                    send_input(commands)
                    commands_sent = True
                if "StarGenesis budgets:" in line and not stopping and args.player_workflow:
                    def send_workflow_command() -> bool:
                        nonlocal stopping
                        command = "disable-star-genesis\n" if args.disable_plugin_during_lab else "stop\n"
                        sent = send_input(command)
                        if command == "stop\n": stopping = sent
                        return sent
                    player_workflow = run_player_workflow(args, target, send_workflow_command)
                    if args.disable_plugin_during_lab or not stopping:
                        stopping = send_input("stop\n")
                elif "StarGenesis budgets:" in line and not stopping:
                    stopping = send_input("stop\n")
            if proc.poll() is None:
                if not stopping:
                    send_input("stop\n")
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
        "plugin_enabled": "StarGenesis ready | P0 accepted" in joined,
        "design_examples_verified": "design self-check passed: 5" in joined,
        "status_command": "foundation accepted; automated workflows available, human acceptance pending | engine=" in joined,
        "p3_program_workspace": "P3 typed program editor=available" in joined,
        "p3_replayable_simulation": "P3 programs run in a replayable resource sandbox" in joined,
        "p4_virtual_radio": "P4 virtual radio=available" in joined,
        "p4_radio_protocol_pinned": "Virtual radio protocol pinned | genesis:radio | hash=" in joined,
        "p4_service_contract_pinned": "Virtual service contract pinned | genesis:service_contract | hash=" in joined,
        "p5_closed_ecology": "P5 closed ecology=available" in joined,
        "p6_four_player_factory": "P6 four-seat simulated factory=available" in joined,
        "p7_arena_catalog": "P7 licensed arena catalog=available" in joined,
        "p8_territory_match": "P8 simultaneous territory match=available" in joined,
        "p9_real_settlement_inactive": "P9 durable saga contract=available, real gateway=inactive" in joined,
        "p10_signed_design_import": "P10 signed Ed25519 design import=available" in joined,
        "help_command": "/genesis help - this help" in joined,
        "capabilities_command": "StarGenesis capabilities (" in joined,
        "missing_dependency_report": "NOT_INSTALLED" in joined,
        "budget_command": "StarGenesis budgets:" in joined,
        "plugin_disabled": "StarGenesis stopped cleanly" in joined,
        "clean_exit": proc.returncode == 0,
        "no_plugin_startup_error": "StarGenesis startup rejected" not in joined,
    }
    if args.player_workflow:
        checks["player_workflow"] = bool(player_workflow and player_workflow.get("passed"))
    result = {"kind": args.kind, "checks": checks, "passed": all(checks.values()),
              "seconds": round(time.monotonic() - started, 2), "server_sha256": sha256(args.server_jar),
              "plugin_sha256": sha256(jar), "log_sha256": sha256(log),
              "java": subprocess.run([args.java, "-version"], capture_output=True, text=True).stderr.strip(),
              "launch_mode": "extracted-server-classpath" if extracted_server else "paperclip-bootstrap",
              "heap_mb": args.heap_mb,
              "jvm_args": args.jvm_arg,
              "scope": "actual-server console lifecycle and automated offline clients; no human acceptance"
              if args.player_workflow else "actual-server console lifecycle only; no human clients or integration plugins"}
    if args.player_workflow:
        result["player_workflow"] = player_workflow or {"passed": False, "error": "workflow did not run"}
    (target / "smoke-result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not result["passed"]:
        print("".join(lines[-60:]))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
