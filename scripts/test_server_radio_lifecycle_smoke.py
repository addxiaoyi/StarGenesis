"""Verify that incomplete or rewritten radio shutdown evidence cannot pass the live harness."""
import copy
import unittest

from server_radio_lifecycle_smoke import validate_snapshot


class RadioShutdownEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.saved = {'schemaVersion': 1, 'radio': {'tick': 10, 'evidence': [1, 2], 'pending': [1, 2],
                      'acknowledged': [], 'events': [1, 2]}, 'contracts': {'tick': 10, 'states': [], 'received': [], 'events': []}}
        self.observed = {'tick': 9, 'messages': 2, 'pending': 2, 'acknowledged': 0, 'radioEvents': 2, 'received': 0}

    def test_shutdown_rejects_partial_save_clock_rollback_or_client_observation_loss(self):
        validate_snapshot(self.saved, 'prepare', self.observed, None)
        for mutate in (lambda doc: doc['radio']['pending'].pop(), lambda doc: doc['radio'].update(tick=8),
                       lambda doc: doc['contracts'].update(tick=11), lambda doc: doc.update(schemaVersion=2)):
            invalid = copy.deepcopy(self.saved)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                validate_snapshot(invalid, 'prepare', self.observed, None)
        with self.assertRaises(AssertionError):
            validate_snapshot(self.saved, 'prepare', {**self.observed, 'messages': 3}, None)

    def test_previous_committed_history_cannot_be_lost_or_replaced(self):
        validate_snapshot(self.saved, 'prepare', self.observed, copy.deepcopy(self.saved))
        for mutate in (lambda doc: doc['radio']['evidence'].append(3), lambda doc: doc['radio'].update(events=[2, 1]),
                       lambda doc: doc['contracts']['received'].append(3), lambda doc: doc['contracts']['events'].append(4)):
            previous = copy.deepcopy(self.saved)
            mutate(previous)
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                validate_snapshot(self.saved, 'prepare', self.observed, previous)


if __name__ == '__main__':
    unittest.main()
