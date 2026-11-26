"""Run isolated Paper item/registry probes twice, preserving original payload bytes across restart."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import zipfile
import uuid


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def diagnostics(lines):
    """Only the exact provider hot-disable warning inside our deliberate probe is expected."""
    errors, expected = [], []
    disabling = False
    for raw in lines:
        line = re.sub(r'\x1b\[[0-9;]*m', '', raw).strip()
        if 'CRAFTENGINE_INTENTIONAL_DISABLE_BEGIN' in line: disabling = True
        if 'CRAFTENGINE_INTENTIONAL_DISABLE_END' in line: disabling = False
        if 'ERROR' not in line and 'SEVERE' not in line: continue
        message = re.fullmatch(r'\[\d{2}:\d{2}:\d{2} ERROR\]: \[CraftEngine\](?: (.*))?', line)
        if disabling and message and (message[1] or '') in ('', 'Please do not disable plugins at runtime.'):
            expected.append(line)
        else: errors.append(line)
    if expected and (len(expected) != 7 or sum('Please do not disable plugins at runtime.' in line for line in expected) != 1):
        errors.append('Unexpected provider hot-disable warning sequence')
    return errors, expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'server-jar', 'provider-jar', 'plugin-jar', 'bridge-jar',
                 'libraries-from', 'provider-data-from', 'directory'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, default=25685)
    parser.add_argument('--runs', type=int, choices=(1,2), default=2)
    parser.add_argument('--clients', action='store_true')
    parser.add_argument('--node', type=Path)
    parser.add_argument('--node-modules', type=Path)
    args = parser.parse_args()
    if args.clients and (not args.node or not args.node_modules or args.runs != 1):
        parser.error('--clients requires --runs 1, --node and --node-modules')
    target = args.directory.resolve()
    eula = target / 'eula.txt'
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower().splitlines():
        parser.error('An existing accepted eula.txt is required')
    if (target / 'plugins').exists() or (target / 'result.json').exists():
        parser.error('Use a fresh directory; previous failure evidence must be preserved')
    java = args.java_home.resolve() / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    javac = args.java_home.resolve() / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    libraries = sorted((args.libraries_from.resolve() / 'libraries').rglob('*.jar'))
    if not libraries: parser.error('Paper runtime libraries are required')
    server = args.server_jar.resolve()
    artifacts = {'server': server, 'provider': args.provider_jar.resolve(),
                 'plugin': args.plugin_jar.resolve(), 'bridge': args.bridge_jar.resolve()}
    plugins = target / 'plugins'
    plugins.mkdir()
    for name in ('provider', 'plugin', 'bridge'):
        shutil.copyfile(artifacts[name], plugins / (name + '.jar'))
    provider = plugins / 'CraftEngine'
    provider.mkdir()
    shutil.copytree(args.provider_data_from / 'libs', provider / 'libs')
    config = (args.provider_data_from / 'config.yml').read_text(encoding='utf-8')
    config = config.replace('metrics: true', 'metrics: false').replace('update-checker: true', 'update-checker: false')
    for section, following in (('workflows','packs'), ('packs','presets'), ('presets','self-host')):
        config, count = re.subn(r'(?ms)^  ' + section + r':\n.*?(?=^  ' + following + r':)', '  ' + section + ': {}\n', config)
        if count != 1: raise ValueError('Unexpected provider configuration section: ' + section)
    config = re.sub(r'(?m)^(\s*send-on-join:) true$', r'\1 false', config)
    (provider / 'config.yml').write_text(config, encoding='utf-8')
    pack = provider / 'resources/sg_item_probe'
    (pack / 'configuration').mkdir(parents=True)
    (pack / 'pack.yml').write_text('namespace: stargenesis\nname: StarGenesis isolated item probe\nversion: 1\n', encoding='utf-8')
    definition = {'items': {'stargenesis:item_probe': {
        'material': 'paper', 'data': {'item_name': 'Template initial', 'max_stack_size': 4},
        'settings': {'tags': ['stargenesis:probe']},
        'model': {'type': 'minecraft:model', 'path': 'minecraft:item/paper'}}}}
    (pack / 'configuration/items.yml').write_text(json.dumps(definition, indent=2) + '\n', encoding='utf-8')
    probe_class = 'CraftEngineItemClientProbe' if args.clients else 'CraftEngineItemServerProbe'
    fixture_source = Path(__file__).with_name(probe_class + '.java').resolve()
    classes = target / 'fixture-classes'
    classes.mkdir()
    classpath = os.pathsep.join(str(p) for p in [server, *libraries, artifacts['provider'], artifacts['plugin']])
    compilation = subprocess.run([str(javac), '-J-Xmx96m', '-encoding', 'UTF-8', '--release', '21', '-cp', classpath,
                                  '-d', str(classes), str(fixture_source)], capture_output=True, text=True)
    (target / 'fixture-compile.log').write_text(compilation.stdout + compilation.stderr, encoding='utf-8')
    compilation.check_returncode()
    with zipfile.ZipFile(plugins / 'probe.jar', 'w') as archive:
        archive.writestr('plugin.yml', f'name: {probe_class}\nversion: 1\nmain: {probe_class}\napi-version: "1.21.11"\ndepend: [StarGenesisCraftEngine]\n')
        for path in classes.glob('*.class'): archive.write(path, path.name)
    (target / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=8\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nspawn-animals=false\nspawn-monsters=false\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n', encoding='utf-8')
    fixture = plugins / probe_class
    if args.clients:
        name = 'SGItemAdmin'
        identity = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3)
        (target / 'ops.json').write_text(json.dumps([{'uuid': str(identity), 'name': name, 'level': 4, 'bypassesPlayerLimit': False}]))
    runs = []
    for run in range(1, args.runs + 1):
        command = [str(java), '-Xms128m', '-Xmx512m', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
                   '-XX:ReservedCodeCacheSize=64m', '-XX:ActiveProcessorCount=2', f'-Dstargenesis.probe.run={run}',
                   '-cp', os.pathsep.join(str(p) for p in [server, *libraries]), 'org.bukkit.craftbukkit.Main', '--nogui']
        print('Starting Paper item probe run', run, flush=True)
        proc = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        output = queue.Queue()
        def drain():
            for line in proc.stdout: output.put(line)
            output.put(None)
        threading.Thread(target=drain, daemon=True).start()
        lines = []
        started = time.monotonic()
        stop_sent = False
        client = client_log = None
        log_path = target / f'console-{run}.log'
        try:
            with log_path.open('w', encoding='utf-8') as log:
                while time.monotonic() - started < 240:
                    if client is not None and client.poll() is not None and not stop_sent:
                        proc.stdin.write('stop\n'); proc.stdin.flush(); stop_sent = True
                    try: line = output.get(timeout=1)
                    except queue.Empty:
                        if proc.poll() is not None: break
                        continue
                    if line is None: break
                    lines.append(line); log.write(line); log.flush()
                    if args.clients and 'CRAFTENGINE_ITEM_CLIENT_READY' in line and client is None:
                        client_log = (target / 'client.log').open('w', encoding='utf-8')
                        client = subprocess.Popen([str(args.node.resolve()), str(Path(__file__).with_name('offline_client_item_smoke.cjs').resolve()),
                            '--host', '127.0.0.1', '--port', str(args.port), '--module-root', str(args.node_modules.resolve()),
                            '--observed', str(fixture / 'observed.json'), '--output', str(target / 'client-result.json')],
                            cwd=target, stdout=client_log, stderr=subprocess.STDOUT)
                    if not stop_sent and ('CRAFTENGINE_ITEM_PROBE_PASSED' in line or 'CRAFTENGINE_ITEM_PROBE_FAILED' in line
                                          or 'CRAFTENGINE_ITEM_CLIENT_FAILED' in line
                                          or 'Error occurred while enabling' in line):
                        proc.stdin.write('stop\n'); proc.stdin.flush(); stop_sent = True
                if proc.poll() is None and not stop_sent:
                    proc.stdin.write('stop\n'); proc.stdin.flush()
            proc.wait(timeout=50)
        finally:
            if proc.poll() is None: proc.kill(); proc.wait(timeout=10)
            if client is not None and client.poll() is None: client.kill(); client.wait(timeout=10)
            if client_log is not None: client_log.close()
        path = fixture / ('observed.json' if args.clients else f'result-{run}.json')
        observed = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'passed': False}
        joined = ''.join(lines)
        errors, expected = diagnostics(lines)
        passed = (observed['passed'] and proc.returncode == 0 and not errors
                  and (args.clients or 'CRAFTENGINE_ITEM_PROBE_PASSED' in joined) and 'CRAFTENGINE_ITEM_PROBE_FAILED' not in joined
                  and 'StarGenesis stopped cleanly' in joined)
        client_result = None
        if args.clients:
            path = target / 'client-result.json'
            client_result = json.loads(path.read_text()) if path.exists() else {'passed': False}
            passed = passed and client_result['passed'] and client is not None and client.returncode == 0 and observed.get('stopped') is True
        runs.append({'passed': passed, 'run': run, 'seconds': round(time.monotonic() - started, 2),
                     'exitCode': proc.returncode, 'fixture': observed, 'errors': errors,
                     'expectedProviderDisableWarnings': expected, 'logSha256': sha(log_path)})
        if args.clients: runs[-1].update(client=client_result, clientExitCode=client.returncode if client else None)
        result = {'passed': all(r['passed'] for r in runs) and len(runs) == args.runs, 'runs': runs,
                  'sha256': {name: sha(path) for name, path in artifacts.items()},
                  'fixtureSourceSha256': sha(fixture_source), 'providerConfigSha256': sha(provider / 'config.yml'),
                  'scope': 'Actual Paper/CraftEngine detached item state, registry reload and sequential process restart; no inventory delivery or visual acceptance'}
        if args.clients:
            result['scope'] = 'Actual Paper/CraftEngine held item commands, two offline clients and full inventory observation; fixture provisioning, no production delivery or rendered visual acceptance'
        if len(runs) == 2:
            result['samePersistedPayloadAcrossRestart'] = (runs[1]['fixture'].get('restoredFromPreviousProcess') is True
                and runs[0]['fixture'].get('payloadSha256') == runs[1]['fixture'].get('payloadSha256'))
            result['passed'] &= result['samePersistedPayloadAcrossRestart']
        (target / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        if not passed: raise SystemExit(json.dumps(result))
    print(json.dumps(result))


if __name__ == '__main__': main()
