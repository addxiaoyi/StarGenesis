"""Validate and archive the finite radio journal batch without overwriting evidence."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


WORK = Path(__file__).resolve().parent
ROOT = WORK.parents[1]
TARGET = ROOT / "docs/evidence/p4/journal-rotation"
LABELS = ("java21", "java25")
PROBES = ("radio-journal-lock", "worker-lock", "settlement-lock", "release-signer", "design-signer")
NEW_SUITES = {"RadioJournalRotationTest": 17, "RadioJournalPersistenceTest": 16}
EXPECTED_TOTALS = {"tests": 720, "failures": 0, "errors": 0, "skipped": 0}


def ensure(condition, message):
    if not condition:
        raise ValueError(message)


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    return sha_bytes(path.read_bytes())


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        ensure(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def describe(path):
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": sha_bytes(data)}


def source_hashes():
    paths = [ROOT / "pom.xml", ROOT / ".github/workflows/verify.yml"]
    paths.extend(path for path in (ROOT / "scripts").rglob("*")
                 if path.is_file() and "__pycache__" not in path.parts)
    for module in ROOT.glob("stargenesis-*"):
        if module.is_dir():
            paths.extend(module.glob("pom.xml"))
            paths.extend(path for path in (module / "src").rglob("*") if path.is_file())
    return {path.relative_to(ROOT).as_posix(): sha(path) for path in sorted(paths)}


def radio_probe_bytes():
    path = ROOT / "scripts/test_radio_journal_process_lock.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    values = [ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
              and any(isinstance(target, ast.Name) and target.id == "PROBE_SOURCE" for target in node.targets)]
    ensure(len(values) == 1 and type(values[0]) is str, "Missing unique radio probe source")
    return values[0].encode("utf-8")


def validate_probe(name, label, jar_hash, sbom_hash):
    path = WORK / f"{name}-{label}.json"
    result = read_json(path)
    ensure(result.get("passed") is True, f"Probe did not pass: {path.name}")
    ensure((WORK / f"{name}-{label}.log").is_file(), f"Missing probe log: {path.name}")
    if name in ("radio-journal-lock", "worker-lock", "settlement-lock"):
        ensure(result.get("pluginSha256") == jar_hash, f"Probe artifact mismatch: {path.name}")
        expected_probe = radio_probe_bytes() if name == "radio-journal-lock" else (
            ROOT / "scripts" / ("WorkerDirectoryLockProbe.java" if name == "worker-lock" else "SettlementLockProbe.java")
        ).read_bytes()
        ensure(result.get("probeSha256") == sha_bytes(expected_probe), f"Probe source mismatch: {path.name}")
        major = "21" if label == "java21" else "25"
        ensure(f'openjdk version "{major}.' in result.get("java", ""), f"Unexpected probe JDK: {path.name}")
        cases = result.get("cases")
        ensure(type(cases) is list and len(cases) == (2 if name == "settlement-lock" else 3),
               f"Incomplete process case matrix: {path.name}")
        lock_cases = cases[:2] if name == "radio-journal-lock" else cases
        ensure({case.get("holderTerminated") for case in lock_cases} == {False, True},
               f"Missing process termination case: {path.name}")
        for case in lock_cases:
            ensure(type(case.get("holderTerminated")) is bool
                   and case.get("holderExitCode") == (1 if case["holderTerminated"] else 0),
                   f"Unexpected holder outcome: {path.name}")
            fields = {"radio-journal-lock": ("loadRejectedWhileHeld", "saveRejectedWhileHeld",
                      "loadAndSaveAfterRelease", "committedBytesPreserved"),
                      "worker-lock": ("sameJvmAndReloadClassloaderRejected", "otherProcessRejectedWhileHeld", "acquiredAfterRelease"),
                      "settlement-lock": ("blockedWhileHeld", "acquiredAfterRelease")}[name]
            ensure(all(case.get(field) is True for field in fields), f"Missing lock assertions: {path.name}")
            if name == "settlement-lock":
                ensure(case.get("contenderExitCode") == 0, f"Settlement contender failed: {path.name}")
        if name == "worker-lock":
            ensure({(case.get("shutdownTimedOut"), case["holderTerminated"]) for case in cases}
                   == {(False, False), (True, False), (True, True)}, f"Missing worker shutdown case: {path.name}")
        if name == "radio-journal-lock":
            case = cases[2]
            ensure(all(case.get(field) is True for field in ("staleLoadPrecededOtherJvmRotation", "staleSaveRejected",
                       "manifestAndArchiveBytesPreserved", "journalRecovered", "archivedRetrySuppressed", "archivedPayloadConflictRejected"))
                   and case.get("archiveCount") == 1, f"Missing stale-writer assertions: {path.name}")
            files = case.get("committedFiles", {})
            ensure(set(files) == {"radio-state.json", "radio-state.json.archives/segment-0001.json"},
                   f"Unexpected committed files: {path.name}")
            ensure(all(type(record.get("bytes")) is int and record["bytes"] > 0
                       and re.fullmatch(r"[0-9a-f]{64}", record.get("sha256", "")) for record in files.values()),
                   f"Missing committed byte hashes: {path.name}")
    elif name == "release-signer":
        ensure(result.get("sourceJarSha256") == jar_hash and result.get("sourceSbomSha256") == sbom_hash,
               f"Release signer artifact mismatch: {path.name}")
        ensure(all(result.get(field) is True for field in ("testKeyEphemeral", "signatureVerified",
                   "tamperRejected", "privateTestKeyRemoved")), f"Incomplete release signer assertions: {path.name}")
    else:
        ensure(result.get("designSourceSha256") == sha(ROOT / "stargenesis-design/src/main/resources/examples/blueprint.json"),
               f"Design signer source mismatch: {path.name}")
        ensure(all(result.get(field) is True for field in ("testKeyEphemeral", "validSignatureVerified",
                   "signatureTamperRejected", "unknownKeyIdRejected", "privateTestKeyRemoved")),
               f"Incomplete design signer assertions: {path.name}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    expected_sources = read_json(WORK / "source-hashes.json")
    ensure(source_hashes() == expected_sources, "Build input bytes changed since the batch began")
    snapshot_sha = sha(WORK / "source-hashes.json")
    inputs = {}

    def stage(path, name):
        ensure(path.is_file() and name not in inputs, f"Missing or duplicate evidence: {name}")
        inputs[name] = (path, path.read_bytes())

    builds, probes, artifacts, reports_by_jdk = {}, {}, {}, {}
    for label in LABELS:
        build_path = WORK / f"build-{label}.json"
        build = read_json(build_path)
        ensure(build.get("passed") is True and build.get("java") == label, f"Missing passed build: {label}")
        ensure(build.get("sourceHashesSha256") == snapshot_sha, f"Build source snapshot mismatch: {label}")
        command = [str(ROOT / "mvnw.cmd"), "-B", "-ntp", "-DforkCount=0",
                   "-Phelstera-bridge,craftengine-bridge,cookery-bridge", "clean", "verify"]
        ensure(build.get("command") == command, f"Not full three-profile clean verify: {label}")
        log = WORK / f"verify-{label}.log"
        ensure(build.get("logSha256") == sha(log) and b"BUILD SUCCESS" in log.read_bytes(), f"Build log mismatch: {label}")
        reports = sorted((WORK / label).glob("TEST-*.xml"))
        ensure(len(reports) == build.get("reports") == 92, f"Incorrect suite count: {label}")
        totals = dict.fromkeys(EXPECTED_TOTALS, 0)
        suites = {}
        for path in reports:
            suite = ET.parse(path).getroot()
            name = suite.get("name")
            ensure(suite.tag == "testsuite" and name not in suites, f"Malformed or duplicate suite: {path}")
            counts = {key: int(suite.get(key, "0")) for key in totals}
            ensure(counts["tests"] == len(suite.findall("testcase")), f"XML testcase count differs: {path}")
            ensure(all(counts[key] == 0 for key in ("failures", "errors", "skipped"))
                   and not any(suite.findall(".//" + key) for key in ("failure", "error", "skipped")), f"Nonpassing XML: {path}")
            suites[name] = counts["tests"]
            for key, value in counts.items():
                totals[key] += value
            stage(path, f"{label}/reports/{path.name}")
        ensure(totals == build.get("tests") == EXPECTED_TOTALS, f"XML totals differ: {label}")
        for name, count in NEW_SUITES.items():
            ensure([value for suite_name, value in suites.items() if suite_name.endswith("." + name)] == [count],
                   f"Missing new test suite: {label}/{name}")
        reports_by_jdk[label] = {"suites": len(reports), "totals": totals, "newSuites": NEW_SUITES}
        hashes = build.get("artifacts")
        required = {"StarGenesis-4.0.0-SNAPSHOT.jar", "sbom.json", *(
            f"stargenesis-{module}-4.0.0-SNAPSHOT.jar" for module in ("helstera", "craftengine", "cookery"))}
        ensure(type(hashes) is dict and set(hashes) == required, f"Incorrect artifact set: {label}")
        artifacts[label] = {}
        for name, expected_hash in hashes.items():
            path = WORK / label / name
            record = describe(path)
            ensure(record["sha256"] == expected_hash, f"Artifact bytes differ: {label}/{name}")
            artifacts[label][name] = {"localPath": path.relative_to(ROOT).as_posix(), **record,
                                      "copiedIntoArchive": name == "sbom.json"}
        stage(WORK / label / "sbom.json", f"{label}/sbom.json")
        probes[label] = {}
        for name in PROBES:
            probes[label][name] = validate_probe(name, label, hashes["StarGenesis-4.0.0-SNAPSHOT.jar"], hashes["sbom.json"])
            for extension in ("json", "log"):
                stage(WORK / f"{name}-{label}.{extension}", f"probes/{name}-{label}.{extension}")
        stage(build_path, build_path.name)
        stage(log, log.name)
        builds[label] = build

    focused = (WORK / "focused-java21-retry.log").read_text(encoding="utf-8", errors="replace")
    ensure("BUILD SUCCESS" in focused and "Tests run: 87, Failures: 0, Errors: 0, Skipped: 0" in focused,
           "Focused radio regression did not pass")
    python_log = (WORK / "python-regression.log").read_text(encoding="utf-8", errors="replace")
    ensure(re.search(r"Ran 64 tests in [^\r\n]+\r?\n\r?\nOK(?:\r?\n|$)", python_log), "Python regression did not pass")
    node_lines = (WORK / "node-regression.log").read_text(encoding="utf-8").splitlines()
    ensure(all(line in node_lines for line in ("# tests 6", "# pass 6", "# fail 0", "# cancelled 0", "# skipped 0", "# todo 0")),
           "Node regression did not pass")
    ensure((WORK / "boundary.log").read_text(encoding="utf-8").startswith("PASS: public API, simulation, design and discovery"),
           "Boundary scan did not pass")
    ensure((WORK / "batch-stdout.log").read_text(encoding="utf-8").rstrip().endswith("COMPLETE")
           and (WORK / "batch-stderr.log").stat().st_size == 0, "Verification batch did not complete cleanly")
    for path in sorted(WORK.glob("*.log")):
        if path.name not in inputs:
            stage(path, path.name)
    for path in sorted(WORK.glob("*source-hashes*.json")):
        stage(path, path.name)
    for name in ("verify_batch.py", "archive_batch.py", "launch_batch.py", "diagnose.py"):
        stage(WORK / name, name)

    source_paths = {ROOT / path for path in expected_sources if "/paper/communication/" in path}
    source_paths.update(ROOT / path for path in expected_sources if Path(path).name.startswith("radio-v1-"))
    source_paths.update(ROOT / "scripts" / name for name in ("test_radio_journal_process_lock.py",
        "test_worker_directory_lock.py", "WorkerDirectoryLockProbe.java", "test_settlement_process_lock.py", "SettlementLockProbe.java",
        "test_release_artifact_signer.py", "ReleaseArtifactSigner.java", "test_signed_design_package_signer.py", "SignedDesignPackageSigner.java"))
    source_paths.update(ROOT / path for path in ("stargenesis-paper/src/main/resources/plugin.yml", ".github/workflows/verify.yml",
        "docs/RADIO_JOURNAL_ROTATION.md", "docs/RADIO_RETENTION.md", "docs/PRODUCT_CONTRACT.md"))
    source_records = {}
    for path in sorted(source_paths):
        relative = path.relative_to(ROOT).as_posix()
        source_records[relative] = describe(path)
        stage(path, "sources/" + relative + ".txt")
    stage_data = radio_probe_bytes()
    inputs["sources/RadioJournalProcessProbe.java.txt"] = (None, stage_data)
    ensure(source_hashes() == expected_sources, "Build source snapshot changed during preflight")
    failure_notes = {
        "focused-java21.log": "Initial run reached 87 tests with one error: archiveFailureDoesNotFlushAnOtherwiseUnsavedDirtyTick reopened the service while the intentional archive-directory blocker still existed. The fixture was corrected to remove the blocker before reopening; this log does not establish a production defect.",
        "focused-java21-native-memory-failure.log": "Focused attempt failed with native allocation OutOfMemoryError while reading a bounded radio file. The final implementation reads fixed chunks into a size-checked array to avoid the large temporary direct buffer.",
        "focused-java21-interrupted.log": "Attempt ended during RadioCapacityTest without a build terminal; no pass is inferred.",
        "20261126T225947-focused-java21-retry.log": "Another focused attempt failed with native allocation OutOfMemoryError, unable to allocate 7073800 bytes. Preserved with the corresponding batch stderr and source snapshot.",
        "20261126T230829-verify-java21.log": "Earlier full Java21 verify failed; corresponding batch stderr is preserved. Superseded by both final complete clean verify runs, not counted as a pass.",
        "focused-java21-retry.log": "Final focused run passed all 87 tests. Its source identity is established by the subsequent complete final source-bound builds, not by an independent focused-run snapshot."
    }
    summary = {
        "date": "2026-11-26", "passed": True, "phaseGatePassed": False,
        "repositoryHeadAtArchive": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "scope": "Finite explicit P4 radio journal rotation retaining message idempotency and contract identity, v1 compatibility, atomic publication, archive integrity, process locks and stale-writer rejection.",
        "newJavaTests": sum(NEW_SUITES.values()), "newSuites": NEW_SUITES, "focusedTests": 87,
        "builds": builds, "validatedReports": reports_by_jdk, "probes": probes,
        "pythonRegressionTests": 64, "nodeRegressionTests": 6, "boundaryScanPassed": True,
        "regressionCommands": {
            "cwd": str(ROOT),
            "python": [".work/python-3.12.10/python.exe", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"],
            "node": ["node", "--test", "scripts/test_offline_client_lifecycle_smoke.cjs",
                     "scripts/test_offline_client_radio_lifecycle_smoke.cjs", "scripts/test_offline_client_match_admin_smoke.cjs"],
            "boundary": [".work/python-3.12.10/python.exe", "scripts/check_boundaries.py"]
        },
        "sourceSnapshotScope": "Root/module POMs, verification workflow, every non-pycache scripts file and all module src files; exact bytes checked unchanged before/after builds and archive.",
        "sourceHashesSha256": snapshot_sha, "sourceSnapshots": source_records,
        "artifacts": artifacts, "artifactStorage": "Four exact JARs per JDK remain under .work/radio-journal/{java21,java25}; only their local paths, lengths and hashes are archived. SBOMs, all 184 XML reports, complete logs and relevant source copies are included.",
        "failureNotes": failure_notes,
        "persistence": {"snapshotSchema": 1, "journalSchema": 2, "maxArchives": 16,
                        "maxFileBytes": 16777216, "maxTotalDataBytes": 134217728,
                        "retiredMessageIdentitiesPreserved": True, "retiredContractIdsReusable": False},
        "limits": [
            "Finite history capacity only: segment and byte limits can stop further rotation; no automatic evidence deletion or infinite lifetime.",
            "Local JVM/file tests exercise atomic publication, byte preservation and locking; no new Paper/Folia lifecycle or human communication acceptance was run in this batch.",
            "Every save synchronously verifies retained archive integrity; these tests do not establish long-term server throughput, latency, network-filesystem behavior or power-loss durability.",
            "Archived retry comparisons use a canonical request SHA-256 digest and rely on collision resistance; archive hashes provide integrity, not authenticated publisher provenance.",
            "Signing probes use ephemeral removed keys, not production trust roots or an official release signature; real settlement remains disabled.",
            "P4 phase gate remains unpassed; new runtime lifecycle, human, production capacity and operational acceptance require separate evidence."
        ]
    }
    payloads = {name: data for name, (_, data) in inputs.items()}
    payloads["artifacts.json"] = json_bytes(artifacts)
    payloads["summary.json"] = json_bytes(summary)
    payloads["manifest.json"] = json_bytes({"algorithm": "SHA-256", "rawByteHashes": True, "manifestSelfExcluded": True,
        "files": {name: {"bytes": len(data), "sha256": sha_bytes(data)} for name, data in sorted(payloads.items())}})
    for name, (path, data) in inputs.items():
        ensure(path is None or path.read_bytes() == data, f"Evidence changed after preflight: {name}")
    if TARGET.exists():
        existing = {path.relative_to(TARGET).as_posix() for path in TARGET.rglob("*") if path.is_file()}
        ensure(existing.issubset(payloads), "Unexpected existing archive files")
        ensure(all((TARGET / name).read_bytes() == payloads[name] for name in existing), "Refusing to overwrite different evidence")
    if not args.check_only:
        for name, data in payloads.items():
            destination = TARGET / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                with destination.open("xb") as output:
                    output.write(data)
            ensure(destination.read_bytes() == data, f"Archive copy differs: {name}")
        ensure({path.relative_to(TARGET).as_posix() for path in TARGET.rglob("*") if path.is_file()} == set(payloads),
               "Archive file set differs from manifest payload")
    ensure(source_hashes() == expected_sources, "Build source snapshot changed during archive")
    print(json.dumps({"passed": True, "checkOnly": args.check_only, "archive": str(TARGET),
                      "files": len(payloads), "newJavaTests": 33, "builds": reports_by_jdk}, indent=2))


if __name__ == "__main__":
    main()
