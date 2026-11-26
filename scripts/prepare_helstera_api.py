"""Build the actual pinned upstream API for the optional bridge; never create API stubs.

Only the locked parent/API/license sources are extracted. The API is provided at runtime
by helsteraMobs and is not redistributed inside either StarGenesis plugin JAR.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


def extract_locked(archive_path, destination, lock):
    destination = destination.resolve()
    prefix = f"helsteraMobs-{lock['revision']}/"
    with zipfile.ZipFile(archive_path) as archive:
        for path, expected in lock["files"].items():
            target = (destination / path).resolve()
            if destination not in target.parents:
                raise ValueError("Unsafe source path")
            info = archive.getinfo(prefix + path)
            if info.file_size > 2 * 1024 * 1024 or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Invalid source member: {path}")
            content = archive.read(info)
            actual = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
            if actual != expected:
                raise ValueError(f"Pinned Git blob mismatch: {path}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Optional already downloaded exact-commit ZIP")
    parser.add_argument("--work-dir", type=Path, default=Path(".work/helstera-api"))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    lock_path = repo / "scripts/helstera-api-lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    archive_path = args.archive.resolve() if args.archive else work / "source.zip"
    if not args.archive:
        partial = work / "source.zip.partial"
        url = f"https://codeload.github.com/{lock['repository']}/zip/{lock['revision']}"
        with urllib.request.urlopen(url, timeout=60) as source, partial.open("wb") as target:
            count = 0
            while chunk := source.read(1024 * 1024):
                count += len(chunk)
                if count > 128 * 1024 * 1024:
                    raise ValueError("Source archive exceeds 128 MiB")
                target.write(chunk)
        partial.replace(archive_path)
    # A fresh directory ensures unlisted local sources cannot enter the compiled API.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="source-", dir=work) as directory:
        upstream = Path(directory)
        extract_locked(archive_path, upstream, lock)
        wrapper = repo / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
        api_dir = upstream / "helstera-api"
        # Some upstream tests read module-relative source paths. In-process tests must run here.
        with (work / "upstream-api-build.log").open("w", encoding="utf-8") as log:
            subprocess.run(command + ["-B", "-ntp", "-DforkCount=0",
                           "-Dproject.build.outputTimestamp=2026-11-26T03:57:00Z", "clean", "package"],
                           cwd=api_dir, stdout=log, stderr=subprocess.STDOUT, check=True)
        reports = [ET.parse(path).getroot() for path in (api_dir / "target/surefire-reports").glob("TEST-*.xml")]
        counts = {name: sum(int(report.attrib.get(name, 0)) for report in reports)
                  for name in ("tests", "failures", "errors", "skipped")}
        if counts != {"tests": lock["tests"], "failures": 0, "errors": 0, "skipped": 0}:
            raise ValueError(f"Unexpected upstream test results: {counts}")
        artifact = work / "helstera-api.jar"
        shutil.copyfile(api_dir / f"target/helstera-api-{lock['apiVersion']}.jar", artifact)
        with (work / "install.log").open("w", encoding="utf-8") as log:
            subprocess.run(command + ["-B", "-ntp", "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
                           f"-Dfile={artifact}", "-DgroupId=dev.helstera", "-DartifactId=helstera-api",
                           f"-Dversion={lock['apiVersion']}-{lock['revision']}", "-Dpackaging=jar", "-DgeneratePom=true"],
                           cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
        result = {"revision": lock["revision"], "lockedFiles": len(lock["files"]), "tests": counts,
                  "artifactSha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                  "sourceLockSha256": hashlib.sha256(lock_path.read_bytes()).hexdigest()}
        (work / "preparation.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result))


if __name__ == "__main__":
    main()
