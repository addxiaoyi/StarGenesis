"""Build an isolated, unsigned non-SNAPSHOT StarGenesis release candidate."""
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
import zipfile
from datetime import datetime, timezone
from pathlib import Path


MODULES = (
    "stargenesis-api",
    "stargenesis-design",
    "stargenesis-sim",
    "stargenesis-integrations",
    "stargenesis-paper",
)
VERSION_PATTERN = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_sha256(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def path_label(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def project_version(pom: Path) -> str:
    project = ET.parse(pom).getroot()
    version_element = project.find("{*}version")
    if version_element is None:
        raise RuntimeError(f"Could not find project version in {pom}")
    return version_element.text or ""


def set_reactor_version(pom: Path, version: str, root_pom: bool, expected_version: str) -> None:
    tree = ET.parse(pom)
    project = tree.getroot()
    version_element = project.find("{*}version") if root_pom else project.find("{*}parent/{*}version")
    if version_element is None or version_element.text != expected_version:
        raise RuntimeError(f"Reactor version in {pom} does not match {expected_version}")
    version_element.text = version
    ET.register_namespace("", "http://maven.apache.org/POM/4.0.0")
    tree.write(pom, encoding="UTF-8", xml_declaration=True)


def copy_build_inputs(root: Path, destination: Path) -> None:
    destination.mkdir(parents=True)
    for name in ("pom.xml", "mvnw", "mvnw.cmd", ".gitattributes"):
        source = root / name
        if source.is_file():
            shutil.copy2(source, destination / name)
    for name in (".mvn", *MODULES):
        source = root / name
        if not source.is_dir():
            raise RuntimeError(f"Release source directory is missing: {source}")
        shutil.copytree(source, destination / name, ignore=shutil.ignore_patterns("target", "__pycache__"))


def test_totals(root: Path) -> dict[str, int]:
    reports = sorted(root.glob("stargenesis-*/target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise RuntimeError("Release candidate build produced no Surefire reports")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        attributes = ET.parse(report).getroot().attrib
        for key in totals:
            totals[key] += int(attributes.get(key, 0))
    if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError(f"Release candidate tests did not pass cleanly: {totals}")
    return {"reports": len(reports), **totals}


def run_build(build_root: Path, java: Path, log: Path, fork_count: int) -> list[str]:
    wrapper = build_root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper), "-B", "-ntp", f"-DforkCount={fork_count}", "clean", "verify"]
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java.parent.parent)
    environment["PATH"] = str(java.parent) + os.pathsep + environment.get("PATH", "")
    print("$ " + subprocess.list2cmdline(command), flush=True)
    with log.open("w", encoding="utf-8", newline="\n") as stream:
        output = subprocess.run(command, cwd=build_root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    if output.returncode != 0:
        raise RuntimeError(f"Release candidate clean verify failed with exit code {output.returncode}; see {log}")
    return command


def main() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="4.0.0-rc.1", help="Non-SNAPSHOT semantic version")
    parser.add_argument("--java", default="java", help="Java 21 executable or path")
    parser.add_argument("--output-directory", type=Path,
                        help="Output directory, defaulting to .work/releases/<version>")
    parser.add_argument("--evidence-file", type=Path,
                        help="New evidence JSON, defaulting to docs/evidence/p11/release-candidate-<version>.json")
    parser.add_argument("--fork-count", type=int, choices=(0, 1), default=1,
                        help="Surefire JVM forks: 1 normally, 0 for memory-constrained verification; tests always run")
    args = parser.parse_args()

    if not VERSION_PATTERN.fullmatch(args.version) or "SNAPSHOT" in args.version.upper():
        parser.error("--version must be a non-SNAPSHOT semantic version")
    java_value = shutil.which(args.java) if not Path(args.java).is_absolute() else str(Path(args.java).resolve())
    if java_value is None:
        parser.error(f"Java executable not found: {args.java}")
    java = Path(java_value).resolve()
    root = Path(__file__).resolve().parents[1]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    build_root = root / ".work" / f"release-build-{args.version}-{stamp}"
    output = args.output_directory or Path(".work") / "releases" / args.version
    output = (root / output).resolve() if not output.is_absolute() else output.resolve()
    evidence = args.evidence_file or Path("docs/evidence/p11") / f"release-candidate-{args.version}.json"
    evidence = (root / evidence).resolve() if not evidence.is_absolute() else evidence.resolve()
    if output.exists():
        parser.error(f"Release output directory already exists: {output}")
    if build_root.exists():
        parser.error(f"Release build directory already exists: {build_root}")
    if evidence.exists():
        parser.error(f"Release evidence already exists: {evidence}")

    copy_build_inputs(root, build_root)
    source_version = project_version(build_root / "pom.xml")
    source_tree_hash = tree_sha256(build_root)
    set_reactor_version(build_root / "pom.xml", args.version, True, source_version)
    for module in MODULES:
        set_reactor_version(build_root / module / "pom.xml", args.version, False, source_version)

    build_log = build_root / "release-verify.log"
    build_command = run_build(build_root, java, build_log, args.fork_count)
    tests = test_totals(build_root)
    sbom_source = build_root / "target" / "sbom.json"
    jar_source = build_root / "stargenesis-paper" / "target" / f"stargenesis-paper-{args.version}.jar"
    if not sbom_source.is_file() or not jar_source.is_file():
        raise RuntimeError("Release candidate JAR or aggregate SBOM is missing")
    sbom = json.loads(sbom_source.read_text(encoding="utf-8"))
    if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.6":
        raise RuntimeError("Release candidate SBOM is not CycloneDX 1.6")
    with zipfile.ZipFile(jar_source) as archive:
        descriptor = archive.read("plugin.yml").decode("utf-8")
    if f"version: '{args.version}'" not in descriptor:
        raise RuntimeError("Release candidate plugin descriptor version does not match the reactor")

    output.mkdir(parents=True)
    jar_output = output / f"StarGenesis-{args.version}.jar"
    sbom_output = output / "sbom.json"
    log_output = output / "release-verify.log"
    shutil.copy2(jar_source, jar_output)
    shutil.copy2(sbom_source, sbom_output)
    shutil.copy2(build_log, log_output)
    evidence_document = {
        "version": args.version,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "buildCommand": subprocess.list2cmdline([Path(build_command[0]).name, *build_command[1:]]),
        "buildArguments": [Path(build_command[0]).name, *build_command[1:]],
        "forkCount": args.fork_count,
        "javaVersion": subprocess.run([str(java), "-Xmx32m", "-XX:+UseSerialGC", "-version"],
                                      check=True, capture_output=True, text=True, timeout=30).stderr.strip(),
        "mavenOpts": os.environ.get("MAVEN_OPTS", ""),
        "isolatedSourceCopy": True,
        "sourceVersion": source_version,
        "sourceTreeSha256": source_tree_hash,
        "tests": tests,
        "artifact": {
            "file": path_label(jar_output, root),
            "sizeBytes": jar_output.stat().st_size,
            "sha256": sha256(jar_output),
        },
        "sbom": {
            "format": sbom["bomFormat"],
            "specVersion": sbom["specVersion"],
            "components": len(sbom.get("components", [])),
            "file": path_label(sbom_output, root),
            "sha256": sha256(sbom_output),
        },
        "signed": False,
        "scope": "Unsigned release-candidate build from an isolated source copy. This is not a signed or production release.",
        "buildLog": path_label(log_output, root),
        "buildLogSha256": sha256(log_output),
    }
    evidence.parent.mkdir(parents=True, exist_ok=True)
    with evidence.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(evidence_document, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(evidence_document, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"Release candidate build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
