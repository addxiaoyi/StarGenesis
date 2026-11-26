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
import tempfile
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
PROFILES = {
    "helstera-bridge": ("stargenesis-helstera", "StarGenesisHelstera"),
    "craftengine-bridge": ("stargenesis-craftengine", "StarGenesisCraftEngine"),
    "cookery-bridge": ("stargenesis-cookery", "StarGenesisCookery"),
}
DEPENDENCY_INPUTS = {
    "helstera": ("prepare_helstera_api.py", "helstera-api-lock.json",
                 "patches/helstera-owned-visuals.patch", "patches/HELSTERA-LICENSE.txt"),
    "craftengine": ("prepare_craftengine_api.py", "craftengine-api-lock.json"),
    "cookery": ("prepare_cookery_api.py", "cookery-api-lock.json", "cookery-api.init.gradle"),
}


def normalized_profiles(profiles: tuple[str, ...] | list[str]) -> tuple[str, ...]:
    if any(profile not in PROFILES for profile in profiles):
        raise ValueError("Unknown optional release profile")
    return tuple(profile for profile in PROFILES if profile in profiles)


def selected_modules(profiles=()) -> tuple[str, ...]:
    return (*MODULES, *(PROFILES[profile][0] for profile in normalized_profiles(profiles)))


def dependencies_for(profiles) -> tuple[str, ...]:
    profiles = normalized_profiles(profiles)
    return tuple(name for name in DEPENDENCY_INPUTS
                 if name + "-bridge" in profiles or (name == "craftengine" and "cookery-bridge" in profiles))


def java_environment(java: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java.parent.parent)
    environment["PATH"] = str(java.parent) + os.pathsep + environment.get("PATH", "")
    return environment
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


