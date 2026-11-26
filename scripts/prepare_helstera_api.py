"""Build the actual pinned upstream API for the optional bridge; never create API stubs.

Only the locked parent/API/license sources and optional runtime-test inputs are extracted.
The API is provided at runtime by helsteraMobs and is not redistributed inside either
StarGenesis plugin JAR.
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


def verified_patch(lock):
    scripts = Path(__file__).resolve().parent
    patch = (scripts / lock['patch']['file']).resolve()
    if scripts not in patch.parents or hashlib.sha256(patch.read_bytes()).hexdigest() != lock['patch']['sha256']:
        raise ValueError('Helstera lifecycle patch hash/path mismatch')
    return patch


def apply_locked_patch(destination, lock, *, api_only=False):
    patch = verified_patch(lock)
    # An isolated repository prevents Git from resolving paths against an enclosing checkout.
    subprocess.run(['git', 'init', '--quiet', str(destination)], check=True)
    command = ['git', '-C', str(destination), 'apply']
    if api_only:
        command += ['--include=helstera-api/*']
    subprocess.run(command + ['--check', str(patch)], check=True)
    subprocess.run(command + [str(patch)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Optional already downloaded exact-commit ZIP")
    parser.add_argument("--work-dir", type=Path, default=Path(".work/helstera-api"))
    parser.add_argument("--verify-runtime", action="store_true", help="Also test the actual patched core/runtime/renderer modules")
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
        source_lock = dict(lock)
        if args.verify_runtime:
            source_lock['files'] = {**lock['files'], **lock['runtimeFiles']}
        extract_locked(archive_path, upstream, source_lock)
        apply_locked_patch(upstream, lock, api_only=not args.verify_runtime)
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
                           f"-Dversion={lock['artifactVersion']}", "-Dpackaging=jar", "-DgeneratePom=true"],
                           cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
        result = {"revision": lock["revision"], "patchSha256": lock['patch']['sha256'],
                  "artifactVersion": lock['artifactVersion'], "lockedFiles": len(lock["files"]), "tests": counts,
                  "artifactSha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                  "sourceLockSha256": hashlib.sha256(lock_path.read_bytes()).hexdigest()}
        if args.verify_runtime:
            with (work / 'runtime-parent-install.log').open('w', encoding='utf-8') as log:
                subprocess.run(command + ['-B', '-ntp', '-N', '-Dspotbugs.skip=true', 'install'], cwd=upstream,
                               stdout=log, stderr=subprocess.STDOUT, check=True)
            with (work / 'runtime-api-install.log').open('w', encoding='utf-8') as log:
                subprocess.run(command + ['-B', '-ntp', '-DskipTests', '-Dspotbugs.skip=true', 'install'], cwd=api_dir,
                               stdout=log, stderr=subprocess.STDOUT, check=True)
            result['runtimeTests'] = {}
            for module, expected in lock['runtimeTests'].items():
                module_dir = upstream / module
                with (work / f'{module}-build.log').open('w', encoding='utf-8') as log:
                    subprocess.run(command + ['-B', '-ntp', '-DforkCount=0', '-Dspotbugs.skip=true', 'clean', 'install'],
                                   cwd=module_dir, stdout=log, stderr=subprocess.STDOUT, check=True)
                reports = [ET.parse(p).getroot() for p in (module_dir/'target/surefire-reports').glob('TEST-*.xml')]
                observed = {key: sum(int(r.attrib.get(key, 0)) for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
                if observed != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}:
                    raise ValueError(f'Unexpected {module} tests: {observed}')
                result['runtimeTests'][module] = observed
        (work / "preparation.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result))


if __name__ == "__main__":
    main()
