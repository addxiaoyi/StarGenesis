from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

WORK = Path(__file__).resolve().parent
ROOT = WORK.parents[1]
ARCHIVE = ROOT / "docs/evidence/p5/robustness"
PYTHON = ROOT / ".work/python-3.12.10/python.exe"
JDK = ROOT.parent / ".toolchains/jdk-21.0.13.0.2+1"
JAR = ROOT / ".work/settlement-capability/java21/StarGenesis-4.0.0-SNAPSHOT.jar"
EXPECTED_JAR = "63de78696d735ba45fa88f05f6a72b4c57317950c07876098878731ec73528c2"


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")


def copy(source, relative):
    source, target = Path(source), ARCHIVE / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        assert target.read_bytes() == source.read_bytes(), relative
    else:
        shutil.copyfile(source, target)
    return target


def execute(command, log, expected=0):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("x", encoding="utf-8", newline="\n") as stream:
        process = subprocess.run([str(value) for value in command], cwd=ROOT,
                                 stdout=stream, stderr=subprocess.STDOUT, shell=False)
    assert process.returncode == expected, (process.returncode, str(log))
    return {"command": [str(value) for value in command], "cwd": str(ROOT),
            "exitCode": process.returncode, "logSha256": digest(log)}


def smoke():
    assert digest(JAR) == EXPECTED_JAR
    artifact = copy(JAR, "artifacts/StarGenesis-4.0.0-SNAPSHOT.jar")
    target = WORK / "wrapper-smoke/java21.json"
    command = [PYTHON, ROOT / "scripts/run_ecology_robustness_study.py", "--java", JDK / "bin/java.exe",
               "--plugin-jar", artifact, "--expected-jar-sha256", EXPECTED_JAR,
               "--output", target, "--baseline-seeds", "1", "--paired-seeds", "1"]
    results = {"fresh": execute(command, WORK / "wrapper-smoke/fresh.log")}
    original_report, original_plan = target.read_bytes(), target.with_name(target.name + ".plan.json").read_bytes()
    results["completedResume"] = execute([*command, "--resume"], WORK / "wrapper-smoke/resume.log")
    assert target.read_bytes() == original_report
    assert target.with_name(target.name + ".plan.json").read_bytes() == original_plan
    primary = json.loads((WORK / "java21.json").read_text())
    selected = {(case["profile"], case["seed"]): case for case in primary["cases"]}
    report = json.loads(target.read_text())
    assert report["complete"] and len(report["cases"]) == 18
    assert all(selected[(case["profile"], case["seed"])] == case for case in report["cases"])
    results.update({"passed": True, "casesReexecuted": 18, "allCasesEqualHistoricalPrimary": True,
                    "completedResumeFilesUnchanged": True,
                    "scope": "Separate post-study wrapper smoke. Reuses 18 original cases; adds no independent seeds. Historical reports were not created by this wrapper."})
    save_json(WORK / "wrapper-smoke/result.json", results)

    # Reproduce the default-group counterexample with the exact archived worker and JAR.
    # Reflection selects one existing private worker case, without changing its physics or plan.
    helper = WORK / "counterexample/ReplayCounterexample.java"
    helper.parent.mkdir(parents=True, exist_ok=True)
    with helper.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write('''import java.util.Map;
import dev.stargenesis.internal.jackson.databind.ObjectMapper;
class ReplayCounterexample {
  public static void main(String[] args) throws Exception {
    Class<?> worker = Class.forName("EcologyRobustnessStudy");
    var defaults = worker.getDeclaredMethod("defaults"); defaults.setAccessible(true);
    var run = worker.getDeclaredMethod("run", String.class, long.class, String.class, Map.class);
    run.setAccessible(true);
    Object result = run.invoke(null, "ecology-v2-robustness-9", 8108471355311695579L, "default", defaults.invoke(null));
    System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }
}
''')
    classes = helper.parent / "classes"
    classes.mkdir()
    compiler = execute([JDK / "bin/javac.exe", "-J-Xmx96m", "-cp", artifact, "-d", classes,
                        ROOT / "scripts/EcologyRobustnessStudy.java", helper], helper.parent / "compile.log")
    command = [JDK / "bin/java.exe", "-Xms16m", "-Xmx96m", "-XX:+UseSerialGC", "-cp",
               str(artifact) + ";" + str(classes), "ReplayCounterexample"]
    invocation = execute(command, helper.parent / "result.json")
    repeated = json.loads((helper.parent / "result.json").read_text())
    historical = selected[("default", 8108471355311695579)]
    assert repeated == historical
    assert repeated["firstSpeciesExtinction"] == {"decomposer": 1360}
    save_json(helper.parent / "verification.json", {"passed": True, "compile": compiler, "run": invocation,
              "workerSourceSha256": digest(ROOT / "scripts/EcologyRobustnessStudy.java"),
              "helperSha256": digest(helper), "jarSha256": digest(artifact),
              "completeCaseEqualHistoricalPrimary": True,
              "scope": "Post-study exact default counterexample replay; not an additional independent seed or a rerun of all historical cases."})
    print("Wrapper smoke and default counterexample replay passed", flush=True)


