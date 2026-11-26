"""Exercise catalog or passive projection against an actual fixed Helstera build on isolated Paper.
Requires a fresh directory containing an already accepted eula.txt. Lab mode uses offline clients;
none of these modes establish resource-pack appearance or human acceptance.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading
import time
import zipfile
import hashlib
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from server_smoke import sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'server-jar', 'provider-jar', 'plugin-jar', 'bridge-jar', 'libraries-from', 'directory', 'texture'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, default=25683)
    parser.add_argument('--probe', choices=('catalog', 'projection', 'lab-projection'), default='catalog')
    parser.add_argument('--node', type=Path)
    parser.add_argument('--node-modules', type=Path)
    args = parser.parse_args()
    if args.probe == 'lab-projection' and (not args.node or not args.node_modules):
        parser.error('Lab projection requires --node and --node-modules')
    target = args.directory.resolve()
    eula = target / 'eula.txt'
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower().splitlines():
        parser.error('Existing accepted eula.txt is required')
    if (target / 'plugins').exists() or (target / 'result.json').exists():
        parser.error('Use a fresh isolated directory')
    target.mkdir(parents=True, exist_ok=True)
    java = args.java_home.resolve() / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    javac = args.java_home.resolve() / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    libraries = sorted((args.libraries_from.resolve() / 'libraries').rglob('*.jar'))
    if not libraries:
        parser.error('Paper runtime libraries are required')
    server = args.server_jar.resolve()
    with zipfile.ZipFile(server) as archive:
        if 'org/bukkit/craftbukkit/Main.class' not in archive.namelist():
            parser.error('Use the extracted official Paper server JAR')
    plugins = target / 'plugins'
    plugins.mkdir()
    artifacts = {'server': server, 'provider': args.provider_jar.resolve(),
                 'plugin': args.plugin_jar.resolve(), 'bridge': args.bridge_jar.resolve()}
    for name in ('provider', 'plugin', 'bridge'):
        shutil.copyfile(artifacts[name], plugins / (name + '.jar'))
    probe_class, marker = {
        'catalog': ('HelsteraCatalogServerProbe', 'HELSTERA_CATALOG_PROBE'),
        'projection': ('HelsteraProjectionServerProbe', 'HELSTERA_PROJECTION_PROBE'),
        'lab-projection': ('HelsteraLabProjectionServerProbe', 'HELSTERA_LAB_PROJECTION_PROBE'),
    }[args.probe]
    fixture = plugins / probe_class
    model = fixture / 'model'
    model.mkdir(parents=True)
    (model / 'manifest.yml').write_text('id: stargenesis/catalog_probe\nscale: 1\ntextures: [texture.png]\n')
    (model / 'model.json').write_text('{"bones":[{"name":"root"}],"hitbox":{"width":1,"height":2}}\n')
    shutil.copyfile(args.texture, model / 'texture.png')
    provider_config = plugins / 'helsteraMobs'
    provider_config.mkdir()
    (provider_config / 'config.yml').write_text('schema-version: 1\nmodels:\n  auto-load: false\nweb:\n  enabled: false\nai:\n  enabled: false\n')
    classes = target / 'fixture-classes'
    classes.mkdir()
    fixture_source = Path(__file__).with_name(probe_class + '.java').resolve()
    classpath = os.pathsep.join(str(path) for path in [server, *libraries, artifacts['provider'], artifacts['plugin']])
    compiled = subprocess.run([str(javac), '-J-Xmx96m', '--release', '21', '-cp', classpath,
                               '-d', str(classes), str(fixture_source)], capture_output=True, text=True)
    (target / 'fixture-compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    compiled.check_returncode()
    with zipfile.ZipFile(plugins / 'probe.jar', 'w') as archive:
        archive.writestr('plugin.yml', f'name: {probe_class}\nversion: 1\nmain: {probe_class}\napi-version: "1.21.11"\ndepend: [StarGenesisHelstera]\n')
        for path in classes.glob('*.class'):
            archive.write(path, path.name)
    if args.probe == 'lab-projection':
        player = 'SGProjectAlice'
        identity = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + player).encode()).digest(), version=3)
        (target/'ops.json').write_text(json.dumps([{'uuid': str(identity), 'name': player, 'level': 4, 'bypassesPlayerLimit': False}]))
    (target / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=8\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
                                            + 'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n')
    command = [str(java), '-Xms128m', '-Xmx512m', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
               '-XX:ReservedCodeCacheSize=64m', '-XX:ActiveProcessorCount=2', '-cp',
               os.pathsep.join(str(p) for p in [server, *libraries]), 'org.bukkit.craftbukkit.Main', '--nogui']
    proc = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    output = queue.Queue()
    def read_output():
        for line in proc.stdout:
            output.put(line)
        output.put(None)
    threading.Thread(target=read_output, daemon=True).start()
    lines = []
    started = time.monotonic()
    stop_sent = False
    client = None
    client_log = None
    try:
        with (target / 'console.log').open('w', encoding='utf-8') as log:
            while time.monotonic() - started < 240:
                if client is not None and client.poll() is not None and not stop_sent:
                    proc.stdin.write('stop\n'); proc.stdin.flush(); stop_sent = True
                try:
                    line = output.get(timeout=1)
                except queue.Empty:
                    if proc.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                lines.append(line)
                log.write(line)
                log.flush()
                if args.probe == 'lab-projection' and marker + '_READY' in line and client is None:
                    client_log = (target/'client.log').open('w', encoding='utf-8')
                    client = subprocess.Popen([str(args.node.resolve()), str(Path(__file__).with_name('offline_client_projection_smoke.cjs').resolve()),
                        '--host', '127.0.0.1', '--port', str(args.port), '--module-root', str(args.node_modules.resolve()),
                        '--observed', str(fixture/'observed.json'), '--output', str(target/'client-result.json')],
                        cwd=target, stdout=client_log, stderr=subprocess.STDOUT)
                if not stop_sent and (marker + '_PASSED' in line or marker + '_FAILED' in line
                                      or 'Error occurred while enabling' in line):
                    proc.stdin.write('stop\n')
                    proc.stdin.flush()
                    stop_sent = True
            if proc.poll() is None:
                if not stop_sent:
                    proc.stdin.write('stop\n')
                    proc.stdin.flush()
                proc.wait(timeout=40)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)
        if client is not None and client.poll() is None:
            client.kill(); client.wait(timeout=10)
        if client_log is not None:
            client_log.close()
    joined = ''.join(lines)
    fixture_result = json.loads((fixture / 'result.json').read_text()) if (fixture / 'result.json').is_file() else {'passed': False}
    result = {'passed': fixture_result['passed'] and proc.returncode == 0
              and 'StarGenesis stopped cleanly' in joined and marker + '_FAILED' not in joined
              and 'Error occurred while enabling' not in joined and 'Error occurred while disabling' not in joined
              and 'model.inspect=true' in joined and ('PLUGIN_DISABLED' in joined or args.probe == 'lab-projection'),
              'fixture': fixture_result, 'exitCode': proc.returncode, 'seconds': round(time.monotonic() - started, 2),
              'sha256': {name: sha256(path) for name, path in artifacts.items()},
              'fixtureSourceSha256': sha256(fixture_source), 'logSha256': sha256(target / 'console.log'),
              'scope': ('Actual Paper/provider passive entity lifecycle with synthetic lab frames; no clients or visual acceptance'
                        if args.probe == 'projection' else 'Actual Paper/provider read-only catalog lifecycle; no clients, entity rendering or Folia support')}
    if args.probe == 'lab-projection':
        observed_client = json.loads((target/'client-result.json').read_text()) if (target/'client-result.json').exists() else {'passed': False}
        result.update(client=observed_client, clientExitCode=client.returncode if client else None,
                      scope='Actual Paper lab worker, model entities and two fixed offline clients; no rendered visual or human acceptance')
        result['passed'] = result['passed'] and observed_client['passed'] and client is not None and client.returncode == 0
    (target / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