def copy_build_inputs(root: Path, destination: Path, profiles=()) -> None:
    modules = selected_modules(profiles)
    destination.mkdir(parents=True)
    for name in ("pom.xml", "mvnw", "mvnw.cmd", ".gitattributes"):
        source = root / name
        if source.is_file():
            shutil.copy2(source, destination / name)
    for name in (".mvn", *modules):
        source = root / name
        if not source.is_dir():
            raise RuntimeError(f"Release source directory is missing: {source}")
        shutil.copytree(source, destination / name, ignore=shutil.ignore_patterns("target", "__pycache__"))
    # Include the build driver and exact preparation inputs in the source tree commitment.
    driver = root / "scripts/build_release_candidate.py"
    if driver.is_file():
        (destination / "scripts").mkdir(exist_ok=True)
        shutil.copy2(driver, destination / "scripts/build_release_candidate.py")
    for dependency in dependencies_for(profiles):
        for name in DEPENDENCY_INPUTS[dependency]:
            source = root / "scripts" / name
            if not source.is_file():
                raise RuntimeError(f"Locked dependency build input is missing: {source}")
            target = destination / "scripts" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def test_totals(root: Path, modules=None) -> dict[str, int]:
    reports = sorted(root.glob("stargenesis-*/target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise RuntimeError("Release candidate build produced no Surefire reports")
    if modules is not None:
        for module in modules:
            own = [report for report in reports if report.relative_to(root).parts[0] == module]
            if not own or sum(int(ET.parse(p).getroot().attrib.get("tests", 0)) for p in own) == 0:
                raise RuntimeError(f"Selected module has no executed tests: {module}")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        attributes = ET.parse(report).getroot().attrib
        for key in totals:
            totals[key] += int(attributes.get(key, 0))
    if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError(f"Release candidate tests did not pass cleanly: {totals}")
    return {"reports": len(reports), **totals}


def run_build(build_root: Path, java: Path, log: Path, fork_count: int, profiles=()) -> list[str]:
    wrapper = build_root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    command += ["-B", "-ntp", f"-DforkCount={fork_count}"]
    profiles = normalized_profiles(profiles)
    if profiles:
        command += ["-P" + ",".join(profiles)]
    command += ["clean", "verify"]
    environment = java_environment(java)
    print("$ " + subprocess.list2cmdline(command), flush=True)
    with log.open("w", encoding="utf-8", newline="\n") as stream:
        output = subprocess.run(command, cwd=build_root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    if output.returncode != 0:
        raise RuntimeError(f"Release candidate clean verify failed with exit code {output.returncode}; see {log}")
    return command


def prepare_dependencies(build_root: Path, java: Path, profiles, inputs: Path | None = None) -> dict:
    required = dependencies_for(profiles)
    if not required:
        return {}
    # Short sibling paths also allow the actual upstream source tree on Windows hosts.
    directory = Path(tempfile.mkdtemp(prefix="rc-deps-", dir=build_root.parent)).resolve()
    if directory.parent != build_root.parent.resolve():
        raise RuntimeError("Dependency build directory escaped isolated workspace")
    result = {}
    for name in required:
        work = directory / name
        work.mkdir()
        script = build_root / "scripts" / DEPENDENCY_INPUTS[name][0]
        lock = build_root / "scripts" / DEPENDENCY_INPUTS[name][1]
        command = [sys.executable, str(script), "--work-dir", str(work)]
        if name == "helstera":
            command += ["--verify-runtime"]
        if inputs is not None:
            cached = (inputs / ("craftengine" if name == "craftengine" else name + ".zip")).resolve()
            if not cached.exists():
                raise RuntimeError(f"Locked dependency input is missing: {cached}")
            command += ["--source-dir" if name == "craftengine" else "--archive", str(cached)]
        print("Preparing actual " + name + " API from locked inputs", flush=True)
        with (work / "preparation-launch.log").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=build_root, env=java_environment(java), stdout=log,
                           stderr=subprocess.STDOUT, check=True)
        preparation = json.loads((work / "preparation.json").read_text(encoding="utf-8"))
        if preparation.get("sourceLockSha256") != sha256(lock):
            raise RuntimeError(f"Dependency preparation does not match source lock: {name}")
        result[name] = {"directory": work, "lock": lock, "preparation": preparation}
    return result


def verified_plugin(path: Path, version: str, module: str) -> None:
    if not path.is_file():
        raise RuntimeError(f"Selected release plugin is missing: {module}")
    with zipfile.ZipFile(path) as archive:
        descriptor = archive.read("plugin.yml").decode("utf-8")
        if f"version: '{version}'" not in descriptor:
            raise RuntimeError(f"Release plugin descriptor version mismatch: {module}")
        classes = [name for name in archive.namelist() if name.endswith(".class")]
        if not classes:
            raise RuntimeError(f"Selected plugin contains no classes: {module}")
        if any(name.startswith(("net/momirealms/", "net/kaleidoscope/", "dev/helstera/")) for name in classes):
            raise RuntimeError(f"Optional provider classes were bundled in {module}")
        if module != "stargenesis-paper":
            prefix = "dev/stargenesis/" + module.removeprefix("stargenesis-") + "/"
            if any(not name.startswith(prefix) for name in classes):
                raise RuntimeError(f"Optional bridge includes classes outside its own package: {module}")


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
    parser.add_argument("--profile", action="append", choices=tuple(PROFILES), default=[],
                        help="Optional bridge to include; repeat to select more than one")
    parser.add_argument("--dependency-inputs", type=Path,
                        help="Optional verified input cache: helstera.zip, cookery.zip, craftengine/ artifacts; APIs are still rebuilt/verified")
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

    profiles = normalized_profiles(args.profile)
    modules = selected_modules(profiles)
    copy_build_inputs(root, build_root, profiles)
    source_version = project_version(build_root / "pom.xml")
    source_tree_hash = tree_sha256(build_root)
    set_reactor_version(build_root / "pom.xml", args.version, True, source_version)
    for module in modules:
        set_reactor_version(build_root / module / "pom.xml", args.version, False, source_version)

    dependencies = prepare_dependencies(build_root, java, profiles,
                                        args.dependency_inputs.resolve() if args.dependency_inputs else None)
    build_log = build_root / "release-verify.log"
    build_command = run_build(build_root, java, build_log, args.fork_count, profiles)
    tests = test_totals(build_root, modules)
    sbom_source = build_root / "target" / "sbom.json"
    jar_source = build_root / "stargenesis-paper" / "target" / f"stargenesis-paper-{args.version}.jar"
    if not sbom_source.is_file() or not jar_source.is_file():
        raise RuntimeError("Release candidate JAR or aggregate SBOM is missing")
    sbom = json.loads(sbom_source.read_text(encoding="utf-8"))
    if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.6":
        raise RuntimeError("Release candidate SBOM is not CycloneDX 1.6")
    plugins = {"stargenesis-paper": (jar_source, "StarGenesis")}
    for profile in profiles:
        module, name = PROFILES[profile]
        plugins[module] = (build_root / module / "target" / f"{module}-{args.version}.jar", name)
    for module, (path, _) in plugins.items():
        verified_plugin(path, args.version, module)

    output.mkdir(parents=True)
    jar_output = output / f"StarGenesis-{args.version}.jar"
    sbom_output = output / "sbom.json"
    log_output = output / "release-verify.log"
    artifacts = {}
    for module, (path, name) in plugins.items():
        copied = output / f"{name}-{args.version}.jar"
        shutil.copy2(path, copied)
        artifacts[module] = {"file": path_label(copied, root), "sizeBytes": copied.stat().st_size, "sha256": sha256(copied)}
    shutil.copy2(sbom_source, sbom_output)
    shutil.copy2(build_log, log_output)
    dependency_evidence = {}
    for name, preparation in dependencies.items():
        folder = output / "dependency-evidence" / name
        folder.mkdir(parents=True)
        files = {}
        for path in sorted(preparation["directory"].iterdir()):
            if path.is_file() and path.suffix in (".json", ".log", ".xml"):
                copied = folder / path.name
                shutil.copy2(path, copied)
                files[path.name] = sha256(copied)
        shutil.copy2(preparation["lock"], folder / "source-lock.json")
        files["source-lock.json"] = sha256(folder / "source-lock.json")
        dependency_evidence[name] = {"preparation": preparation["preparation"],
                                     "directory": path_label(folder, root), "files": files}
    arguments = [Path(value).name if index == 0 or (index == 1 and build_command[0] == "sh") else value
                 for index, value in enumerate(build_command)]
    evidence_document = {
        "version": args.version,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "buildCommand": subprocess.list2cmdline(arguments),
        "buildArguments": arguments,
        "profiles": list(profiles),
        "modules": list(modules),
        "dependencyPreparation": dependency_evidence,
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
        "artifacts": artifacts,
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
