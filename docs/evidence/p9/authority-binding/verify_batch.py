from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

work = Path(__file__).resolve().parent
root = work.parents[1]
python = str(root / '.work/python-3.12.10/python.exe')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_hashes():
    paths = [root / 'pom.xml']
    for module in root.glob('stargenesis-*'):
        if not module.is_dir():
            continue
        paths.extend(module.glob('pom.xml'))
        paths.extend(p for p in (module / 'src').rglob('*') if p.is_file())
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(paths)}

original_sources = source_hashes()
(work / 'source-hashes.json').write_text(json.dumps(original_sources, indent=2) + '\n', encoding='utf-8')
for label, directory in [('java21', 'jdk-21.0.13.0.2+1'), ('java25', 'jdk-25.0.5.0.2+1')]:
    assert source_hashes() == original_sources, 'Source changed before build'
    jdk = root.parent / '.toolchains' / directory
    java = jdk / 'bin/java.exe'
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
    env['MAVEN_OPTS'] = '-Xms16m -Xmx160m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2'
    command = [str(root / 'mvnw.cmd'), '-B', '-ntp', '-DforkCount=0',
               '-Phelstera-bridge,craftengine-bridge,cookery-bridge', 'clean', 'verify']
    with (work / f'verify-{label}.log').open('x', encoding='utf-8') as log:
        subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=1800)
    assert source_hashes() == original_sources, 'Source changed during build'
    reports = sorted(root.glob('stargenesis-*/target/surefire-reports/TEST-*.xml'))
    totals = {key: sum(int(ET.parse(p).getroot().get(key, '0')) for p in reports)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] >= 687 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped')), totals
    for suite, count in [('SettlementAuthorityBindingTest', 10), ('SettlementAuthorityCoordinatorTest', 14),
                         ('SettlementAuthorityRepositoryTest', 11)]:
        matches = [p for p in reports if p.name.endswith('.' + suite + '.xml')]
        assert len(matches) == 1 and int(ET.parse(matches[0]).getroot().get('tests')) == count, (suite, matches)
    artifacts = work / label
    artifacts.mkdir()
    for report in reports:
        shutil.copyfile(report, artifacts / report.name)
    jar = artifacts / 'StarGenesis-4.0.0-SNAPSHOT.jar'
    sbom = artifacts / 'sbom.json'
    shutil.copyfile(root / 'stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar', jar)
    shutil.copyfile(root / 'target/sbom.json', sbom)
    artifact_hashes = {jar.name: sha(jar), sbom.name: sha(sbom)}
    for module in ('helstera', 'craftengine', 'cookery'):
        name = f'stargenesis-{module}-4.0.0-SNAPSHOT.jar'
        shutil.copyfile(root / f'stargenesis-{module}/target' / name, artifacts / name)
        artifact_hashes[name] = sha(artifacts / name)
    result = {'passed': True, 'java': label, 'tests': totals, 'reports': len(reports),
              'artifacts': artifact_hashes, 'command': command, 'mavenOpts': env['MAVEN_OPTS'],
              'logSha256': sha(work / f'verify-{label}.log'),
              'sourceHashesSha256': sha(work / 'source-hashes.json'),
              'scope': 'Full three-profile clean verify using previously prepared exact API dependencies; no real settlement provider or production entry enabled.'}
    (work / f'build-{label}.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(label, totals, flush=True)
    checks = [
        ('worker-lock', [python, 'scripts/test_worker_directory_lock.py', '--java-home', str(jdk), '--plugin-jar', str(jar)]),
        ('settlement-lock', [python, 'scripts/test_settlement_process_lock.py', '--java-home', str(jdk), '--plugin-jar', str(jar)]),
        ('release-signer', [python, 'scripts/test_release_artifact_signer.py', '--java', str(java), '--plugin-jar', str(jar), '--sbom', str(sbom)]),
        ('design-signer', [python, 'scripts/test_signed_design_package_signer.py', '--java-home', str(jdk), '--java', str(java), '--plugin-jar', str(jar), '--design', 'stargenesis-design/src/main/resources/examples/blueprint.json'])]
    for name, command in checks:
        with (work / f'{name}-{label}.log').open('x', encoding='utf-8') as log:
            subprocess.run(command + ['--output', str(work / f'{name}-{label}.json')], cwd=root, env=env,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        print(label, name, 'passed', flush=True)
print('COMPLETE', flush=True)
