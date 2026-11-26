"""Validate the completed authority-binding batch and copy immutable, byte-exact evidence.

Run only after verify_batch.py has completed both JDK builds and all eight probes.
The built JARs remain in .work; their exact byte lengths and SHA256 values are recorded.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


WORK = Path(__file__).resolve().parent
ROOT = WORK.parents[1]
TARGET = ROOT / "docs/evidence/p9/authority-binding"
LABELS = ("java21", "java25")
PROBES = ("worker-lock", "settlement-lock", "release-signer", "design-signer")
NEW_SUITES = {"SettlementAuthorityBindingTest": 10,
              "SettlementAuthorityCoordinatorTest": 14,
              "SettlementAuthorityRepositoryTest": 11}


def sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha(path: Path) -> str:
    return sha_bytes(path.read_bytes())


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def source_hashes() -> dict[str, str]:
    paths = [ROOT / "pom.xml"]
    for module in ROOT.glob("stargenesis-*"):
        if module.is_dir():
            paths.extend(module.glob("pom.xml"))
            paths.extend(path for path in (module / "src").rglob("*") if path.is_file())
    return {path.relative_to(ROOT).as_posix(): sha(path) for path in sorted(paths)}


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def describe(path: Path) -> dict:
    data = path.read_bytes()
    return {"sha256": sha_bytes(data), "bytes": len(data)}


def validate_probe(name: str, label: str, jar_hash: str, sbom_hash: str) -> dict:
    path = WORK / f"{name}-{label}.json"
    result = read_json(path)
    ensure(result.get("passed") is True, f"Probe did not pass: {path.name}")
    ensure((WORK / f"{name}-{label}.log").is_file(), f"Missing probe log: {name}-{label}")
    if name in ("worker-lock", "settlement-lock"):
        ensure(result.get("pluginSha256") == jar_hash, f"Probe artifact mismatch: {path.name}")
        probe = "WorkerDirectoryLockProbe.java" if name == "worker-lock" else "SettlementLockProbe.java"
        ensure(result.get("probeSha256") == sha(ROOT / "scripts" / probe), f"Probe source mismatch: {path.name}")
        cases = result.get("cases")
        ensure(type(cases) is list and len(cases) == (3 if name == "worker-lock" else 2),
               f"Incomplete lock case matrix: {path.name}")
        for case in cases:
            ensure(case.get("acquiredAfterRelease") is True, f"Lock not acquired after release: {path.name}")
            terminated = case.get("holderTerminated")
            ensure(type(terminated) is bool and case.get("holderExitCode") == (1 if terminated else 0),
                   f"Unexpected lock holder outcome: {path.name}")
            if name == "worker-lock":
                ensure(case.get("sameJvmAndReloadClassloaderRejected") is True
                       and case.get("otherProcessRejectedWhileHeld") is True,
                       f"Worker lock exclusion was not demonstrated: {path.name}")
            else:
                ensure(case.get("blockedWhileHeld") is True and case.get("contenderExitCode") == 0,
                       f"Settlement lock exclusion was not demonstrated: {path.name}")
        if name == "worker-lock":
            ensure({(case.get("shutdownTimedOut"), case["holderTerminated"]) for case in cases}
                   == {(False, False), (True, False), (True, True)}, f"Missing worker lock lifecycle case: {path.name}")
        else:
            ensure({case["holderTerminated"] for case in cases} == {False, True},
                   f"Missing settlement lock termination case: {path.name}")
    elif name == "release-signer":
        ensure(result.get("sourceJarSha256") == jar_hash and result.get("sourceSbomSha256") == sbom_hash,
               f"Release signer artifact mismatch: {path.name}")
        ensure(all(result.get(field) is True for field in ("testKeyEphemeral", "signatureVerified",
                   "tamperRejected", "privateTestKeyRemoved")), f"Incomplete release signer checks: {path.name}")
    else:
        ensure(result.get("designSourceSha256") == sha(ROOT / "stargenesis-design/src/main/resources/examples/blueprint.json"),
               f"Design signer source mismatch: {path.name}")
        ensure(all(result.get(field) is True for field in ("testKeyEphemeral", "validSignatureVerified",
                   "signatureTamperRejected", "unknownKeyIdRejected", "privateTestKeyRemoved")),
               f"Incomplete design signer checks: {path.name}")
    return result


def main() -> None:
    # Complete every prerequisite check before creating any archive output.
    expected_sources = read_json(WORK / "source-hashes.json")
    ensure(source_hashes() == expected_sources, "Maven source/POM snapshot changed since the batch began")
    snapshot_sha = sha(WORK / "source-hashes.json")
    inputs: dict[str, tuple[Path, bytes]] = {}

    def stage(path: Path, archive_name: str) -> None:
        ensure(path.is_file(), f"Missing evidence: {path}")
        ensure(archive_name not in inputs, f"Duplicate archive path: {archive_name}")
        inputs[archive_name] = (path, path.read_bytes())

    builds, probes, artifacts, reports_by_jdk = {}, {}, {}, {}
    for label in LABELS:
        build_path = WORK / f"build-{label}.json"
        build = read_json(build_path)
        ensure(build.get("passed") is True and build.get("java") == label, f"Missing passed build: {label}")
        ensure(build.get("sourceHashesSha256") == snapshot_sha, f"Build uses a different source snapshot: {label}")
        expected_command = [str(ROOT / "mvnw.cmd"), "-B", "-ntp", "-DforkCount=0",
                            "-Phelstera-bridge,craftengine-bridge,cookery-bridge", "clean", "verify"]
        ensure(build.get("command") == expected_command, f"Not the complete three-profile clean verify: {label}")
        log_path = WORK / f"verify-{label}.log"
        ensure(build.get("logSha256") == sha(log_path), f"Build log hash mismatch: {label}")
        ensure(b"BUILD SUCCESS" in log_path.read_bytes(), f"Missing build success terminal: {label}")
        report_paths = sorted((WORK / label).glob("TEST-*.xml"))
        ensure(len(report_paths) == build.get("reports") and bool(report_paths), f"Saved report count differs: {label}")
        totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        suites = {}
        for path in report_paths:
            suite = ET.parse(path).getroot()
            name = suite.get("name")
            ensure(suite.tag == "testsuite" and name not in suites, f"Malformed/duplicate XML suite: {path.name}")
            counts = {key: int(suite.get(key, "0")) for key in totals}
            ensure(counts["tests"] == len(suite.findall("testcase")), f"XML testcase count mismatch: {path.name}")
            ensure(all(counts[key] == 0 for key in ("failures", "errors", "skipped")), f"Failed/skipped XML suite: {path.name}")
            ensure(not suite.findall(".//failure") and not suite.findall(".//error") and not suite.findall(".//skipped"),
                   f"XML has uncounted test failure/error/skip: {path.name}")
            suites[name] = counts["tests"]
            for key in totals:
                totals[key] += counts[key]
            stage(path, f"{label}/reports/{path.name}")
        ensure(totals == build.get("tests") and totals["tests"] >= 687, f"Build totals differ from all XML: {label}")
        for name, count in NEW_SUITES.items():
            matches = [value for suite_name, value in suites.items() if suite_name.endswith("." + name)]
            ensure(matches == [count], f"Missing/incorrect new suite: {label}/{name}")
        reports_by_jdk[label] = {"suites": len(report_paths), "totals": totals, "newSuites": NEW_SUITES}
        artifact_hashes = build.get("artifacts")
        required_names = {"StarGenesis-4.0.0-SNAPSHOT.jar", "sbom.json", *(
            f"stargenesis-{module}-4.0.0-SNAPSHOT.jar" for module in ("helstera", "craftengine", "cookery"))}
        ensure(type(artifact_hashes) is dict and set(artifact_hashes) == required_names, f"Artifact set mismatch: {label}")
        artifacts[label] = {}
        for name, expected_hash in artifact_hashes.items():
            path = WORK / label / name
            record = describe(path)
            ensure(record["sha256"] == expected_hash, f"Artifact hash mismatch: {label}/{name}")
            artifacts[label][name] = {"localPath": path.relative_to(ROOT).as_posix(), **record,
                                      "copiedIntoArchive": name == "sbom.json"}
        stage(WORK / label / "sbom.json", f"{label}/sbom.json")
        probes[label] = {}
        for name in PROBES:
            probes[label][name] = validate_probe(name, label, artifact_hashes["StarGenesis-4.0.0-SNAPSHOT.jar"], artifact_hashes["sbom.json"])
            for extension in ("json", "log"):
                stage(WORK / f"{name}-{label}.{extension}", f"probes/{name}-{label}.{extension}")
        stage(build_path, build_path.name)
        stage(log_path, log_path.name)
        builds[label] = build

    failure_names = ("focused-initial.log", "focused-retry.log", "focused-diagnostic.log", "focused-before-type-fix.log")
    for name in (*failure_names, "focused-fixed.log", "python-regression.log", "node-regression.log", "boundary.log",
                 "source-hashes.json", "verify_batch.py", "archive_batch.py"):
        stage(WORK / name, name)
    focused = (WORK / "focused-fixed.log").read_text(encoding="utf-8", errors="replace")
    ensure("BUILD SUCCESS" in focused and "Tests run: 47, Failures: 0, Errors: 0, Skipped: 0" in focused
           and "Tests run: 50, Failures: 0, Errors: 0, Skipped: 0" in focused,
           "Focused test pass totals are missing")
    before = (WORK / "focused-before-type-fix.log").read_text(encoding="utf-8", errors="replace")
    ensure('Corruption 7:' in before and '"assetAuthorityId":7' in before and 'nothing was thrown' in before,
           "Missing genuine numeric-authority coercion failure")
    python_log = (WORK / "python-regression.log").read_text(encoding="utf-8", errors="replace")
    ensure(re.search(r"Ran 64 tests in [^\r\n]+\r?\n\r?\nOK(?:\r?\n|$)", python_log) is not None,
           "Python 64-test regression did not pass")
    node_log = (WORK / "node-regression.log").read_text(encoding="utf-8", errors="replace")
    for expected in ("# tests 6", "# pass 6", "# fail 0", "# cancelled 0", "# skipped 0", "# todo 0"):
        ensure(expected in node_log.splitlines(), f"Node regression outcome missing: {expected}")
    boundary_log = (WORK / "boundary.log").read_text(encoding="utf-8", errors="replace")
    ensure(boundary_log.strip().startswith("PASS: public API, simulation, design and discovery"),
           "Module boundary scan did not pass")

    # Preserve exact relevant source bytes. The full Maven input hash snapshot covers all modules.
    source_paths = []
    for path_string in expected_sources:
        path = ROOT / path_string
        if (path.suffix == ".java" and ("/integrations/Settlement" in path_string
                or path.name == "IdempotentSettlementPort.java" or "/paper/settlement/" in path_string)):
            source_paths.append(path)
    source_paths.extend(ROOT / "scripts" / name for name in (
        "test_worker_directory_lock.py", "WorkerDirectoryLockProbe.java", "test_settlement_process_lock.py", "SettlementLockProbe.java",
        "test_release_artifact_signer.py", "ReleaseArtifactSigner.java", "test_signed_design_package_signer.py", "SignedDesignPackageSigner.java"))
    source_paths.append(ROOT / "docs/SETTLEMENT_PROVIDER_CONTRACT.md")
    source_paths.append(ROOT / "docs/PRODUCT_CONTRACT.md")
    source_records = {}
    for path in sorted(source_paths):
        relative = path.relative_to(ROOT).as_posix()
        source_records[relative] = describe(path)
        stage(path, "sources/" + relative + ".txt")
    ensure(source_hashes() == expected_sources, "Source snapshot changed during archive preflight")

    summary = {
        "date": "2026-11-26", "passed": True, "phaseGatePassed": False,
        "repositoryHeadAtArchive": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "scope": "Persist immutable currency/item authority and idempotency-history domains atomically in a schema-v3 saga envelope; reject unbound or changed domains before new external operations.",
        "newJavaTests": sum(NEW_SUITES.values()), "newSuites": NEW_SUITES, "focusedTests": 97,
        "builds": builds, "validatedReports": reports_by_jdk, "probes": probes,
        "pythonRegressionTests": 64, "nodeRegressionTests": 6, "boundaryScanPassed": True,
        "regressionCommands": {
            "cwd": str(ROOT),
            "python": [".work/python-3.12.10/python.exe", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"],
            "node": ["node", "--test", "scripts/test_offline_client_lifecycle_smoke.cjs",
                     "scripts/test_offline_client_radio_lifecycle_smoke.cjs", "scripts/test_offline_client_match_admin_smoke.cjs"],
            "boundary": [".work/python-3.12.10/python.exe", "scripts/check_boundaries.py"]
        },
        "sourceSnapshotScope": "Root/module POMs and every file under each module src; snapshot checked unchanged before/after both builds and again during archive.",
        "sourceHashesSha256": snapshot_sha, "sourceSnapshots": source_records, "artifacts": artifacts,
        "artifactStorage": "Exact JAR binaries remain under .work/settlement-authority/{java21,java25}; this archive records their raw lengths/hashes and copies SBOMs, all XML reports and complete logs. No binary is represented as archived when only its digest is retained.",
        "failureNotes": {
            "focused-initial.log": "PowerShell split the unquoted surefire property into an unknown lifecycle phase; no tests executed in this attempt.",
            "focused-retry.log": "Genuine regression: malformedSchemaBindingTypesAndReviewCountsRejectWithoutRewrite accepted one invalid authority type; original diagnostic had no case label.",
            "focused-diagnostic.log": "Test fixture compilation failed on unqualified Asset symbols; this attempt cannot establish a test pass.",
            "focused-before-type-fix.log": "Labeled corruption 7 reproduced numeric item.assetAuthorityId=7 being accepted by Jackson String coercion despite ALLOW_COERCION_OF_SCALARS being disabled.",
            "focused-fixed.log": "Final focused run passed 47 integration and 50 file-repository tests after explicit schema-v3 JsonNode object/text type validation; final tests include 20 four-position malformed-ID type combinations."
        },
        "persistence": {"boundEnvelopeSchema": 3, "legacyReadableSchemas": [1, 2],
                        "legacyBindingsInferred": False, "legacyUnboundAutomaticAdvanceAllowed": False,
                        "legacyUnboundFreshReviewAllowed": False, "productionEntryEnabled": False},
        "limits": [
            "Provider identities are exact bounded declarations, not authenticated asset ownership or signed receipts.",
            "Provider tests use in-memory operation histories; real files, repository reopen, byte preservation and local process locks are exercised, not real economy/inventory durability.",
            "Before/after descriptor checks do not replace an internal provider operation lease or prove history continuity when an adapter falsely reuses identifiers.",
            "Legacy unbound records remain readable but cannot acquire the currently installed provider's binding implicitly; no migration or production review adapter is enabled.",
            "Signing probes use removed ephemeral keys, not issuer/publisher production keys; these are SNAPSHOT test artifacts, not a formal release.",
            "No new Paper/Folia runtime, real third-party fault audit, human acceptance, production capacity or operations approval evidence; P9 phase gate remains unpassed."
        ]
    }

    # Publish only bytes read during the completed preflight; existing evidence cannot be overwritten.
    payloads = {name: data for name, (_, data) in inputs.items()}
    payloads["artifacts.json"] = json_bytes(artifacts)
    payloads["summary.json"] = json_bytes(summary)
    manifest = {"algorithm": "SHA-256", "rawByteHashes": True, "manifestSelfExcluded": True,
                "files": {name: {"sha256": sha_bytes(data), "bytes": len(data)}
                          for name, data in sorted(payloads.items())}}
    payloads["manifest.json"] = json_bytes(manifest)
    for name, (path, data) in inputs.items():
        ensure(path.read_bytes() == data, f"Evidence changed after preflight: {name}")
    if TARGET.exists():
        existing = {path.relative_to(TARGET).as_posix() for path in TARGET.rglob("*") if path.is_file()}
        ensure(existing.issubset(payloads), "Archive has unexpected preexisting evidence files")
        for name in existing:
            ensure((TARGET / name).read_bytes() == payloads[name], f"Refusing to overwrite different prior evidence: {name}")
    for name, data in payloads.items():
        destination = TARGET / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            with destination.open("xb") as stream:
                stream.write(data)
        ensure(destination.read_bytes() == data, f"Archive copy differs: {name}")
    ensure(source_hashes() == expected_sources, "Source snapshot changed while copying evidence")
    actual_files = {path.relative_to(TARGET).as_posix() for path in TARGET.rglob("*") if path.is_file()}
    ensure(actual_files == set(payloads), "Archived file set differs from manifest payload")
    print(json.dumps({"passed": True, "archive": str(TARGET), "files": len(payloads),
                      "newJavaTests": 35, "builds": reports_by_jdk}, indent=2))


if __name__ == "__main__":
    main()
