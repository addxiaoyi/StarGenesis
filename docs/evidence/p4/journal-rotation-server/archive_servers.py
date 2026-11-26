"""Archive completed P4 rotation server attempts, preserving failures and byte provenance."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import types


WORK = Path(__file__).resolve().parent
ROOT = WORK.parents[1]
TARGET = ROOT / "docs/evidence/p4/journal-rotation-server"
BASELINE = ROOT / "docs/evidence/p4/journal-rotation"
PHASES = ("prepare", "rotate", "verify")
KINDS = ("paper", "folia")
SOURCE_NAMES = {".work/radio-rotation-lifecycle/run_servers.py", *(
    "scripts/" + name for name in ("server_radio_rotation_smoke.py", "offline_client_radio_rotation_smoke.cjs",
    "server_smoke.py", "offline_client_workflow_smoke.cjs", "test_server_radio_rotation_smoke.py",
    "test_offline_client_radio_rotation_smoke.cjs"))}
CI_SHA = "87abf9cfe4505b0cb5f71992a81f9743493a720d"


def ensure(condition, message):
    if not condition:
        raise ValueError(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        ensure(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_pairs)


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def describe(path):
    with path.open("rb") as source:
        value = hashlib.file_digest(source, "sha256").hexdigest()
    return {"bytes": path.stat().st_size, "sha256": value}


def relative(path):
    return path.relative_to(ROOT).as_posix()


def batch_path(value):
    path = Path(value)
    path = (WORK / path if not path.is_absolute() else path).resolve()
    ensure(path.parent == WORK and re.fullmatch(r"runs-\d{8}T\d{6}", path.name), f"Not an isolated attempt: {path}")
    ensure(path.is_dir() and not path.is_symlink(), f"Missing attempt: {path}")
    ensure((path / "batch.json").is_file(), f"Attempt has no final batch.json: {path}")
    return path


def command_value(command, flag):
    ensure(command.count(flag) == 1, f"Missing or repeated command option: {flag}")
    return command[command.index(flag) + 1]


def validate_sources(batch, record, compare_current):
    hashes = record.get("sourceHashes")
    ensure(type(hashes) is dict and set(hashes) == SOURCE_NAMES, "Incomplete seven-source startup snapshot")
    records = {}
    for name, expected in hashes.items():
        snapshot = batch / "sources" / (name + ".txt")
        ensure(snapshot.is_file() and not snapshot.is_symlink(), f"Missing startup source copy: {name}")
        source_record = describe(snapshot)
        ensure(source_record["sha256"] == expected, f"Startup source hash mismatch: {name}")
        if compare_current:
            ensure(describe(ROOT / name) == source_record, f"Runtime source changed since startup: {name}")
        records[name] = source_record
    actual = {path.relative_to(batch / "sources").as_posix() for path in (batch / "sources").rglob("*") if path.is_file()}
    ensure(actual == {name + ".txt" for name in hashes}, "Unexpected startup source inventory")
    return records


def source_comparison(startup, current):
    if startup is None:
        return {"startupSnapshotAvailable": False, "files": {},
                "scope": "Attempt-time startup source bytes were not captured; differences cannot be determined."}
    files = {}
    for name in sorted(SOURCE_NAMES):
        startup_record = startup[name]
        current_record = current[name]
        files[name] = {"startup": startup_record, "currentAtArchive": current_record,
                       "matches": startup_record == current_record}
    return {"startupSnapshotAvailable": True, "files": files,
            "differentFiles": [name for name, value in files.items() if not value["matches"]]}


def frozen_validator(batch):
    path = batch / "sources/scripts/server_radio_rotation_smoke.py.txt"
    module = types.ModuleType("frozen_radio_rotation_archive_validator")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def verify_inventory(directory, expected, result_name, parse_json=False):
    ensure(type(expected) is dict, f"Missing artifact inventory: {directory}")
    paths = {path.relative_to(directory).as_posix(): path for path in directory.rglob("*") if path.is_file()}
    ensure(set(paths) == set(expected) | {result_name}, f"Artifact inventory differs: {directory}")
    for name, record in expected.items():
        path = paths[name]
        ensure(not path.is_symlink() and describe(path) == record, f"Raw artifact bytes differ: {path}")
        if parse_json and path.suffix == ".json":
            read_json(path)


def validate_raw_journal(validator, directory):
    journal = validator.read_journal(directory)
    documents = [validator.active(journal), *journal["archives"].values()]
    for snapshot in documents:
        ensure(type(snapshot["radio"]["tick"]) is int and 0 <= snapshot["radio"]["tick"] <= 9223372036854775807,
               f"Invalid radio clock type/range: {directory}")
        ensure(type(snapshot["contracts"]["tick"]) is int, f"Invalid contract clock type: {directory}")
    for reference in journal["document"].get("archives", []):
        ensure(set(reference) == {"generation", "sha256", "bytes"}, f"Unexpected archive reference fields: {directory}")
        ensure(type(reference["generation"]) is int and 1 <= reference["generation"] <= 16
               and type(reference["bytes"]) is int and 0 < reference["bytes"] <= 16777216
               and re.fullmatch(r"[0-9a-f]{64}", reference["sha256"]), f"Invalid archive reference types: {directory}")
    return journal


def validate_server(batch, kind, command, validator, expected_plugin):
    target = batch / kind
    evidence = target / "radio-rotation-evidence"
    result = read_json(evidence / "result.json")
    ensure(type(result.get("passed")) is bool and result["passed"] is True, f"Server attempt did not pass: {kind}")
    ensure(result.get("kind") == kind and result.get("withObserver") is True, f"Wrong server kind or client count: {kind}")
    runs = result.get("runs")
    ensure(type(runs) is list and [run.get("phase") for run in runs] == list(PHASES), f"Not exactly three ordered phases: {kind}")
    verify_inventory(evidence, result.get("artifacts"), "result.json", True)
    plugin = Path(command_value(command, "--plugin-jar"))
    server = Path(command_value(command, "--server-jar"))
    plugin_record, server_record = describe(plugin), describe(server)
    ensure(plugin_record["sha256"] == expected_plugin, f"Plugin differs from the source-bound Java build: {kind}")
    ensure(result.get("inputs") == {"pluginSha256": expected_plugin, "serverSha256": server_record["sha256"]},
           f"Server input hashes differ: {kind}")
    ensure(describe(target / "plugins/StarGenesis.jar") == plugin_record, f"Installed plugin bytes differ: {kind}")
    previous = None
    summaries = []
    for phase, run in zip(PHASES, runs):
        destination = evidence / phase
        ensure(type(run.get("exitCode")) is int and run["exitCode"] == 0
               and run.get("validatedShutdownJournal") is True, f"Phase exit or validation failed: {kind}/{phase}")
        smoke = read_json(destination / "smoke-result.json")
        ensure(smoke == run.get("server"), f"Copied server result differs: {kind}/{phase}")
        checks = smoke.get("checks")
        ensure(smoke.get("passed") is True and smoke.get("kind") == kind and type(checks) is dict
               and {"server_ready", "plugin_enabled", "plugin_disabled", "clean_exit", "player_workflow",
                    "p4_virtual_radio", "p4_radio_protocol_pinned", "p4_service_contract_pinned"} <= set(checks)
               and all(value is True for value in checks.values()), f"Incomplete strict server checks: {kind}/{phase}")
        ensure(smoke.get("server_sha256") == server_record["sha256"]
               and smoke.get("plugin_sha256") == expected_plugin, f"Phase artifact hashes differ: {kind}/{phase}")
        ensure(smoke.get("log_sha256") == describe(destination / "smoke-console.log")["sha256"],
               f"Console log bytes differ: {kind}/{phase}")
        console = (destination / "smoke-console.log").read_text(encoding="utf-8", errors="replace")
        ensure("StarGenesis stopped cleanly" in console and "StarGenesis shutdown incomplete" not in console,
               f"Missing clean plugin shutdown: {kind}/{phase}")
        ensure(smoke.get("heap_mb") == int(command_value(command, "--heap-mb")), f"Unexpected server heap: {kind}/{phase}")
        workflow = smoke.get("player_workflow")
        ensure(type(workflow) is dict and workflow.get("passed") is True and workflow.get("humanAcceptance") is False,
               f"Client workflow did not strictly pass: {kind}/{phase}")
        validator.validate_workflow(workflow, phase, True)
        raw_workflow = read_json(destination / "player-workflow-result.json")
        normalized = copy.deepcopy(workflow)
        ensure(normalized.pop("log") == "player-workflow.log", f"Unexpected client log binding: {kind}/{phase}")
        ensure(normalized == raw_workflow and raw_workflow.get("passed") is True, f"Raw client result differs: {kind}/{phase}")
        client = validate_raw_journal(validator, destination / "client-journal")
        shutdown = validate_raw_journal(validator, destination / "shutdown-journal")
        ensure(client == workflow.get("journalAtFinish"), f"Raw client journal differs: {kind}/{phase}")
        ensure(shutdown == run.get("shutdownJournal"), f"Raw shutdown journal differs: {kind}/{phase}")
        validator.same_committed_journal(shutdown, client)
        validator.validate_phase_journal(shutdown, phase, workflow["identities"], previous)
        operators = read_json(destination / "ops.json")
        ensure(operators == [{"uuid": workflow["identities"]["requester"], "name": "SGReview", "level": 4,
                              "bypassesPlayerLimit": False}], f"Operator privileges changed: {kind}/{phase}")
        summaries.append({"phase": phase, "passed": True, "checks": len(checks), "players": workflow["players"],
                          "clientChecks": workflow["checks"], "files": shutdown["files"],
                          "tick": validator.active(shutdown)["radio"]["tick"]})
        previous = shutdown
    ensure(validate_raw_journal(validator, target / "plugins/StarGenesis") == previous, f"Final installed journal differs: {kind}")
    return {"passed": True, "phases": summaries,
            "serverArtifact": {"localPath": str(server), **server_record, "binaryArchived": False},
            "pluginArtifact": {"localPath": str(plugin), **plugin_record, "binaryArchived": False}}


def validate_ci(path):
    value = read_json(path)
    ensure(value.get("headSha") == CI_SHA and value.get("repository") == "addxiaoyi/StarGenesis", "Wrong CI commit/repository")
    run = value["run"]
    ensure(run.get("headSha") == CI_SHA and run.get("name") == "verify", "Wrong CI run identity")
    jobs = value.get("jobs")
    ensure(type(jobs) is list and {job.get("name") for job in jobs} == {"java (21)", "java (25)"}
           and len(jobs) == 2, "Missing CI Java matrix jobs")
    for job in jobs:
        ensure(job.get("run_id") == run["id"] and job.get("status") == "completed" and job.get("conclusion") == "success",
               "A CI matrix job did not complete successfully")
        ensure(all(step.get("status") == "completed" and step.get("conclusion") in ("success", "skipped")
                   for step in job.get("steps", [])), "A recorded CI step failed")
    ensure(value["verification"].get("allReturnedJobsCompletedSuccessfully") is True, "CI verification did not pass")
    return {"headSha": CI_SHA, "runId": run["id"], "jobs": [
        {key: job[key] for key in ("id", "name", "status", "conclusion")} for job in jobs],
        "runLevelFinalConclusionAsserted": False, "record": {"localPath": relative(path), **describe(path)},
        "scope": "Selected connector-observed fields confirm both jobs completed successfully; this is not a raw API response or a freshly verified run-level final conclusion."}


def baseline_reference():
    manifest_path = BASELINE / "manifest.json"
    manifest = read_json(manifest_path)
    verify_inventory(BASELINE, manifest["files"], "manifest.json")
    summary = read_json(BASELINE / "summary.json")
    ensure(summary.get("passed") is True and summary["builds"]["java21"]["tests"] == {
        "tests": 720, "failures": 0, "errors": 0, "skipped": 0}, "Missing source-bound 720-test baseline")
    sources = read_json(BASELINE / "source-hashes.json")
    for name, expected in sources.items():
        if name == "pom.xml" or name.startswith("stargenesis-"):
            ensure(describe(ROOT / name)["sha256"] == expected, f"Product build input changed: {name}")
    return {"archive": relative(BASELINE), "manifest": describe(manifest_path),
            "summary": describe(BASELINE / "summary.json"), "allRawManifestEntriesReverified": len(manifest["files"]),
            "productInputsStillMatchBuildSnapshot": True,
            "pluginSha256": summary["builds"]["java21"]["artifacts"]["StarGenesis-4.0.0-SNAPSHOT.jar"],
            "scope": "Reference to existing Java21/25 720-test archive; XML reports and binary JARs are not recopied."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--final-batch", required=True)
    parser.add_argument("--diagnostic-batch", action="append")
    parser.add_argument("--ci", type=Path, default=WORK / "ci-87abf9c.json")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    final = batch_path(args.final_batch)
    diagnostics = [batch_path(value) for value in args.diagnostic_batch] if args.diagnostic_batch is not None else sorted(
        path for path in WORK.glob("runs-*") if path.is_dir() and path != final)
    ensure(final not in diagnostics and len(set(diagnostics)) == len(diagnostics), "Repeated final/diagnostic batch")
    for batch in diagnostics:
        batch_path(str(batch))
    inputs = {}

    def stage(path, name):
        ensure(path.is_file() and not path.is_symlink(), f"Missing or symlinked evidence: {path}")
        data = path.read_bytes()
        if name in inputs:
            ensure(inputs[name][1] == data, f"Conflicting evidence destination: {name}")
        inputs[name] = (path, data)

    def stage_tree(directory, prefix):
        if directory.is_dir():
            for path in sorted(directory.rglob("*")):
                ensure(not path.is_symlink(), f"Symlinked raw evidence: {path}")
                if path.is_file():
                    stage(path, prefix + "/" + path.relative_to(directory).as_posix())

    attempts = {}
    for batch in [*diagnostics, final]:
        prefix = "attempts/" + batch.name
        record = read_json(batch / "batch.json")
        ensure(type(record.get("passed")) is bool, f"Missing terminal batch status: {batch}")
        artifact_records, inventory_records = {}, {}
        for run in record.get("runs", []):
            command = run["command"]
            artifact_records[run["kind"]] = {}
            for label, flag in (("server", "--server-jar"), ("plugin", "--plugin-jar")):
                path = Path(command_value(command, flag))
                artifact_records[run["kind"]][label] = {"localPath": str(path), **describe(path),
                    "binaryArchived": False, "hashCheckedAtArchive": True}
        for path in sorted(batch.iterdir()):
            if path.is_file():
                stage(path, prefix + "/" + path.name)
        stage_tree(batch / "sources", prefix + "/sources")
        for kind in KINDS:
            target = batch / kind
            if not target.exists():
                continue
            evidence_result = target / "radio-rotation-evidence/result.json"
            if evidence_result.is_file():
                raw_result = read_json(evidence_result)
                verify_inventory(evidence_result.parent, raw_result.get("artifacts"), "result.json")
                inventory_records[kind] = {"files": len(raw_result["artifacts"]), "passed": True,
                                           "reportedServerAttemptPassed": raw_result.get("passed")}
            for path in sorted(target.iterdir()):
                if path.is_file() and (path.suffix in (".json", ".log", ".properties", ".txt") or path.name.endswith(".log.gz")):
                    stage(path, prefix + "/" + kind + "/" + path.name)
            stage_tree(target / "radio-rotation-evidence", prefix + "/" + kind + "/radio-rotation-evidence")
            stage_tree(target / "logs", prefix + "/" + kind + "/logs")
            stage_tree(target / "crash-reports", prefix + "/" + kind + "/crash-reports")
            stage_tree(target / "config", prefix + "/" + kind + "/config")
            for path in sorted((target / "plugins").glob("radio-rotation-*.json")):
                stage(path, prefix + "/" + kind + "/plugins/" + path.name)
            data = target / "plugins/StarGenesis"
            for name in ("radio-state.json", "radio-state.json.tmp"):
                if (data / name).is_file():
                    stage(data / name, prefix + "/" + kind + "/plugins/StarGenesis/" + name)
            stage_tree(data / "radio-state.json.archives", prefix + "/" + kind + "/plugins/StarGenesis/radio-state.json.archives")
        attempts[batch.name] = {"reportedPassed": record["passed"], "record": record, "isFinalAttempt": batch == final,
                                "sourceStartupSnapshotPresent": "sourceHashes" in record,
                                "artifactRecords": artifact_records, "rawArtifactInventories": inventory_records}
        if "sourceHashes" in record:
            attempts[batch.name]["sourceSnapshots"] = validate_sources(batch, record, False)

    current_source_paths = {}
    for name in sorted(SOURCE_NAMES):
        path = ROOT / name
        current_source_paths[name] = path
        stage(path, "current-sources/" + name + ".txt")
    current_sources = {name: describe(path) for name, path in current_source_paths.items()}
    for attempt in attempts.values():
        attempt["sourceComparison"] = source_comparison(attempt.get("sourceSnapshots"), current_sources)

    baseline = baseline_reference()
    ci = validate_ci(args.ci.resolve())
    final_record = attempts[final.name]["record"]
    validation_errors, servers = [], {}
    final_sources_match_current = False
    try:
        startup_sources = validate_sources(final, final_record, False)
        ensure(final_record["passed"] is True, "Final batch recorded failure; no complete dual-server pass is inferred")
        ensure(startup_sources == current_sources, "Passing final startup source differs from current archived source")
        final_sources_match_current = True
        runs = final_record.get("runs")
        ensure(type(runs) is list and [run.get("kind") for run in runs] == list(KINDS), "Not exactly Paper then Folia")
        validator = frozen_validator(final)
        for kind, run in zip(KINDS, runs):
            ensure(type(run.get("exitCode")) is int and run["exitCode"] == 0, f"Nonzero server harness exit: {kind}")
            command = run["command"]
            ensure(command_value(command, "--kind") == kind and Path(command_value(command, "--directory")).resolve() == final / kind
                   and "--with-observer" in command, f"Unexpected isolated run command: {kind}")
            servers[kind] = validate_server(final, kind, command, validator, baseline["pluginSha256"])
        ensure(validate_sources(final, final_record, False) == startup_sources, "Final startup snapshots changed during validation")
    except (OSError, AssertionError, KeyError, TypeError, ValueError) as failure:
        validation_errors.append(f"{type(failure).__name__}: {failure}")
    passed = not validation_errors and set(servers) == set(KINDS)
    python_log = (WORK / "python-regression.log").read_text(encoding="utf-8", errors="replace")
    ensure(re.search(r"Ran 72 tests in [^\r\n]+\r?\n\r?\nOK(?:\r?\n|$)", python_log), "72-test Python regression did not pass")
    node_lines = (WORK / "node-regression.log").read_text(encoding="utf-8", errors="replace").splitlines()
    ensure(all(line in node_lines for line in ("# tests 10", "# pass 10", "# fail 0", "# cancelled 0", "# skipped 0", "# todo 0")),
           "10-test Node regression did not pass")
    for name in ("python-regression.log", "node-regression.log", "archive_servers.py"):
        stage(WORK / name, name)
    stage(args.ci.resolve(), "ci-87abf9c.json")
    stage(BASELINE / "manifest.json", "references/java-build-manifest.json")
    summary = {"schemaVersion": 1, "taskDate": "2026-11-26", "timeZone": "Asia/Shanghai", "passed": passed,
        "phaseGatePassed": False, "finalBatch": final.name, "validationErrors": validation_errors,
        "repositoryHeadAtArchive": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "servers": servers, "attempts": attempts, "javaBuildEvidence": baseline, "ci": ci,
        "serverAcceptance": "automated_rotation_workflow_passed" if passed else "pending",
        "currentSources": {"files": current_sources,
            "scope": "Exact seven current runtime and validator source files captured at archive preflight; each attempt's startup snapshots are validated and compared separately."},
        "finalSourcesMatchCurrentAtArchive": final_sources_match_current,
        "pythonRegressionTests": 72, "nodeRegressionTests": 10,
        "diagnosticNotes": {
            "runs-20261126T234649": "256 MiB heap exhausted during server remapping/world startup. Root stopped only this isolated JVM at 2026-11-26 23:52:51 Asia/Shanghai to end the failed attempt; exit 1, no clients. Initial runner bytes were not snapshotted at startup and changed during the attempt; no source-bound success is claimed.",
            "runs-20261126T235317": "512 MiB attempt remapped successfully but failed loading server datapacks with native allocation failure (2131520 bytes), under host commit pressure. No product/client acceptance is inferred. Startup source snapshots are preserved separately from later attempts.",
            "runs-20261126T235553": "384 MiB attempt first recorded OutOfMemoryError: Compressed class space in crash-2026-11-26_23.56.28-server.txt at 2026-11-26 23:56:28 Asia/Shanghai. Root subsequently terminated only isolated test JVM PID2356 to restore host memory; harness exited 1. No client workflow or Folia run occurred."
        },
        "storage": "All attempt harness/evidence/server logs, crash reports, results, relevant runtime config/journals and recorded source snapshots are copied. World data, server library JARs and plugin/server binaries are not copied; available attempt binary paths, lengths and SHA-256 values checked at archive are recorded in attempts/artifactRecords, with successful workflow bindings only in servers. Existing Java test XML stays in the referenced source-bound archive.",
        "limits": ["Three ordinary isolated boots per completed server, three offline automated clients. No forced-crash, network filesystem, cross-region, long-load, production capacity or human acceptance.",
                   "A failed or incomplete final attempt sets passed false; success requires both servers and all six ordered phases with strict server/client results and clean shutdown.",
                   "Archive SHA-256 pins demonstrate byte integrity, not publisher signatures or authenticated backup provenance. Real settlement remains disabled and the P4 phase gate remains unpassed.",
                   "CI confirms both recorded Java matrix jobs completed successfully for 87abf9c. The run-level resource final conclusion was not observed and is not asserted."]}
    payloads = {name: data for name, (_, data) in inputs.items()}
    payloads["summary.json"] = encoded(summary)
    payloads["manifest.json"] = encoded({"algorithm": "SHA-256", "rawByteHashes": True, "manifestSelfExcluded": True,
        "files": {name: {"bytes": len(data), "sha256": digest(data)} for name, data in sorted(payloads.items())}})
    for name, (path, data) in inputs.items():
        ensure(path.read_bytes() == data, f"Raw evidence changed during preflight: {name}")
    ensure({name: describe(path) for name, path in current_source_paths.items()} == current_sources,
           "Current source inputs changed during archive preflight")
    if final_sources_match_current:
        ensure(validate_sources(final, final_record, False) == current_sources,
               "Passing final startup source differs from current archived source")
    if TARGET.exists():
        existing = {path.relative_to(TARGET).as_posix() for path in TARGET.rglob("*") if path.is_file()}
        ensure(existing.issubset(payloads), "Unexpected existing archive files")
        ensure(all((TARGET / name).read_bytes() == payloads[name] for name in existing), "Refusing to overwrite different archive bytes")
    if not args.check_only:
        for name, data in payloads.items():
            destination = TARGET / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                with destination.open("xb") as output:
                    output.write(data)
            ensure(destination.read_bytes() == data, f"Published archive bytes differ: {name}")
        verify_inventory(TARGET, read_json(TARGET / "manifest.json")["files"], "manifest.json")
        ensure({name: describe(path) for name, path in current_source_paths.items()} == current_sources,
               "Current source inputs changed during archive publication")
        if final_sources_match_current:
            ensure(validate_sources(final, final_record, False) == current_sources,
                   "Passing final startup source differs from current archived source")
    print(json.dumps({"archivePublished": not args.check_only, "passed": passed, "files": len(payloads),
                      "finalBatch": final.name, "validationErrors": validation_errors, "archive": str(TARGET)}, indent=2))


if __name__ == "__main__":
    main()