def archive():
    assert digest(JAR) == EXPECTED_JAR
    artifact = copy(JAR, "artifacts/StarGenesis-4.0.0-SNAPSHOT.jar")
    for name in ("java21.json", "java21.json.plan.json", "java21.log", "java25.json", "java25.json.plan.json",
                 "java25.log", "command-java21-low-memory.json", "command-java21.json", "command-java25.json",
                 "process.json", "run_studies.py", "launcher.log", "launcher-error.log", "launcher-resumed.log",
                 "launcher-resumed-error.log", "launcher-after-tests.log", "launcher-after-tests-error.log"):
        copy(WORK / name, "historical/" + name)
    for directory in ("wrapper-smoke", "counterexample"):
        for source in sorted((WORK / directory).iterdir()):
            if source.is_file():
                copy(source, "verification/" + directory + "/" + source.name)
    for path, relative in (
        ("docs/evidence/p5/dynamic-lifecycle/summary.json", "provenance/prior-dynamic-summary.json"),
        ("docs/evidence/p9/provider-capability/summary.json", "provenance/prior-provider-summary.json"),
        ("docs/evidence/p9/provider-capability/build-java21.json", "provenance/prior-build-java21.json"),
        ("docs/evidence/p9/provider-capability/ci/summary.json", "provenance/prior-ci-summary.json"),
        ("docs/evidence/p9/provider-capability/ci/ci-result.json", "provenance/prior-ci-result.json"),
        ("docs/evidence/p5/lifecycle-v2/ecology-v2-validation.json", "provenance/prior-32-seed-validation.json"),
        (".work/ecology-robustness/ci-jobs.json", "provenance/f4a21da-ci-jobs.json"),
        (".work/ecology-robustness/ci-java21.log", "provenance/f4a21da-ci-java21.log"),
        (".work/ecology-robustness/ci-java25.log", "provenance/f4a21da-ci-java25.log"),
        (".work/ecology-robustness/sync-result.json", "provenance/f4a21da-sync-result.json"),
    ):
        copy(ROOT / path, relative)
    for name in ("EcologyRobustnessStudy.java", "verify_ecology_robustness_study.py", "test_verify_ecology_robustness_study.py",
                 "run_ecology_robustness_study.py", "test_run_ecology_robustness_study.py"):
        copy(ROOT / "scripts" / name, "sources/" + name + ".txt")

    source_identity = {}
    for source in sorted((ROOT / "stargenesis-sim/src/main/java").rglob("*.java")):
        path = source.relative_to(ROOT).as_posix()
        committed = subprocess.check_output(["git", "show", "f4a21da:" + path], cwd=ROOT)
        assert source.read_bytes() == committed, path
        source_identity[path] = digest(source)
        copy(source, "sources/" + path + ".txt")
    current_worker = digest(ROOT / "scripts/EcologyRobustnessStudy.java")
    prior_dynamic = json.loads((ROOT / "docs/evidence/p5/dynamic-lifecycle/summary.json").read_text())
    assert current_worker == prior_dynamic["sourceHashes"]["scripts/EcologyRobustnessStudy.java"]
    assert (ROOT / "scripts/EcologyRobustnessStudy.java").read_bytes() == subprocess.check_output(
        ["git", "show", "f4a21da:scripts/EcologyRobustnessStudy.java"], cwd=ROOT)

    with zipfile.ZipFile(artifact) as jar:
        entries = {entry.filename: {"bytes": entry.file_size, "sha256": hashlib.sha256(jar.read(entry)).hexdigest()}
                   for entry in jar.infolist() if not entry.is_dir()}
    save_json(ARCHIVE / "provenance/artifact-entries.json", {"jarSha256": EXPECTED_JAR, "entries": entries})
    save_json(ARCHIVE / "provenance/source-identity.json", {
        "comparisonCommit": "f4a21da2d5220a36dd9ae4301322b8a9403c5d24",
        "simulationSourcesEqualCommittedBytes": True, "sourceHashes": source_identity,
        "workerSourceSha256": current_worker, "workerEqualsPriorRecordedHash": True,
        "workerEqualsCommittedBytes": True,
        "historicalLauncherSha256": digest(WORK / "run_studies.py"),
        "limits": ["Historical launcher checked the JAR hash only at launcher startup and did not persist source/JDK hashes for every segment.",
                   "Current worker source matches the earlier dynamic-lifecycle hash and committed source, but no authenticated per-segment source lease existed.",
                   "Exact archived JAR and source snapshots permit repetition; these hashes do not independently attest source-to-binary build provenance.",
                   "No runtime.json files are retroactively assigned to the historical 608/72-case reports."]})

    commands = {}
    for pattern in ("test_run_ecology_robustness_study.py", "test_verify_ecology_robustness_study.py"):
        commands[pattern] = execute([PYTHON, "-m", "unittest", "discover", "-s", "scripts", "-p", pattern],
                                    ARCHIVE / "verification" / (pattern + ".log"))
    commands["independentValidator"] = execute([
        PYTHON, ROOT / "scripts/verify_ecology_robustness_study.py",
        "--primary", ARCHIVE / "historical/java21.json", "--primary-plan", ARCHIVE / "historical/java21.json.plan.json",
        "--comparison", ARCHIVE / "historical/java25.json", "--comparison-plan", ARCHIVE / "historical/java25.json.plan.json",
        "--prior-validation", ARCHIVE / "provenance/prior-32-seed-validation.json", "--output", ARCHIVE / "validation.json"],
        ARCHIVE / "verification/independent-validator.log")
    for label, jdk_name in (("java21", "jdk-21.0.13.0.2+1"), ("java25", "jdk-25.0.5.0.2+1")):
        executable = ROOT.parent / ".toolchains" / jdk_name / "bin/java.exe"
        commands[label + "VersionObservedAtArchive"] = execute([executable, "-version"], ARCHIVE / "provenance" / (label + "-version.log"))
        commands[label + "VersionObservedAtArchive"]["javaExecutableSha256AtArchive"] = digest(executable)
    validation = json.loads((ARCHIVE / "validation.json").read_text())
    smoke_result = json.loads((ARCHIVE / "verification/wrapper-smoke/result.json").read_text())
    assert smoke_result["passed"] and validation["passed"]
    primary = json.loads((ARCHIVE / "historical/java21.json").read_text())
    total_extinctions = sum(bool(case["firstSpeciesExtinction"]) for case in primary["cases"])
    summary = {"date": "2026-11-26", "passed": True, "scope": "Completed fixed finite pure-simulation study and evidence validation; stage gate remains unmet.",
               "historicalJarSha256": EXPECTED_JAR, "historicalJarBytes": artifact.stat().st_size,
               "historicalWorkerSourceSha256": current_worker, "historicalLauncher": "historical/run_studies.py",
               "historicalLauncherWasNewWrapper": False,
               "primaryCases": validation["primaryCases"], "heldOutIndependentSeeds": validation["heldOutIndependentSeeds"],
               "crossJdkRepeatedCases": validation["crossJdkRepeatedCases"], "crossJdkAddsIndependentSeeds": False,
               "defaultThreeSpeciesAt1536": validation["profiles"]["default"]["threeSpeciesAt1536"],
               "defaultCases": validation["profiles"]["default"]["cases"],
               "totalExtinctionCasesAcrossPairedProfiles": total_extinctions,
               "allCasesReplayVerified": all(case["replayVerified"] for case in primary["cases"]),
               "newWrapperUnitTests": 17, "independentVerifierUnitTests": 18,
               "wrapperSmokeRepeatedCases": 18, "defaultCounterexampleExactReplayPassed": True,
               "commands": commands,
               "limits": ["Original Java21 study used checkpoint resumes at 6, 23 and 96 cases; historical command snapshots include low-memory and normal JIT settings. JIT settings are not performance evidence.",
                          "Historical launcher's once-per-launch JAR check and retrospective source equality are weaker than the new wrapper's per-segment checks. No historical runtime manifest is invented.",
                          "The wrapper hashes the Java executable, worker and JAR before/after each segment and serializes cooperating wrappers. It does not hash the entire JDK, prevent transient input changes, authenticate files or lock direct Java invocations.",
                          "A crashed wrapper may leave a run lock. Verify the owning process has ended and inspect saved plan/report/runtime before explicitly removing that lock; never remove an active owner's lock.",
                          "Independent Python validation checks saved evidence, not every physics step. New wrapper smoke and one default counterexample were reexecuted; other historical cases were not all rerun during archiving.",
                          "Default survives in 63/64 new seeds; extinction is retained. Perturbations share 32 baseline seeds, so 608 cases are not 608 independent seeds.",
                          "Finite 1536-step pure-simulation data do not prove indefinite balance, arbitrary seeds/parameters, live-server behavior, human experience, production capacity or official release acceptance."]}
    save_json(ARCHIVE / "summary.json", summary)
    copy(Path(__file__), "verification/finalize_robustness.py")
    files = {path.relative_to(ARCHIVE).as_posix(): {"bytes": path.stat().st_size, "sha256": digest(path)}
             for path in sorted(ARCHIVE.rglob("*")) if path.is_file()}
    save_json(ARCHIVE / "manifest.json", {"algorithm": "SHA-256", "scope": "All archive files except this manifest itself; raw file bytes.", "files": files})
    assert all(digest(ARCHIVE / name) == row["sha256"] for name, row in files.items())
    print(json.dumps({"passed": True, "files": len(files), "primaryCases": 608, "crossJdkCases": 72,
                      "defaultSurvives": summary["defaultThreeSpeciesAt1536"], "extinctionCases": total_extinctions}), flush=True)


