"""Reproduce the audited upstream instance-retention behavior using its actual runtime JAR."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parent))
from server_smoke import sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'provider-jar', 'server-jar', 'libraries-from', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    suffix = '.exe' if os.name == 'nt' else ''
    provider = args.provider_jar.resolve()
    source = Path(__file__).with_name('HelsteraSpawnFailureProbe.java').resolve()
    dependencies = [provider, args.server_jar.resolve(), *sorted((args.libraries_from.resolve() / 'libraries').rglob('*.jar'))]
    with tempfile.TemporaryDirectory(prefix='helstera-spawn-probe-') as directory:
        classpath = os.pathsep.join(str(path) for path in dependencies)
        subprocess.run([str(args.java_home / 'bin' / ('javac' + suffix)), '-J-Xmx96m', '--release', '21',
                        '-cp', classpath, '-d', directory, str(source)], check=True)
        result = subprocess.run([str(args.java_home / 'bin' / ('java' + suffix)), '-Xmx80m', '-XX:+UseSerialGC',
                                 '-cp', directory + os.pathsep + classpath, 'HelsteraSpawnFailureProbe'],
                                check=True, capture_output=True, text=True)
    observed = json.loads(result.stdout)
    observed.update(providerSha256=sha256(provider), sourceSha256=sha256(source),
                    serverSha256=sha256(args.server_jar),
                    librarySha256={str(path.relative_to(args.libraries_from.resolve())): sha256(path)
                                   for path in dependencies[2:]})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(observed, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(observed))


if __name__ == '__main__':
    main()
