"""Compile the pinned, unchanged Cookery API and run actual upstream recipe tests.

No API stubs, native-server compilation or provider redistribution is involved.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


def verify_blob(content, expected):
    actual = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
    if actual != expected:
        raise ValueError('Cookery source differs from pinned Git blob')
    return content


def extract_locked(destination, lock, *, source_dir=None, archive_path=None):
    destination = destination.resolve()
    archive = zipfile.ZipFile(archive_path) if archive_path else None
    try:
        for path, expected in lock['files'].items():
            target = (destination / path).resolve()
            if destination not in target.parents or '\\' in path or ':' in path:
                raise ValueError('Unsafe source path')
            if source_dir:
                source = (source_dir / path).resolve()
                if source_dir.resolve() not in source.parents or source.stat().st_size > 2 * 1024 * 1024:
                    raise ValueError('Unsafe local source')
                content = source.read_bytes()
            else:
                info = archive.getinfo(f"SenLuo_Plugin-{lock['revision']}/{path}")
                if info.file_size > 2 * 1024 * 1024 or (info.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Unsafe source member')
                content = archive.read(info)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(verify_blob(content, expected))
    finally:
        if archive:
            archive.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, help='Previously extracted exact raw source bytes')
    parser.add_argument('--archive', type=Path, help='Exact-commit GitHub ZIP')
    parser.add_argument('--work-dir', type=Path, default=Path('.work/cookery-api'))
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock_path = root / 'scripts/cookery-api-lock.json'
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    archive_path = args.archive
    if not args.source_dir and not archive_path:
        archive_path = work / 'source.zip'
        url = f"https://codeload.github.com/{lock['repository']}/zip/{lock['revision']}"
        with urllib.request.urlopen(url, timeout=60) as source, archive_path.open('wb') as target:
            count = 0
            while chunk := source.read(1024 * 1024):
                count += len(chunk)
                if count > 128 * 1024 * 1024:
                    raise ValueError('Source archive exceeds 128 MiB')
                target.write(chunk)
    # Fresh source directory prevents unlisted local source/class files entering the API.
    with tempfile.TemporaryDirectory(prefix='source-', dir=work) as directory:
        upstream = Path(directory)
        extract_locked(upstream, lock, source_dir=args.source_dir, archive_path=archive_path)
        gradle = [str(upstream / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(upstream / 'gradlew')]
        command = gradle + ['--init-script', str(root / 'scripts/cookery-api.init.gradle'),
                           'sgApiJar', 'sgRecipeTests', 'sgDependencyManifest', '--no-daemon',
                           '--no-configuration-cache', '--no-build-cache', '--max-workers=1', '--console=plain',
                           '-Dorg.gradle.jvmargs=-Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8']
        if args.offline:
            command.append('--offline')
        with (work / 'upstream-api-build.log').open('w', encoding='utf-8') as log:
            subprocess.run(command, cwd=upstream, stdout=log, stderr=subprocess.STDOUT, check=True)
        reports_dir = upstream / 'build/test-results/sgRecipeTests'
        reports = [ET.parse(p).getroot() for p in reports_dir.glob('TEST-*.xml')]
        counts = {key: sum(int(r.attrib.get(key, 0)) for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
        if counts != {'tests': lock['tests'], 'failures': 0, 'errors': 0, 'skipped': 0}:
            raise ValueError(f'Unexpected actual upstream test results: {counts}')
        for report in reports_dir.glob('TEST-*.xml'):
            shutil.copyfile(report, work / report.name)
        artifact = work / 'cookery-api.jar'
        shutil.copyfile(upstream / 'build/stargenesis/cookery-actual-api.jar', artifact)
        shutil.copyfile(upstream / 'build/stargenesis/dependencies.json', work / 'dependencies.json')
        dependencies = json.loads((work / 'dependencies.json').read_text(encoding='utf-8'))
        if any(d['group'] == 'org.spigotmc' for d in dependencies):
            raise ValueError('Native server dependency leaked into API preparation')
        wrapper = root / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        maven = [str(wrapper)] if os.name == 'nt' else ['sh', str(wrapper)]
        with (work / 'install.log').open('w', encoding='utf-8') as log:
            subprocess.run(maven + ['-B', '-ntp', 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
                           '-Dfile=' + str(artifact), '-DgroupId=net.kaleidoscope', '-DartifactId=cookery-api',
                           '-Dversion=' + lock['artifactVersion'], '-Dpackaging=jar', '-DgeneratePom=true'],
                           cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        result = {'revision': lock['revision'], 'lockedFiles': len(lock['files']), 'tests': counts,
                  'artifactVersion': lock['artifactVersion'], 'artifactSha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
                  'sourceLockSha256': hashlib.sha256(lock_path.read_bytes()).hexdigest(),
                  'scope': 'Unchanged upstream main/compat API classes and 28 upstream recipe tests; no runtime or NMS verification'}
        (work / 'preparation.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result))


if __name__ == '__main__':
    main()
