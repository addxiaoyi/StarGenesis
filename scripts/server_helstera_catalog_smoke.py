"""Exercise the read-only bridge against an actual fixed Helstera build on isolated Paper.
Requires a fresh directory containing an already accepted eula.txt. No player/visual claim.
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from server_smoke import sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'server-jar', 'provider-jar', 'plugin-jar', 'bridge-jar', 'libraries-from', 'directory', 'texture'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, default=25683)
    args = parser.parse_args()
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
    fixture = plugins / 'HelsteraCatalogServerProbe'
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
    fixture_source = Path(__file__).with_name('HelsteraCatalogServerProbe.java').resolve()
    classpath = os.pathsep.join(str(path) for path in [server, *libraries, artifacts['provider'], artifacts['plugin']])
    compiled = subprocess.run([str(javac), '-J-Xmx96m', '--release', '21', '-cp', classpath,
                               '-d', str(classes), str(fixture_source)], capture_output=True, text=True)
    (target / 'fixture-compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    compiled.check_returncode()
    with zipfile.ZipFile(plugins / 'probe.jar', 'w') as archive:
        archive.writestr('plugin.yml', 'name: HelsteraCatalogServerProbe\nversion: 1\nmain: HelsteraCatalogServerProbe\napi-version: "1.21.11"\ndepend: [StarGenesisHelstera]\n')
        for path in classes.glob('*.class'):
            archive.write(path, path.name)
    (target / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
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
    try:
        with (target / 'console.log').open('w', encoding='utf-8') as log:
            while time.monotonic() - started < 240:
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
                if not stop_sent and ('HELSTERA_CATALOG_PROBE_PASSED' in line or 'HELSTERA_CATALOG_PROBE_FAILED' in line
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
    joined = ''.join(lines)
    fixture_result = json.loads((fixture / 'result.json').read_text()) if (fixture / 'result.json').is_file() else {'passed': False}
    result = {'passed': fixture_result['passed'] and proc.returncode == 0
              and 'StarGenesis stopped cleanly' in joined and 'HELSTERA_CATALOG_PROBE_FAILED' not in joined
              and 'Error occurred while enabling' not in joined and 'Error occurred while disabling' not in joined
              and 'model.inspect=true' in joined and 'PLUGIN_DISABLED' in joined,
              'fixture': fixture_result, 'exitCode': proc.returncode, 'seconds': round(time.monotonic() - started, 2),
              'sha256': {name: sha256(path) for name, path in artifacts.items()},
              'fixtureSourceSha256': sha256(fixture_source), 'logSha256': sha256(target / 'console.log'),
              'scope': 'Actual Paper/provider read-only catalog lifecycle; no clients, entity rendering or Folia support'}
    (target / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
