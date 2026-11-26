import copy
import unittest

from server_match_admin_smoke import validate_matches


def sample(phase):
    ids = {name: f'00000000-0000-0000-0000-{index:012d}'
           for index, name in enumerate(('alice', 'bob', 'carol', 'dave', 'admin'), 1)}
    state = {'identities': ids, 'seed': 77, 'old': {'factory': 'factory-old', 'arena': 'arena-old'},
             'active': {'factory': 'factory-new', 'arena': 'arena-new'}}
    pairs = [('alice', 'bob'), ('carol', 'dave')] if phase == 'prepare' else [('alice', 'carol'), ('bob', 'dave')]
    teams = {ids[name]: team for pair, team in zip(pairs, ('cedar', 'ember')) for name in pair}
    selected = state['old'] if phase == 'prepare' else state['active']
    factory = {'schemaVersion': 2, 'matchId': selected['factory'], 'runId': 'factory-run', 'seed': 77,
               'participants': sorted(teams), 'config': {'maxTicks': 100}, 'frames': [{}] * (100 if phase == 'prepare' else 0)}
    arena = {'schemaVersion': 1, 'replay': {'schemaVersion': 2, 'matchId': selected['arena'], 'runId': 'arena-run',
             'seed': 42, 'teams': teams, 'config': {'durationSteps': 2}, 'frames': [{}] * (2 if phase == 'prepare' else 0)},
             'pending': {} if phase == 'prepare' else {ids['alice']: {'actor': ids['alice'], 'type': 'PASS'}},
             'spectators': [ids['admin']] if phase == 'prepare' else []}
    saved = {'factory/organizations.json': {'schemaVersion': 1, 'pairs': [
                {'first': ids[first], 'second': ids[second]} for first, second in pairs]},
             'arena-matches/teams.json': {'schemaVersion': 1, 'teams': teams},
             f"factory/{selected['factory']}.factory.json": {'schemaVersion': 1, 'replay': factory},
             f"arena-matches/{selected['arena']}.match.json": arena}
    return saved, state


class MatchAdminEvidenceTest(unittest.TestCase):
    def test_reorganization_rejects_resurrected_history_lost_pending_and_old_pairs(self):
        saved, state = sample('verify')
        validate_matches(saved, 'verify', state)
        variants = []
        extra = copy.deepcopy(saved)
        extra['factory/factory-old.factory.json'] = extra['factory/factory-new.factory.json']
        variants.append(extra)
        missing = copy.deepcopy(saved)
        missing['arena-matches/arena-new.match.json']['pending'] = {}
        variants.append(missing)
        old_pairs = copy.deepcopy(saved)
        old_pairs['factory/organizations.json'] = sample('prepare')[0]['factory/organizations.json']
        variants.append(old_pairs)
        impostor = copy.deepcopy(saved)
        impostor['arena-matches/arena-new.match.json']['pending'][state['identities']['alice']]['actor'] = state['identities']['bob']
        variants.append(impostor)
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(AssertionError):
                validate_matches(variant, 'verify', state)

    def test_terminal_evidence_rejects_unfinished_matches_and_incorrect_rosters(self):
        saved, state = sample('prepare')
        validate_matches(saved, 'prepare', state)
        variants = []
        unfinished = copy.deepcopy(saved)
        unfinished['factory/factory-old.factory.json']['replay']['frames'].pop()
        variants.append(unfinished)
        pending = copy.deepcopy(saved)
        pending['arena-matches/arena-old.match.json']['pending'] = {'unexpected': {}}
        variants.append(pending)
        wrong_team = copy.deepcopy(saved)
        wrong_team['arena-matches/teams.json']['teams'][state['identities']['alice']] = 'ember'
        variants.append(wrong_team)
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(AssertionError):
                validate_matches(variant, 'prepare', state)


if __name__ == '__main__':
    unittest.main()
