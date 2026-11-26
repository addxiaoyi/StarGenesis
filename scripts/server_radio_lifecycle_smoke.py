"""Run three actual Paper/Folia boots to check durable P4 delivery and contract recovery.

Requires an already accepted eula.txt and a fresh isolated directory. Records all
boots and committed snapshots. No heap, throughput, forced-crash or human claims.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from server_smoke import sha256


def validate_snapshot(snapshot: dict, phase: str, observed: dict, previous: dict | None) -> None:
    radio, contracts = snapshot['radio'], snapshot['contracts']
    assert snapshot['schemaVersion'] == 1
    assert radio['tick'] == contracts['tick'] >= observed['tick']
    for key, actual in (('messages', len(radio['evidence'])), ('pending', len(radio['pending'])),
                        ('acknowledged', len(radio['acknowledged'])), ('radioEvents', len(radio['events'])),
                        ('received', len(contracts['received']))):
        assert observed[key] == actual, f'Shutdown changed client-observed {key}'
    if previous is not None:
        assert radio['tick'] >= previous['radio']['tick']
        assert all(message in radio['evidence'] for message in previous['radio']['evidence'])
        assert radio['events'][:len(previous['radio']['events'])] == previous['radio']['events']
        assert contracts['events'][:len(previous['contracts']['events'])] == previous['contracts']['events']
        assert all(message in contracts['received'] for message in previous['contracts']['received'])
    expected = {'prepare': (2, 2, 0, 2, 0), 'resume': (6, 1, 5, 11, 4), 'verify': (6, 0, 6, 12, 4)}[phase]
    actual = (len(radio['evidence']), len(radio['pending']), len(radio['acknowledged']),
              len(radio['events']), len(contracts['received']))
    assert actual == expected, (phase, actual, expected)
    states = {state['id']: state for state in contracts['states']}
    if phase == 'prepare':
        assert not states and not contracts['events']
    else:
        assert set(states) == {'restart_order', 'expires_after_restart'}
        assert states['restart_order']['state'] == 'COMPLETED'
        assert states['restart_order']['fulfilledUnits'] == states['restart_order']['requestedUnits'] == 3
        expiring = states['expires_after_restart']
        assert expiring['state'] == ('EXPIRED' if contracts['tick'] > expiring['expiresAt'] else 'OFFERED')
        assert len(contracts['events']) == (5 if expiring['state'] == 'EXPIRED' else 4)
        if phase == 'verify':
            assert expiring['state'] == 'EXPIRED'
            event = contracts['events'][-1]
            assert (event['type'], event['tick'], event['contractId']) == ('contract.expire', expiring['expiresAt'] + 1, expiring['id'])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--node', default='node')
    parser.add_argument('--server-jar', type=Path, required=True)
    parser.add_argument('--plugin-jar', type=Path, required=True)
    parser.add_argument('--libraries-from', type=Path)
    parser.add_argument('--node-modules', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--kind', choices=['paper', 'folia'], required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--heap-mb', type=int, default=512)
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--client-timeout', type=int, default=180)
    args = parser.parse_args()
    target = args.directory.resolve()
    data = target / 'plugins/StarGenesis'
    evidence = target / 'radio-lifecycle-evidence'
    state = data / 'radio-state.json'
    client_state = target / 'plugins/radio-lifecycle-client-state.json'
    eula = target / 'eula.txt'
    if not eula.is_file() or 'eula=true' not in {line.strip().lower() for line in eula.read_text().splitlines()}:
        parser.error('An already accepted eula.txt is required')
    if evidence.exists() or state.exists() or client_state.exists():
        parser.error('Use a fresh directory; no existing radio evidence or state may be replaced')
    (target / 'plugins').mkdir(exist_ok=True)
    evidence.mkdir()
    result = {'passed': False, 'kind': args.kind, 'runs': [], 'scope':
              'Three actual boots, two fixed offline clients; no forced crash, cross-region, human or performance acceptance.'}
    started = time.monotonic()
    previous = previous_file = None
    identities = None
    try:
        for phase in ('prepare', 'resume', 'verify'):
            destination = evidence / phase
            destination.mkdir()
            request = {'phase': phase, 'previousSnapshot': str(previous_file) if previous_file else None}
            (target / 'plugins/radio-lifecycle-input.json').write_text(json.dumps(request) + '\n', encoding='utf-8')
            command = [sys.executable, str(Path(__file__).with_name('server_smoke.py')),
                       '--java', args.java, '--node', args.node, '--server-jar', str(args.server_jar.resolve()),
                       '--plugin-jar', str(args.plugin_jar.resolve()), '--directory', str(target),
                       '--kind', args.kind, '--port', str(args.port), '--heap-mb', str(args.heap_mb),
                       '--max-players', '8', '--timeout', str(args.timeout), '--player-workflow',
                       '--node-modules', str(args.node_modules.resolve()), '--player-client-timeout', str(args.client_timeout),
                       '--client-script', str(Path(__file__).with_name('offline_client_radio_lifecycle_smoke.cjs'))]
            command.extend(f'--jvm-arg={value}' for value in args.jvm_arg)
            if args.libraries_from:
                command.extend(['--libraries-from', str(args.libraries_from.resolve())])
            print(f'Starting {args.kind} radio {phase}', flush=True)
            with (destination / 'harness.log').open('w', encoding='utf-8') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            for filename in ('smoke-console.log', 'smoke-result.json', 'player-workflow.log', 'player-workflow-result.json'):
                if (target / filename).is_file():
                    shutil.copy2(target / filename, destination / filename)
            if state.is_file():
                shutil.copy2(state, destination / 'radio-state.json')
            if client_state.is_file():
                shutil.copy2(client_state, destination / 'client-state.json')
            smoke = json.loads((destination / 'smoke-result.json').read_text(encoding='utf-8'))
            result['runs'].append({'phase': phase, 'exitCode': completed.returncode, 'server': smoke})
            assert completed.returncode == 0 and smoke['passed'], f'{phase} server/client failed'
            workflow = smoke['player_workflow']
            assert workflow['phase'] == phase and workflow['players'] == 2 and workflow['checks']
            if identities is not None:
                assert identities == workflow['identities']
            identities = workflow['identities']
            saved = json.loads(state.read_text(encoding='utf-8'))
            validate_snapshot(saved, phase, workflow['snapshotAtFinish'], previous)
            result['runs'][-1]['snapshotSha256'] = sha256(state)
            result['runs'][-1]['validatedShutdownSnapshot'] = True
            previous = saved
            previous_file = destination / 'radio-state.json'
            print(f'Passed {args.kind} radio {phase}', flush=True)
        result['passed'] = True
    except (OSError, AssertionError, KeyError, ValueError) as failure:
        result['error'] = f'{type(failure).__name__}: {failure}'
    finally:
        result['seconds'] = round(time.monotonic() - started, 2)
        result['artifacts'] = {file.relative_to(evidence).as_posix(): sha256(file)
                               for file in sorted(evidence.rglob('*')) if file.is_file()}
        (evidence / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({key: result[key] for key in ('kind', 'passed', 'seconds')}), flush=True)
    if not result['passed']:
        print(result.get('error', 'Failed radio recovery'), flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
