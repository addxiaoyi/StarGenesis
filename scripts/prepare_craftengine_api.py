"""Verify official CraftEngine API/source/POM bytes and install immutable local API coordinates."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import xml.etree.ElementTree as ET


def verified_bytes(content, expected):
    if len(content) != expected['bytes'] or hashlib.sha256(content).hexdigest() != expected['sha256']:
        raise ValueError('CraftEngine artifact differs from locked bytes')
    return content


def artifact_pom(content, module, version):
    namespace = 'http://maven.apache.org/POM/4.0.0'
    ET.register_namespace('', namespace)
    tree = ET.fromstring(content)
    if tree.findtext(f'{{{namespace}}}groupId') != 'net.momirealms' or tree.findtext(f'{{{namespace}}}artifactId') != 'craft-engine-' + module:
        raise ValueError('Unexpected CraftEngine POM identity')
    tree.find(f'{{{namespace}}}version').text = version
    return ET.tostring(tree, encoding='utf-8', xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, help='Optional previously downloaded exact artifacts')
    parser.add_argument('--work-dir', type=Path, default=Path('.work/craftengine-api'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock_path = root / 'scripts/craftengine-api-lock.json'
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    for name, expected in lock['files'].items():
        if Path(name).name != name or expected['bytes'] > 16 * 1024 * 1024:
            raise ValueError('Invalid locked artifact path or size')
        if args.source_dir:
            content = (args.source_dir / name).read_bytes()
        else:
            with urllib.request.urlopen(expected['url'], timeout=60) as response:
                content = response.read(expected['bytes'] + 1)
        (work / name).write_bytes(verified_bytes(content, expected))
    wrapper = root / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    command = [str(wrapper)] if os.name == 'nt' else ['sh', str(wrapper)]
    for module, version in lock['artifactVersions'].items():
        stem = f'craft-engine-{module}-{lock["version"]}'
        pom = work / f'{module}-locked.pom'
        pom.write_bytes(artifact_pom((work / (stem + '.pom')).read_bytes(), module, version))
        with (work / f'{module}-install.log').open('w', encoding='utf-8') as log:
            subprocess.run(command + ['-B', '-ntp', 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
                           '-Dfile=' + str(work / (stem + '.jar')), '-DpomFile=' + str(pom)],
                           cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    result = {'version': lock['version'], 'artifactVersions': lock['artifactVersions'],
              'sourceLockSha256': hashlib.sha256(lock_path.read_bytes()).hexdigest(),
              'files': {name: entry['sha256'] for name, entry in lock['files'].items()},
              'scope': 'Actual published API JARs, sources and POMs; no API stubs or runtime-plugin verification'}
    (work / 'preparation.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
