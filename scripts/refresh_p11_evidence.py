"""Run the reproducible P11 build, boundary, Paper/Folia, and evidence checks."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import date, datetime
from pathlib import Path


SCALE_PATTERN = re.compile(
    r"P11_SCALE arena=(\d+x\d+) players=(\d+) rounds=(\d+) "
    r"elapsedMillis=(\d+) finalHash=([0-9a-f]{64})"
)
SERVER_VERSION_PATTERN = re.compile(r"This server is running (?:Paper|Folia) version ([\d.]+-\d+)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command: list[str], root: Path, env: dict[str, str], log_path: Path | None = None) -> str:
    print("$ " + subprocess.list2cmdline(command), flush=True)
    if log_path is None:
        result = subprocess.run(command, cwd=root, env=env, text=True, encoding="utf-8", errors="replace")
        if result.returncode != 0:
            raise RuntimeError(f"Command failed with exit code {result.returncode}")
        return ""

    with log_path.open("w", encoding="utf-8", newline="\n") as log:
        result = subprocess.run(
            command,
            cwd=root,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    if result.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {result.returncode}. See {log_path}")
    return ""


def test_totals(root: Path) -> int:
    reports = sorted(root.glob("*/target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise RuntimeError("Maven Surefire XML reports were not found")

    total = 0
    failures = 0
    errors = 0
    skipped = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        total += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0"))
        errors += int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))
    if failures or errors or skipped:
        raise RuntimeError(f"Unexpected test results: failures={failures}, errors={errors}, skipped={skipped}")
    return total


def scale_result(log_path: Path) -> dict[str, object]:
    match = SCALE_PATTERN.search(log_path.read_text(encoding="utf-8", errors="replace"))
    if match is None:
        raise RuntimeError("P11 scale probe result was not found in the Maven log")
    arena, players, rounds, elapsed, state_hash = match.groups()
    return {
        "arena": arena,
        "players": int(players),
        "rounds": int(rounds),
        "elapsedMillis": int(elapsed),
        "finalStateHash": state_hash,
        "test": "dev.stargenesis.sim.ArenaMatchWorldTest.maximumMapAndEightPlayerRosterCompleteABoundedScaleRun",
        "scope": "Single-process pure simulation unit test, not server or network load",
    }


def p8_reconnect_result(workflow: dict[str, object]) -> dict[str, object] | None:
    check = next((entry for entry in workflow.get("checks", []) if entry.get("stage") == "P8"), None)
    if check is None:
        return None
    fields = (
        "abruptDisconnect",
        "observerSawPendingActionBeforeDisconnect",
        "participantUuidPreserved",
        "pendingActionRestored",
        "matchIdRestored",
    )
    return {key: check[key] for key in fields if key in check}


def p4_delivery_result(workflow: dict[str, object]) -> dict[str, object] | None:
    check = next((entry for entry in workflow.get("checks", []) if entry.get("stage") == "P4"), None)
    if check is None:
        return None
    fields = (
        "deliveredMessage",
        "atLeastOncePolls",
        "recipientDisconnectPersisted",
        "acknowledgementClearedMailbox",
        "crossTickDuplicateNotRedelivered",
        "messageTick",
        "sequence",
    )
    return {key: check[key] for key in fields if key in check}


def p6_factory_failure_result(workflow: dict[str, object]) -> dict[str, object] | None:
    check = next((entry for entry in workflow.get("checks", []) if entry.get("stage") == "P6"), None)
    if check is None:
        return None
    fields = (
        "injectedProductionFailure",
        "compensationRecovered",
        "refundedCreditsAndOre",
        "releasedPartsEscrow",
        "failureSeed",
        "failedOrder",
        "recoveredOrganization",
        "recoveredOrdersLine",
    )
    return {key: check[key] for key in fields if key in check}


def p5_ecology_study_result(workflow: dict[str, object]) -> dict[str, object] | None:
    check = next((entry for entry in workflow.get("checks", []) if entry.get("stage") == "P5-ecology-study"), None)
    if check is None:
        return None
    fields = ("passed", "requestedTicksPerTank", "maximumWorlds", "profiles", "results", "threeSpeciesCandidate", "scope")
    return {key: check[key] for key in fields if key in check}


def server_version(log_path: Path) -> str:
    match = SERVER_VERSION_PATTERN.search(log_path.read_text(encoding="utf-8", errors="replace"))
    if match is None:
        raise RuntimeError(f"Server version was not found in {log_path}")
    return match.group(1)


def smoke(
    kind: str,
    java: Path,
    server_jar: Path,
    plugin_jar: Path,
    directory: Path,
    root: Path,
    env: dict[str, str],
    accept_eula: bool,
    libraries_from: Path | None = None,
    player_workflow: bool = False,
    node: str = "node",
    node_modules: Path | None = None,
    port: int = 25574,
    player_client_timeout: int = 480,
    region_travel: bool = False,
    disable_plugin_during_lab: bool = False,
    sustained_lab_minutes: int = 0,
    extra_plugin_jar: Path | None = None,
) -> tuple[dict[str, object], Path, Path]:
    command = [
        sys.executable,
        str(root / "scripts" / "server_smoke.py"),
        "--java",
        str(java),
        "--server-jar",
        str(server_jar),
        "--plugin-jar",
        str(plugin_jar),
        "--directory",
        str(directory),
        "--kind",
        kind,
    ]
    if accept_eula:
        command.append("--accept-eula")
    if libraries_from is not None:
        command.extend(["--libraries-from", str(libraries_from)])
    if player_workflow:
        if node_modules is None:
            raise RuntimeError("--node-modules is required for the automated player workflow")
        command.extend([
            "--port", str(port), "--max-players", "8", "--player-workflow", "--node", node,
            "--node-modules", str(node_modules), "--player-client-timeout", str(player_client_timeout),
        ])
    if region_travel:
        command.append("--region-travel")
    if disable_plugin_during_lab:
        command.append("--disable-plugin-during-lab")
    if sustained_lab_minutes:
        command.extend(["--sustained-lab-minutes", str(sustained_lab_minutes)])
    if extra_plugin_jar is not None:
        command.extend(["--extra-plugin-jar", str(extra_plugin_jar)])
    output = subprocess.run(
        command,
        cwd=root,
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    print("$ " + subprocess.list2cmdline(command), flush=True)
    if output.returncode != 0:
        print(output.stdout, end="")
        print(output.stderr, file=sys.stderr, end="")
        raise RuntimeError(f"{kind} server smoke failed with exit code {output.returncode}")

    result_path = directory / "smoke-result.json"
    console_path = directory / "smoke-console.log"
    result = json.loads(result_path.read_text(encoding="utf-8"))
    if not result.get("passed"):
        raise RuntimeError(f"{kind} server smoke did not pass")
    return result, result_path, console_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="Java 21 executable or path")
    parser.add_argument("--paper-server-jar", type=Path, required=True)
    parser.add_argument("--folia-server-jar", type=Path, required=True)
    parser.add_argument("--paper-directory", type=Path, default=Path(".work/server-p11-paper"))
    parser.add_argument("--folia-directory", type=Path, default=Path(".work/server-p11-folia"))
    parser.add_argument("--player-workflow", action="store_true", help="Run five automated offline clients through P1-P10 on Paper")
    parser.add_argument("--player-workflow-directory", type=Path, help="New isolated directory for the offline-client server")
    parser.add_argument("--folia-player-workflow", action="store_true", help="Run P1-P10 clients across Folia regions and live-disable verification")
    parser.add_argument("--folia-player-workflow-directory", type=Path, help="New isolated directory for the Folia offline-client server")
    parser.add_argument("--paper-libraries", type=Path, help="Extracted Paper server directory containing libraries/")
    parser.add_argument("--folia-libraries", type=Path, help="Extracted Folia server directory containing libraries/")
    parser.add_argument("--folia-disable-plugin-jar", type=Path, help="Folia-supported test-only plugin used to live-disable StarGenesis")
    parser.add_argument("--folia-sustained-lab-minutes", type=int, default=0,
                        help="Run four concurrent P1 simulations after the Folia player workflow")
    parser.add_argument("--node", default="node", help="Node.js executable for --player-workflow")
    parser.add_argument("--node-modules", type=Path, help="Directory containing the installed mineflayer package")
    parser.add_argument("--player-port", type=int, default=25574)
    parser.add_argument("--player-client-timeout", type=int, default=900)
    parser.add_argument("--accept-eula", action="store_true", help="Accept Mojang's EULA for these isolated test servers")
    parser.add_argument("--evidence-directory", type=Path, default=Path("docs/evidence/p11"))
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    java_value = shutil.which(args.java) if not Path(args.java).is_absolute() else str(Path(args.java).resolve())
    if java_value is None:
        parser.error(f"Java executable not found: {args.java}")
    java = Path(java_value)
    java_home = java.parent.parent
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java.parent) + os.pathsep + env.get("PATH", "")

    paper_server = args.paper_server_jar.resolve()
    folia_server = args.folia_server_jar.resolve()
    if not paper_server.is_file() or not folia_server.is_file():
        parser.error("Both Paper and Folia server JARs must exist")
    paper_directory = (root / args.paper_directory).resolve() if not args.paper_directory.is_absolute() else args.paper_directory.resolve()
    folia_directory = (root / args.folia_directory).resolve() if not args.folia_directory.is_absolute() else args.folia_directory.resolve()
    evidence = (root / args.evidence_directory).resolve() if not args.evidence_directory.is_absolute() else args.evidence_directory.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    player_directory: Path | None = None
    folia_player_directory: Path | None = None
    paper_libraries: Path | None = None
    folia_libraries: Path | None = None
    disable_plugin_jar: Path | None = None
    if args.player_workflow:
        if args.node_modules is None or args.paper_libraries is None:
            parser.error("--player-workflow requires --node-modules and --paper-libraries")
        paper_libraries = (root / args.paper_libraries).resolve() if not args.paper_libraries.is_absolute() else args.paper_libraries.resolve()
        if not (paper_libraries / "libraries").is_dir():
            parser.error(f"Paper libraries directory not found: {paper_libraries / 'libraries'}")
    if args.player_workflow:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        requested = args.player_workflow_directory or Path(".work") / f"server-p11-player-{stamp}"
        player_directory = (root / requested).resolve() if not requested.is_absolute() else requested.resolve()
        if player_directory.exists():
            parser.error(f"Player workflow directory already exists: {player_directory}")
    if args.folia_player_workflow:
        if args.node_modules is None or args.folia_libraries is None or args.folia_disable_plugin_jar is None:
            parser.error("--folia-player-workflow requires --node-modules, --folia-libraries, and --folia-disable-plugin-jar")
        folia_libraries = (root / args.folia_libraries).resolve() if not args.folia_libraries.is_absolute() else args.folia_libraries.resolve()
        disable_plugin_jar = (root / args.folia_disable_plugin_jar).resolve() if not args.folia_disable_plugin_jar.is_absolute() else args.folia_disable_plugin_jar.resolve()
        if not (folia_libraries / "libraries").is_dir() or not disable_plugin_jar.is_file():
            parser.error("Folia server libraries or live-disable test plugin were not found")
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        requested = args.folia_player_workflow_directory or Path(".work") / f"server-p11-folia-player-{stamp}"
        folia_player_directory = (root / requested).resolve() if not requested.is_absolute() else requested.resolve()
        if folia_player_directory.exists():
            parser.error(f"Folia player workflow directory already exists: {folia_player_directory}")
    if not 0 <= args.folia_sustained_lab_minutes <= 120:
        parser.error("--folia-sustained-lab-minutes must be between 0 and 120")
    if args.folia_sustained_lab_minutes and not args.folia_player_workflow:
        parser.error("--folia-sustained-lab-minutes requires --folia-player-workflow")

    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    run([str(wrapper), "-B", "clean", "verify"], root, env, evidence / "maven-clean-verify.log")
    run([sys.executable, "scripts/check_boundaries.py"], root, env)

    plugin_jar = root / "stargenesis-paper" / "target" / "stargenesis-paper-4.0.0-SNAPSHOT.jar"
    sbom_path = root / "target" / "sbom.json"
    if not plugin_jar.is_file() or not sbom_path.is_file():
        raise RuntimeError("Build output JAR or aggregate SBOM is missing")
    plugin_hash = sha256(plugin_jar)
    sbom = json.loads(sbom_path.read_text(encoding="utf-8"))

    signer_test_path = evidence / "release-signer-smoke.json"
    run([
        sys.executable,
        "scripts/test_release_artifact_signer.py",
        "--java",
        str(java),
        "--plugin-jar",
        str(plugin_jar),
        "--sbom",
        str(sbom_path),
        "--output",
        str(signer_test_path),
    ], root, env)
    release_signer_test = json.loads(signer_test_path.read_text(encoding="utf-8"))
    if not release_signer_test.get("passed"):
        raise RuntimeError("Ed25519 release signer self-test failed")
    signed_design_test_path = evidence / "signed-design-signer-smoke.json"
    signed_design_example = root / "stargenesis-design" / "src" / "main" / "resources" / "examples" / "blueprint.json"
    run([
        sys.executable,
        "scripts/test_signed_design_package_signer.py",
        "--java-home",
        str(java_home),
        "--java",
        str(java),
        "--plugin-jar",
        str(plugin_jar),
        "--design",
        str(signed_design_example),
        "--output",
        str(signed_design_test_path),
    ], root, env)
    signed_design_test = json.loads(signed_design_test_path.read_text(encoding="utf-8"))
    if not signed_design_test.get("passed"):
        raise RuntimeError("Signed design package signer self-test failed")

    paper, base_paper_result_path, base_paper_console_path = smoke(
        "paper", java, paper_server, plugin_jar, paper_directory, root, env, args.accept_eula
    )
    folia, base_folia_result_path, base_folia_console_path = smoke(
        "folia", java, folia_server, plugin_jar, folia_directory, root, env, args.accept_eula
    )
    if paper.get("plugin_sha256") != plugin_hash or folia.get("plugin_sha256") != plugin_hash:
        raise RuntimeError("Paper/Folia smoke did not use the artifact produced by this build")

    player_workflow: dict[str, object] | None = None
    player_smoke: dict[str, object] | None = None
    if player_directory is not None:
        player_smoke, _, _ = smoke(
            "paper", java, paper_server, plugin_jar, player_directory, root, env, args.accept_eula,
            libraries_from=paper_libraries, player_workflow=True, node=args.node,
            node_modules=args.node_modules.resolve(), port=args.player_port,
            player_client_timeout=args.player_client_timeout,
        )
        player_workflow = player_smoke.get("player_workflow")
        if not player_smoke.get("passed") or not isinstance(player_workflow, dict) or not player_workflow.get("passed"):
            raise RuntimeError("Automated Paper player workflow failed")
        if player_smoke.get("plugin_sha256") != plugin_hash:
            raise RuntimeError("Automated player workflow did not use the artifact produced by this build")

    folia_player_workflow: dict[str, object] | None = None
    folia_player_smoke: dict[str, object] | None = None
    if folia_player_directory is not None:
        folia_player_smoke, _, _ = smoke(
            "folia", java, folia_server, plugin_jar, folia_player_directory, root, env, args.accept_eula,
            libraries_from=folia_libraries, player_workflow=True, node=args.node,
            node_modules=args.node_modules.resolve(), port=args.player_port + 1,
            player_client_timeout=max(args.player_client_timeout, args.folia_sustained_lab_minutes * 60 + 600),
            region_travel=True,
            disable_plugin_during_lab=True, sustained_lab_minutes=args.folia_sustained_lab_minutes,
            extra_plugin_jar=disable_plugin_jar,
        )
        folia_player_workflow = folia_player_smoke.get("player_workflow")
        if not folia_player_smoke.get("passed") or not isinstance(folia_player_workflow, dict) or not folia_player_workflow.get("passed"):
            raise RuntimeError("Automated Folia player workflow failed")
        if folia_player_smoke.get("plugin_sha256") != plugin_hash:
            raise RuntimeError("Automated Folia workflow did not use the artifact produced by this build")

    copies = [
        (sbom_path, evidence / "sbom.json"),
        (base_paper_result_path, evidence / "paper-result.json"),
        (base_paper_console_path, evidence / "paper-console.log"),
        (base_folia_result_path, evidence / "folia-result.json"),
        (base_folia_console_path, evidence / "folia-console.log"),
    ]
    for source, destination in copies:
        shutil.copy2(source, destination)
    if player_directory is not None and player_smoke is not None:
        for source_name, destination_name in (
            ("smoke-console.log", "player-paper-console.log"),
            ("smoke-result.json", "player-paper-result.json"),
            ("player-workflow.log", "player-workflow.log"),
            ("player-workflow-result.json", "player-workflow-result.json"),
        ):
            shutil.copy2(player_directory / source_name, evidence / destination_name)
    if folia_player_directory is not None and folia_player_smoke is not None:
        for source_name, destination_name in (
            ("smoke-console.log", "folia-player-console.log"),
            ("smoke-result.json", "folia-player-result.json"),
            ("player-workflow.log", "folia-player-workflow.log"),
            ("player-workflow-result.json", "folia-player-workflow-result.json"),
        ):
            shutil.copy2(folia_player_directory / source_name, evidence / destination_name)
        shutil.copy2(folia_player_directory / "plugins" / "LabDisableTrigger" / "runtime-sample.json",
                     evidence / "folia-runtime-sample.json")

    log_path = evidence / "maven-clean-verify.log"
    scale = scale_result(log_path)
    scale["testLog"] = "maven-clean-verify.log"
    scale["result"] = {
        "passed": True,
        "elapsedMillis": scale.pop("elapsedMillis"),
        "stateHash": scale.pop("finalStateHash"),
    }
    scale_document = {
        "test": "dev.stargenesis.sim.ArenaMatchWorldTest.maximumMapAndEightPlayerRosterCompleteABoundedScaleRun",
        "arena": {
            "width": int(str(scale["arena"]).split("x")[0]),
            "height": int(str(scale["arena"]).split("x")[1]),
            "players": scale["players"],
            "teams": 2,
            "rounds": scale["rounds"],
        },
        "result": scale["result"],
        "scope": "Single-process pure simulation scale probe. This is not Paper/Folia player throughput or network load evidence.",
    }
    (evidence / "scale-probe.json").write_text(
        json.dumps(scale_document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    sustained_load = next((entry for entry in folia_player_workflow["checks"]
                           if entry.get("stage") == "P11-sustained-load"), None) \
        if folia_player_workflow is not None else None
    if sustained_load is not None:
        summary_scope = (
            "SBOM, bounded simulation probe, Paper/Folia lifecycle and client workflows, and a controlled "
            f"{sustained_load['requestedMinutes']}-minute Folia P1 simulation workload were verified. "
            "No human acceptance, real settlement provider, production capacity study, retention study, release signing, or deployment was performed."
        )
    elif player_workflow is not None or folia_player_workflow is not None:
        summary_scope = (
            "SBOM, bounded single-process Arena simulation probe, Paper/Folia console lifecycle, and automated Paper/Folia client workflows were verified. "
            "No human player acceptance, real third-party settlement, sustained production load study, retention study, release signing, or production deployment was performed."
        )
    else:
        summary_scope = (
            "SBOM, bounded single-process Arena simulation probe, and actual Paper/Folia console lifecycle only. "
            "No real third-party settlement, human player match, sustained production load study, retention study, release signing, or production deployment was performed."
        )

    summary = {
        "date": date.today().isoformat(),
        "build": {
            "command": "mvnw.cmd -B clean verify" if os.name == "nt" else "./mvnw -B clean verify",
            "exitCode": 0,
            "testsPassed": test_totals(root),
            "paperArtifactSha256": plugin_hash,
        },
        "sbom": {
            "format": sbom["bomFormat"],
            "specVersion": sbom["specVersion"],
            "components": len(sbom.get("components", [])),
            "file": "sbom.json",
            "sha256": sha256(evidence / "sbom.json"),
        },
        "releaseSigner": {
            "passed": release_signer_test["passed"],
            "algorithm": release_signer_test["algorithm"],
            "signatureVerified": release_signer_test["signatureVerified"],
            "tamperRejected": release_signer_test["tamperRejected"],
            "testKeyEphemeral": release_signer_test["testKeyEphemeral"],
            "manifestTool": "scripts/ReleaseArtifactSigner.java",
            "smokeResult": "release-signer-smoke.json",
            "scope": release_signer_test["scope"],
        },
        "signedDesignSigner": {
            "passed": signed_design_test["passed"],
            "algorithm": signed_design_test["algorithm"],
            "validSignatureVerified": signed_design_test["validSignatureVerified"],
            "signatureTamperRejected": signed_design_test["signatureTamperRejected"],
            "unknownKeyIdRejected": signed_design_test["unknownKeyIdRejected"],
            "testKeyEphemeral": signed_design_test["testKeyEphemeral"],
            "signerTool": "scripts/SignedDesignPackageSigner.java",
            "smokeResult": "signed-design-signer-smoke.json",
            "scope": signed_design_test["scope"],
        },
        "boundaryCheck": {"command": "python scripts/check_boundaries.py", "passed": True},
        "scaleProbe": {
            "arena": f"{scale_document['arena']['width']}x{scale_document['arena']['height']}",
            "players": scale_document["arena"]["players"],
            "rounds": scale_document["arena"]["rounds"],
            "elapsedMillis": scale_document["result"]["elapsedMillis"],
            "finalStateHash": scale_document["result"]["stateHash"],
            "test": scale_document["test"],
            "scope": "Single-process pure simulation unit test, not server or network load",
        },
        "paper": {
            "version": server_version(evidence / "paper-console.log"),
            "passed": paper["passed"],
            "serverSha256": paper["server_sha256"],
            "pluginSha256": paper["plugin_sha256"],
            "result": "paper-result.json",
            "consoleLog": "paper-console.log",
        },
        "folia": {
            "version": server_version(evidence / "folia-console.log"),
            "passed": folia["passed"],
            "serverSha256": folia["server_sha256"],
            "pluginSha256": folia["plugin_sha256"],
            "result": "folia-result.json",
            "consoleLog": "folia-console.log",
        },
        "scope": summary_scope,
    }
    if player_workflow is not None:
        summary["automatedPlayerWorkflow"] = {
            "passed": player_workflow["passed"],
            "minecraftVersion": player_workflow["minecraftVersion"],
            "players": player_workflow["players"],
            "stages": [entry["stage"] for entry in player_workflow["checks"] if entry.get("stage") != "client-login"],
            "p5EcologyStudy": p5_ecology_study_result(player_workflow),
            "p4AtLeastOnceAndAck": p4_delivery_result(player_workflow),
            "p6FactoryFailureRecovery": p6_factory_failure_result(player_workflow),
            "p8DisconnectReconnect": p8_reconnect_result(player_workflow),
            "humanAcceptance": False,
            "result": "player-workflow-result.json",
            "consoleLog": "player-paper-console.log",
            "scope": "Automated offline clients exercised Paper user workflows. This is not human acceptance.",
        }
    if folia_player_workflow is not None:
        live_disable = next((entry for entry in folia_player_workflow["checks"] if entry.get("stage") == "P1-live-disable"), {})
        if sustained_load is not None:
            summary["foliaSustainedLoadTest"] = {
                **{key: value for key, value in sustained_load.items() if key != "stage"},
                "result": "folia-player-workflow-result.json",
                "runtimeSample": "folia-runtime-sample.json",
                "scope": "Controlled P1 simulation workload with four concurrent worlds and four agents per world across Folia regions. This does not represent production world activity, real players, or multi-hardware capacity.",
            }
        summary["foliaAutomatedPlayerWorkflow"] = {
            "passed": folia_player_workflow["passed"],
            "minecraftVersion": folia_player_workflow["minecraftVersion"],
            "players": folia_player_workflow["players"],
            "stages": [entry["stage"] for entry in folia_player_workflow["checks"]
                       if entry.get("stage") not in ("client-login", "P11-sustained-load")],
            "p5EcologyStudy": p5_ecology_study_result(folia_player_workflow),
            "p4AtLeastOnceAndAck": p4_delivery_result(folia_player_workflow),
            "p6FactoryFailureRecovery": p6_factory_failure_result(folia_player_workflow),
            "regionTravel": folia_player_workflow.get("regionTravel"),
            "liveDisable": {key: live_disable[key] for key in ("seed", "savedTick", "replayFrames") if key in live_disable},
            "p8DisconnectReconnect": p8_reconnect_result(folia_player_workflow),
            "sustainedLoadTest": sustained_load is not None,
            "humanAcceptance": False,
            "result": "folia-player-workflow-result.json",
            "consoleLog": "folia-player-console.log",
            "scope": "Automated offline clients exercised command-driven P1 and P2-P10 across Folia regions, followed by a live plugin-disable checkpoint/replay check. This is not human acceptance.",
        }
        summary["foliaRuntimeSample"] = {
            **live_disable["runtimeSample"],
            "file": "folia-runtime-sample.json",
            "scope": "Five-second TPS/MSPT samples collected from the running Folia test server during the automated multi-region workflow and any configured sustained P1 simulation workload. This is not a production capacity study.",
        }
    (evidence / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"P11 verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
