from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

work = Path(__file__).resolve().parent
root = work.parents[1]
python = root / '.work/python-3.12.10/python.exe'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def sources():
    paths = [root / 'pom.xml', root / '.github/workflows/verify.yml']
    paths.extend(p for p in (root / 'scripts').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    for module in root.glob('stargenesis-*'):
        if module.is_dir():
            paths.extend(module.glob('pom.xml'))
            paths.extend(p for p in (module / 'src').rglob('*') if p.is_file())
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(paths)}

snapshot = sources()
(work / 'source-hashes.json').write_text(json.dumps(snapshot, indent=2) + '\n', encoding='utf-8')

def run(command, log, env, timeout=1800):
    with log.open('x', encoding='utf-8') as output:
        subprocess.run([str(part) for part in command], cwd=root, env=env,
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=timeout)

for label, directory in [('java21', 'jdk-21.0.13.0.2+1'), ('java25', 'jdk-25.0.5.0.2+1')]:
    assert sources() == snapshot, 'Sources changed before build'
    jdk = root.parent / '.toolchains' / directory
    java = jdk / 'bin/java.exe'
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
    env['MAVEN_OPTS'] = '-Xms16m -Xmx128m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=96m -XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2 -Djdk.nio.maxCachedBufferSize=262144'
    env['JAVA_TOOL_OPTIONS'] = '-Xms8m -Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=16m -XX:MaxMetaspaceSize=80m -XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2'
    if label == 'java21' and not (work / 'focused-java21-retry.log').exists():
        command = [root / 'mvnw.cmd', '-B', '-ntp', '-DforkCount=0',
                   '-Dtest=Radio*Test,Contract*Test,VirtualMessageBus*Test',
                   '-Dsurefire.failIfNoSpecifiedTests=false', '-pl', 'stargenesis-paper', '-am', 'test']
        run(command, work / 'focused-java21-retry.log', env)
        print('Focused radio/contract regression passed', flush=True)
    command = [root / 'mvnw.cmd', '-B', '-ntp', '-DforkCount=0',
               '-Phelstera-bridge,craftengine-bridge,cookery-bridge', 'clean', 'verify']
    print(label, 'full verify started', flush=True)
    run(command, work / f'verify-{label}.log', env)
    assert sources() == snapshot, 'Sources changed during build'
    reports = sorted(root.glob('stargenesis-*/target/surefire-reports/TEST-*.xml'))
    totals = {key: sum(int(ET.parse(p).getroot().get(key, '0')) for p in reports)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] >= 720 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped')), totals
    for suite, count in [('RadioJournalRotationTest', 17), ('RadioJournalPersistenceTest', 16)]:
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
              'artifacts': artifact_hashes, 'command': [str(x) for x in command], 'mavenOpts': env['MAVEN_OPTS'],
              'logSha256': sha(work / f'verify-{label}.log'),
              'sourceHashesSha256': sha(work / 'source-hashes.json'),
              'scope': 'Full three-profile clean verify; finite persistence regression, no new real-server or human load acceptance.'}
    (work / f'build-{label}.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(label, totals, flush=True)
    checks = [
        ('radio-journal-lock', [python, 'scripts/test_radio_journal_process_lock.py', '--java-home', jdk, '--plugin-jar', jar]),
        ('worker-lock', [python, 'scripts/test_worker_directory_lock.py', '--java-home', jdk, '--plugin-jar', jar]),
        ('settlement-lock', [python, 'scripts/test_settlement_process_lock.py', '--java-home', jdk, '--plugin-jar', jar]),
        ('release-signer', [python, 'scripts/test_release_artifact_signer.py', '--java', java, '--plugin-jar', jar, '--sbom', sbom]),
        ('design-signer', [python, 'scripts/test_signed_design_package_signer.py', '--java-home', jdk, '--java', java, '--plugin-jar', jar,
                           '--design', 'stargenesis-design/src/main/resources/examples/blueprint.json'])]
    for name, command in checks:
        run(command + ['--output', work / f'{name}-{label}.json'], work / f'{name}-{label}.log', env, 240)
        print(label, name, 'passed', flush=True)
print('COMPLETE', flush=True)
