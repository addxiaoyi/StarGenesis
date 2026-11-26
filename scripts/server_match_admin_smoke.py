"""Three actual boots exercise terminal match pruning and persistent reorganization.

Uses the existing loopback-only server harness and five fixed offline identities.
Requires an accepted EULA and fresh isolated directory. Preserves failure evidence.
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
from server_lifecycle_smoke import assert_worker_locks_released


def manifest(data: Path) -> dict[str, str]:
    return {file.relative_to(data).as_posix(): sha256(file)
            for directory in ('factory', 'arena-matches', 'arenas')
            for file in sorted((data / directory).rglob('*.json'))}


def validate_matches(saved: dict, phase: str, state: dict) -> None:
    ids = state['identities']
    assert len(set(ids.values())) == len(ids) == 5
    old = state['old']
    selected = old if phase == 'prepare' else state['active']
    expected_keys = {'factory/organizations.json', 'arena-matches/teams.json',
                     f"factory/{selected['factory']}.factory.json", f"arena-matches/{selected['arena']}.match.json"}
    assert set(saved) == expected_keys, 'Missing, unexpected or resurrected match files'
    for value in saved.values():
        assert value['schemaVersion'] == 1
    factory = saved[f"factory/{selected['factory']}.factory.json"]['replay']
    arena = saved[f"arena-matches/{selected['arena']}.match.json"]
    expected_pairs = [('alice', 'bob'), ('carol', 'dave')] if phase == 'prepare' else [('alice', 'carol'), ('bob', 'dave')]
    pairs = sorted(sorted([ids[first], ids[second]]) for first, second in expected_pairs)
    assert sorted(sorted([pair['first'], pair['second']]) for pair in saved['factory/organizations.json']['pairs']) == pairs
    teams = {ids[player]: team for pair, team in zip(expected_pairs, ('cedar', 'ember')) for player in pair}
    assert saved['arena-matches/teams.json']['teams'] == teams
    assert arena['replay']['teams'] == teams
    assert sorted(factory['participants']) == sorted(teams)
    assert factory['matchId'] == selected['factory'] and arena['replay']['matchId'] == selected['arena']
    assert factory['runId'] and arena['replay']['runId']
    assert factory['schemaVersion'] == arena['replay']['schemaVersion'] == 2
    assert factory['seed'] == state['seed'] and arena['replay']['seed'] == 42
    assert factory['config']['maxTicks'] == 100 and arena['replay']['config']['durationSteps'] == 2
    if phase == 'prepare':
        assert len(factory['frames']) == 100 and len(arena['replay']['frames']) == 2
        assert arena['pending'] == {} and arena['spectators'] == [ids['admin']]
    else:
        assert selected['factory'] != old['factory'] and selected['arena'] != old['arena']
        assert not factory['frames'] and not arena['replay']['frames']
        assert arena['spectators'] == [] and set(arena['pending']) == {ids['alice']}
        assert arena['pending'][ids['alice']]['actor'] == ids['alice']
        assert arena['pending'][ids['alice']]['type'] == 'PASS'


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
    parser.add_argument('--client-timeout', type=int, default=360)
    args = parser.parse_args()
    target = args.directory.resolve()
    data = target / 'plugins/StarGenesis'
    evidence = target / 'match-admin-evidence'
    client_state = target / 'plugins/match-admin-client-state.json'
    retired = target / 'plugins/match-admin-retired'
    eula = target / 'eula.txt'
    if not eula.is_file() or 'eula=true' not in {line.strip().lower() for line in eula.read_text().splitlines()}:
        parser.error('An already accepted eula.txt is required')
    if evidence.exists() or manifest(data) or client_state.exists() or retired.exists():
        parser.error('Use a fresh isolated directory; existing match data/evidence must not be overwritten')
    (target / 'plugins').mkdir(exist_ok=True)
    evidence.mkdir()
    result = {'passed': False, 'kind': args.kind, 'runs': [], 'scope':
              'Five automatic clients, three normal boots, terminal pruning and reorganization; no saturation, crash, cross-region, human or performance claim.'}
    previous = identities = None
    started = time.monotonic()
    try:
        for phase in ('prepare', 'reorganize', 'verify'):
            destination = evidence / phase
            destination.mkdir()
            request = {'phase': phase, 'manifest': previous}
            (target / 'plugins/match-admin-input.json').write_text(json.dumps(request) + '\n', encoding='utf-8')
            command = [sys.executable, str(Path(__file__).with_name('server_smoke.py')),
                       '--java', args.java, '--node', args.node, '--server-jar', str(args.server_jar.resolve()),
                       '--plugin-jar', str(args.plugin_jar.resolve()), '--directory', str(target),
                       '--kind', args.kind, '--port', str(args.port), '--heap-mb', str(args.heap_mb),
                       '--max-players', '8', '--timeout', str(args.timeout), '--player-workflow',
                       '--node-modules', str(args.node_modules.resolve()), '--player-client-timeout', str(args.client_timeout),
                       '--client-script', str(Path(__file__).with_name('offline_client_match_admin_smoke.cjs'))]
            command.extend(f'--jvm-arg={value}' for value in args.jvm_arg)
            if args.libraries_from:
                command.extend(['--libraries-from', str(args.libraries_from.resolve())])
            print(f'Starting {args.kind} match admin {phase}', flush=True)
            with (destination / 'harness.log').open('w', encoding='utf-8') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            for filename in ('smoke-console.log', 'smoke-result.json', 'player-workflow.log', 'player-workflow-result.json'):
                if (target / filename).is_file():
                    shutil.copy2(target / filename, destination / filename)
            saved_manifest = manifest(data)
            for relative in saved_manifest:
                copy = destination / 'data' / relative
                copy.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(data / relative, copy)
            if client_state.is_file():
                shutil.copy2(client_state, destination / 'client-state.json')
            if retired.is_dir():
                shutil.copytree(retired, destination / 'retired')
            smoke = json.loads((destination / 'smoke-result.json').read_text(encoding='utf-8'))
            result['runs'].append({'phase': phase, 'exitCode': completed.returncode, 'server': smoke})
            assert completed.returncode == 0 and smoke['passed'], f'{phase} server/client failed'
            workflow = smoke['player_workflow']
            assert workflow['phase'] == phase and workflow['players'] == 5 and workflow['checks']
            assert workflow['manifest'] == saved_manifest, 'Shutdown changed match or published design bytes'
            assert identities is None or identities == workflow['identities']
            identities = workflow['identities']
            saved = {relative: json.loads((data / relative).read_text(encoding='utf-8'))
                     for relative in saved_manifest if relative.startswith(('factory/', 'arena-matches/'))}
            validate_matches(saved, phase, workflow['state'])
            if phase != 'prepare':
                old = workflow['state']['old']
                assert sha256(retired / 'factory.json') == result['runs'][0]['manifest'][f"factory/{old['factory']}.factory.json"]
                assert sha256(retired / 'arena.json') == result['runs'][0]['manifest'][f"arena-matches/{old['arena']}.match.json"]
                assert {name: digest for name, digest in saved_manifest.items() if name.startswith('arenas/')} == {
                    name: digest for name, digest in previous.items() if name.startswith('arenas/')}
            if phase == 'verify':
                assert saved_manifest == previous
            result['runs'][-1]['manifest'] = saved_manifest
            result['runs'][-1]['releasedLocks'] = assert_worker_locks_released(data)
            previous = saved_manifest
            print(f'Passed {args.kind} match admin {phase}', flush=True)
        result['passed'] = True
    except (OSError, AssertionError, KeyError, ValueError) as failure:
        result['error'] = f'{type(failure).__name__}: {failure}'
    finally:
        result['seconds'] = round(time.monotonic() - started, 2)
        result['artifacts'] = {file.relative_to(evidence).as_posix(): sha256(file)
                               for file in sorted(evidence.rglob('*')) if file.is_file()}
        (evidence / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': result['passed'], 'kind': args.kind, 'error': result.get('error'), 'seconds': result['seconds']}))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