def audit():
    original_manifest = json.loads((ARCHIVE / "manifest.json").read_text())
    for name, row in original_manifest["files"].items():
        assert digest(ARCHIVE / name) == row["sha256"], name
    coverage = {}
    expression = re.compile(r"completed=(\d+)/(\d+) profile=(\S+) seed=(-?\d+) population=(\{.*?\}) extinction=(\{.*?\})")
    def parse_map(text):
        if text == "{}":
            return {}
        return {name: int(number) for name, number in (item.split("=") for item in text[1:-1].split(", "))}
    for label, expected in (("java21", 608), ("java25", 72)):
        report = json.loads((ARCHIVE / "historical" / (label + ".json")).read_text())
        rows = []
        resumes = []
        for line in (ARCHIVE / "historical" / (label + ".log")).read_text().splitlines():
            if line.startswith("resumed="):
                checkpoint = re.fullmatch(r"resumed=(\d+)/(\d+) lastCheckpointReexecuted=true", line)
                assert checkpoint and int(checkpoint[1]) == len(rows) and int(checkpoint[2]) == expected
                resumes.append(int(checkpoint[1]))
                continue
            match = expression.fullmatch(line)
            assert match, (label, line)
            index, total, profile, seed, populations, extinction = match.groups()
            assert int(index) == len(rows) + 1 and int(total) == expected
            case = report["cases"][len(rows)]
            assert (profile, int(seed)) == (case["profile"], case["seed"])
            assert parse_map(populations) == case["finalState"]["populationBySpecies"]
            assert parse_map(extinction) == case["firstSpeciesExtinction"]
            rows.append(int(index))
        assert len(rows) == expected
        coverage[label] = {"completionLogEntries": len(rows), "expectedCases": expected,
                           "logMatchesEveryCaseProfileSeedFinalPopulationAndExtinction": True,
                           "resumedCheckpoints": resumes}
    save_json(ARCHIVE / "verification/log-report-consistency.json", {"passed": True, "reports": coverage,
              "limits": "Compares archived text logs to archived reports; does not authenticate either file or rerun all physics."})
    copy(ROOT / "docs/ECOLOGY_ROBUSTNESS_STUDY.md", "sources/ECOLOGY_ROBUSTNESS_STUDY.md.txt")
    summary_path = ARCHIVE / "summary.json"
    summary = json.loads(summary_path.read_text())
    summary["rawCompletionLogEntriesChecked"] = {label: row["completionLogEntries"] for label, row in coverage.items()}
    summary["limits"].append("Only saved historical command snapshots survive; there is no complete immutable per-segment invocation record. Java versions/executable hashes captured during archiving describe the current installed launchers, not a historical attestation.")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    shutil.copyfile(Path(__file__), ARCHIVE / "verification/finalize_robustness.py")
    files = {path.relative_to(ARCHIVE).as_posix(): {"bytes": path.stat().st_size, "sha256": digest(path)}
             for path in sorted(ARCHIVE.rglob("*")) if path.is_file() and path.name != "manifest.json"}
    (ARCHIVE / "manifest.json").write_text(json.dumps({"algorithm": "SHA-256",
        "scope": "All archive files except this manifest itself; raw file bytes.", "files": files}, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    assert all(digest(ARCHIVE / name) == row["sha256"] for name, row in files.items())
    print(json.dumps({"passed": True, "files": len(files), "completionLogEntries": coverage}), flush=True)


if __name__ == "__main__":
    {"smoke": smoke, "archive": archive, "audit": audit}[sys.argv[1]]()
